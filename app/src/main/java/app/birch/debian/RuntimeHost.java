package app.birch.debian;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.os.Process;
import java.io.*;
import java.net.InetAddress;
import java.util.concurrent.*;
import java.nio.charset.StandardCharsets;

final class RuntimeHost {
    private final Context app;
    private final String helper;
    RuntimeHost(Context context) {
        app=context.getApplicationContext();
        helper=new File(app.getApplicationInfo().nativeLibraryDir,"libbirch.so").getAbsolutePath();
    }
    java.lang.Process open(String op) throws IOException {
        if(!new File(helper).canExecute())throw new IOException("此版本需要 ARM64 设备及已解包的启动器");
        String files=app.getFilesDir().getCanonicalPath();
        if(!files.matches("/[a-zA-Z0-9_./-]+") || !helper.matches("/[A-Za-z0-9_./=+~@:-]+"))throw new IOException("应用私有目录或安装路径无效");
        StringBuilder cmd=new StringBuilder("exec '").append(helper).append("' ").append(op).append(' ').append(Process.myUid()).append(" '").append(files).append("'");
        if(op.equals("shell")){
            ConnectivityManager c=(ConnectivityManager)app.getSystemService(Context.CONNECTIVITY_SERVICE);
            LinkProperties l=c.getLinkProperties(c.getActiveNetwork());
            if(l!=null){int count=0;for(InetAddress a:l.getDnsServers()) {String ip=a.getHostAddress();if(ip!=null && ip.matches("[a-fA-F0-9:.]+")){cmd.append(' ').append(ip);if(++count==4)break;}}}
        }
        return new ProcessBuilder("su","-c",cmd.toString()).redirectErrorStream(true).start();
    }
    interface Progress { void stage(String label); }
    String check() throws Exception {return run("check",null,null,null);}
    void install(ImageStore store,ImageStore.Image image,Progress progress) throws Exception {run("install",store,image,progress);}
    private String run(String op, ImageStore store, ImageStore.Image image, Progress progress) throws Exception {
        java.lang.Process p=open(op); ExecutorService io=Executors.newFixedThreadPool(2); Future<String> log=io.submit(()->{
            byte[] buffer=new byte[1024];StringBuilder s=new StringBuilder();try(InputStream in=p.getInputStream()){int n;while((n=in.read(buffer))!=-1){s.append(new String(buffer,0,n,StandardCharsets.UTF_8));if(s.length()>4096)s.delete(0,s.length()-4096);}}return s.toString().trim();});
        try {
            Future<Void> copy=io.submit(()->{try(OutputStream out=p.getOutputStream()){if(image!=null){store.send(image,out);progress.stage("正在校验镜像并展开文件系统…");}}return null;});
            int limit=90;if(op.equals("install"))limit=300;
            if(!p.waitFor(limit,TimeUnit.SECONDS))throw new IOException("操作超时，请检查 root 管理器或剩余存储空间");
            String result=log.get(5,TimeUnit.SECONDS);if(p.exitValue()!=0){if(result.isEmpty())result="su 退出码："+p.exitValue();throw new IOException(result);}
            copy.get(5,TimeUnit.SECONDS);return result;
        }finally{p.destroy();io.shutdownNow();}
    }
}
