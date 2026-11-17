package app.birch.debian;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Run in an empty test directory; live mode contacts the production public registry without root. */
public final class RegistrySmoke {
    static void require(boolean ok){if(!ok)throw new AssertionError();}
    interface Task {void run() throws Exception;}
    static void rejects(Task task) throws Exception {try{task.run();}catch(IOException expected){return;}throw new AssertionError("Expected rejection");}
    static String digest(byte[] b) throws Exception {
        byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest(b);
        return String.format(java.util.Locale.ROOT, "%064x", new java.math.BigInteger(1, hash));
    }
    static JSONObject row(String tag,String arch,String digest) throws Exception {
        return new JSONObject().put("name",tag).put("images",new JSONArray().put(new JSONObject().put("os","linux").put("architecture",arch).put("digest","sha256:"+digest)));
    }
    public static void main(String[] args) throws Exception {
        File dir=new File(args[0]);require(dir.isDirectory() && dir.list().length==0);ImageStore store=new ImageStore(dir);
        byte[] data="sample bytes".getBytes(StandardCharsets.US_ASCII);String hash=digest(data);
        JSONArray rows=new JSONArray().put(row("13.1-slim","arm64",hash)).put(row("13.10-slim","arm64",hash)).put(row("13.11-slim","amd64",hash)).put(row("13.20","arm64",hash));
        List<DebianRegistry.Version> versions=DebianRegistry.INSTANCE.versions(DebianRegistry.Channel.STABLE,"13",rows);
        require(versions.size()==2 && versions.get(0).getTag().equals("13.10-slim"));
        rejects(()->DebianRegistry.INSTANCE.versions(DebianRegistry.Channel.TESTING,"forky",rows));
        JSONArray snapshots = new JSONArray().put(row("forky-20261004-slim", "arm64", hash));
        DebianRegistry.Version snapshot = DebianRegistry.INSTANCE.versions(DebianRegistry.Channel.TESTING, "forky", snapshots).get(0);
        require(snapshot.getLabel().equals("forky · 2026-10-04"));
        JSONObject item=new JSONObject().put("size",data.length).put("digest","sha256:"+hash).put("mediaType","application/vnd.oci.image.layer.v1.tar+gzip");
        JSONArray layers=new JSONArray().put(item);JSONObject manifest=new JSONObject().put("schemaVersion",2).put("layers",layers);
        byte[] raw=manifest.toString().getBytes(StandardCharsets.UTF_8);DebianRegistry.Layer layer=DebianRegistry.INSTANCE.layer(raw,"sha256:"+digest(raw));
        rejects(()->DebianRegistry.INSTANCE.layer(raw,"sha256:"+hash));layers.put(item);byte[] multi=manifest.toString().getBytes(StandardCharsets.UTF_8);
        rejects(()->DebianRegistry.INSTANCE.layer(multi,"sha256:"+digest(multi)));
        long[] last={-1};ImageStore.Progress progress=(n,total)->{require(n>=last[0] && n<=total);last[0]=n;};
        require(store.pending()==null);ImageStore.Image image=store.save("13.1-slim",layer,new ByteArrayInputStream(data),progress);
        require(last[0]==data.length && new ImageStore(dir).pending().getTag().equals(image.getTag()));
        for(byte[] bad:new byte[][]{new byte[0],new byte[data.length],new byte[data.length+1]}){
            rejects(()->store.save("13.2-slim",layer,new ByteArrayInputStream(bad),(n,t)->{}));
            require(store.pending().getTag().equals(image.getTag()) && dir.list().length==1);
        }
        InputStream disconnected=new InputStream(){public int read() throws IOException {throw new IOException("simulated disconnect");}};
        rejects(()->store.save("13.2-slim",layer,disconnected,(n,t)->{}));
        require(store.pending().getTag().equals(image.getTag()));
        ByteArrayOutputStream pipe=new ByteArrayOutputStream();store.send(image,pipe);require(Arrays.equals(pipe.toByteArray(),(hash+" "+data.length+"\nsample bytes").getBytes(StandardCharsets.US_ASCII)));
        rejects(() -> store.send(new ImageStore.Image("13.2-slim", layer), new ByteArrayOutputStream()));
        require(store.pending().getTag().equals(image.getTag()));
        store.clear();require(store.pending()==null);
        if(args.length>1){
            for(DebianRegistry.Channel channel:DebianRegistry.Channel.values()){
                List<DebianRegistry.Version> live=DebianRegistry.INSTANCE.catalog(channel);System.out.println(channel+" versions="+live.size()+" first="+live.get(0).getTag());
                long[] previous={-1};ImageStore.Image pulled=DebianRegistry.INSTANCE.download(live.get(0),store,(n,t)->{require(n>=previous[0] && n<=t);previous[0]=n;});
                require(previous[0]==pulled.getLayer().getBytes() && store.pending().getTag().equals(pulled.getTag()));
                System.out.println("Verified "+pulled.getTag()+" "+pulled.getLayer().getBytes()+" bytes sha256:"+pulled.getLayer().getDigest());
                store.clear();
            }
        }
        System.out.println("Registry and download-store checks: PASS");
        // Some OEM TLS providers create a non-daemon metrics executor in standalone ART.
        System.exit(0);
    }
}
