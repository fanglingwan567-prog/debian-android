package app.birch.debian;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import javax.net.ssl.HttpsURLConnection;
import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Public, anonymous pulls from library/debian only; no networking in the root helper. */
final class DebianRegistry {
    enum Channel { STABLE, TESTING }
    private static final String HUB="https://hub.docker.com/v2/namespaces/library/repositories/debian/tags";
    private static final String REGISTRY="https://registry-1.docker.io/v2/library/debian/";
    private static final String AUTH="https://auth.docker.io/token?service=registry.docker.io&scope=repository:library/debian:pull";
    private static final String MANIFEST="application/vnd.oci.image.manifest.v1+json, application/vnd.docker.distribution.manifest.v2+json";
    private static final int JSON_LIMIT=2*1024*1024;
    static final class Version {
        final String tag, label, manifest;
        private final int order;
        Version(String tag,String label,String manifest,int order){this.tag=tag;this.label=label;this.manifest=manifest;this.order=order;}
    }
    static final class Layer {
        final String digest;
        final long bytes;
        Layer(String digest,long bytes) throws IOException {
            if(!digest.matches("[a-f0-9]{64}") || bytes<1 || bytes>256L*1024*1024)throw new IOException("镜像摘要或大小不受支持");
            this.digest=digest;this.bytes=bytes;
        }
    }
    private static void interrupted() throws InterruptedIOException {
        if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("操作已中断");
    }
    private static HttpsURLConnection get(String address,String accept,String token) throws IOException {
        URL url=new URL(address);
        for(int redirects=0;redirects<=5;redirects++){
            interrupted();
            if(!url.getProtocol().equals("https") || url.getUserInfo()!=null)throw new IOException("拒绝不安全的镜像地址");
            HttpsURLConnection c=(HttpsURLConnection)url.openConnection();
            c.setRequestMethod("GET");c.setInstanceFollowRedirects(false);c.setUseCaches(false);
            c.setConnectTimeout(15000);c.setReadTimeout(25000);
            c.setRequestProperty("Accept",accept);c.setRequestProperty("Accept-Encoding","identity");
            if(token!=null)c.setRequestProperty("Authorization","Bearer "+token);
            try{
                int code=c.getResponseCode();
                if(code==200)return c;
                if(code==301 || code==302 || code==303 || code==307 || code==308){
                    String location=c.getHeaderField("Location");
                    if(location==null)throw new IOException("镜像服务返回空跳转");
                    URL dest=new URL(url,location);
                    if(!dest.getHost().equalsIgnoreCase(url.getHost()) || dest.getPort()!=url.getPort())token=null;
                    url=dest;
                }else if(code==429)throw new IOException("镜像服务请求过于频繁，请稍后重试（HTTP 429）");
                else throw new IOException(url.getHost()+" 返回 HTTP "+code);
            }catch(IOException ex){c.disconnect();throw ex;}
            c.disconnect();
        }
        throw new IOException("镜像服务跳转次数过多");
    }
    private static byte[] metadata(String address,String accept,String token) throws IOException {
        HttpsURLConnection c=get(address,accept,token);
        try(InputStream in=c.getInputStream(); ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] b=new byte[8192];int n;
            while((n=in.read(b))!=-1){interrupted();if(out.size()+n>JSON_LIMIT)throw new IOException("镜像目录过大");out.write(b,0,n);}
            return out.toByteArray();
        }finally{c.disconnect();}
    }
    private static JSONObject json(byte[] raw) throws JSONException {return new JSONObject(new String(raw,StandardCharsets.UTF_8));}
    private static String branchKey(Channel channel) throws IOException {
        String suite="stable";if(channel==Channel.TESTING)suite="testing";
        HttpsURLConnection c=get("https://deb.debian.org/debian/dists/"+suite+"/Release","text/plain",null);
        // The release identity is in the header; don't download the large checksum table.
        try(InputStream in=c.getInputStream()){
            byte[] head=new byte[2048];int used=0;
            while(used<head.length){int n=in.read(head,used,head.length-used);if(n<0)break;used+=n;}
            String text=new String(head,0,used,StandardCharsets.US_ASCII);
            if(!text.contains("Suite: "+suite+"\n"))throw new IOException("Debian 分支资料不匹配");
            String pattern="(?m)^Version: ([0-9]{1,3})(?:\\.[0-9]+)*$";
            if(channel==Channel.TESTING)pattern="(?m)^Codename: ([a-z]{2,32})$";
            Matcher match=Pattern.compile(pattern).matcher(text);
            if(!match.find())throw new IOException("无法识别 Debian 分支版本");
            return match.group(1);
        }finally{c.disconnect();}
    }
    static List<Version> catalog(Channel channel) throws Exception {
        String key=branchKey(channel);String query=key+".";if(channel==Channel.TESTING)query=key+"-";
        JSONObject page=json(metadata(HUB+"?page_size=60&name="+query,"application/json",null));
        return versions(channel,key,page.getJSONArray("results"));
    }
    static List<Version> versions(Channel channel,String key,JSONArray results) throws JSONException,IOException {
        String suffix="\\.([0-9]{1,4})-slim";if(channel==Channel.TESTING)suffix="-([0-9]{8})-slim";
        Pattern pattern=Pattern.compile(Pattern.quote(key)+suffix);
        List<Version> versions=new ArrayList<>();
        for(int i=0;i<results.length();i++){
            JSONObject row=results.getJSONObject(i);String tag=row.getString("name");Matcher m=pattern.matcher(tag);
            if(!m.matches())continue;JSONArray images=row.optJSONArray("images");if(images==null)continue;
            for(int j=0;j<images.length();j++){
                JSONObject a=images.getJSONObject(j);String digest=a.optString("digest");
                if(!"linux".equals(a.optString("os")) || !"arm64".equals(a.optString("architecture")) || !digest.matches("sha256:[a-f0-9]{64}"))continue;
                String variant=a.optString("variant", "");if(!variant.isEmpty() && !variant.equals("v8"))continue;
                String label="Debian "+tag.substring(0,tag.length()-5);
                if(channel==Channel.TESTING){String d=m.group(1);label=key+" · "+d.substring(0,4)+"-"+d.substring(4,6)+"-"+d.substring(6);}
                versions.add(new Version(tag,label,digest,Integer.parseInt(m.group(1))));break;
            }
        }
        versions.sort((a,b)->Integer.compare(b.order,a.order));
        if(versions.isEmpty())throw new IOException("此分支暂未列出可用的 ARM64 slim 镜像");
        return versions.subList(0,Math.min(12,versions.size()));
    }
    static MessageDigest sha256(){try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException e){throw new AssertionError(e);}}
    static String hex(byte[] raw){StringBuilder s=new StringBuilder(raw.length*2);for(byte value:raw){int v=value&255;s.append(Character.forDigit(v>>>4,16));s.append(Character.forDigit(v&15,16));}return s.toString();}
    static Layer layer(byte[] raw,String expected) throws Exception {
        if(!expected.equals("sha256:"+hex(sha256().digest(raw))))throw new IOException("ARM64 镜像清单 SHA-256 校验失败");
        JSONObject manifest=json(raw);JSONArray layers=manifest.getJSONArray("layers");
        if(manifest.getInt("schemaVersion")!=2 || layers.length()!=1)throw new IOException("不支持此镜像结构，需要单层 rootfs");
        JSONObject info=layers.getJSONObject(0);String type=info.getString("mediaType"),digest=info.getString("digest");
        if((!type.equals("application/vnd.oci.image.layer.v1.tar+gzip") && !type.equals("application/vnd.docker.image.rootfs.diff.tar.gzip")) || !digest.startsWith("sha256:"))throw new IOException("不支持此镜像格式");
        return new Layer(digest.substring(7),info.getLong("size"));
    }
    static ImageStore.Image download(Version version,ImageStore store,ImageStore.Progress progress) throws Exception {
        String token=json(metadata(AUTH,"application/json",null)).getString("token");
        Layer layer=layer(metadata(REGISTRY+"manifests/"+version.manifest,MANIFEST,token),version.manifest);
        HttpsURLConnection c=get(REGISTRY+"blobs/sha256:"+layer.digest,"application/octet-stream",token);
        try(InputStream in=c.getInputStream()){return store.save(version.tag,layer,in,progress);}finally{c.disconnect();}
    }
}
