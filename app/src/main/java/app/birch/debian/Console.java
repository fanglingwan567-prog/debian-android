package app.birch.debian;

import android.util.Base64;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

/** Owns a single process, a bounded outbound queue and at most one unacknowledged display chunk. */
final class Console implements AutoCloseable {
    interface Display { void output(Console c,String base64); void ended(Console c,String reason); }
    private final Display display;
    private final java.lang.Process process;
    private final ThreadPoolExecutor writer=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(64));
    private final Semaphore ack=new Semaphore(0);
    private volatile boolean closed;
    Console(RuntimeHost host,Display display) throws IOException {this.display=display;process=host.open("shell");}
    void start(){new Thread(()->{String msg=null;
        try(InputStream in=process.getInputStream()) {byte[] b=new byte[8192];int n;while(!closed && (n=in.read(b))!=-1){display.output(this,Base64.encodeToString(b,0,n,Base64.NO_WRAP));ack.acquire();}if(!closed){int code=process.waitFor();if(code!=0)msg="启动器退出码："+code+"，输出已保留。";}}
        catch(Exception ex){msg=ex.toString();}finally{close();display.ended(this,msg);}
    },"birch-pty-read").start();}
    void consumed(){if(!closed)ack.release();}
    void input(String s){if(s.length()>65536)return;write('I',s.getBytes(StandardCharsets.UTF_8));}
    void resize(int cols,int rows){if(cols<=0||rows<=0||cols>2048||rows>2048)return;write('W',new byte[]{(byte)(cols>>8),(byte)cols,(byte)(rows>>8),(byte)rows});}
    private void write(int op,byte[] data){if(closed)return;try{writer.execute(()->{try{OutputStream out=process.getOutputStream();for(int pos=0;pos<data.length;){int n=Math.min(8192,data.length-pos);byte[] frame=new byte[n+3];frame[0]=(byte)op;frame[1]=(byte)(n>>8);frame[2]=(byte)n;System.arraycopy(data,pos,frame,3,n);out.write(frame);pos+=n;}out.flush();}catch(IOException e){close();}});}catch(RejectedExecutionException e){close();}}
    public synchronized void close(){if(closed)return;closed=true;ack.release();writer.shutdownNow();try{process.getOutputStream().close();}catch(IOException ignored){}process.destroy();}
}
