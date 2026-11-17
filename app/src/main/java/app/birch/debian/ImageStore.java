package app.birch.debian;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;

/** One atomic file: the selected tag, size, digest and compressed bytes move together. */
final class ImageStore {
    private final File archive,part;
    private static final Object LOCK=new Object();
    ImageStore(File directory){archive=new File(directory,"debian.download");part=new File(directory,"debian.download.part");}
    interface Progress {void bytes(long received,long total);}
    static final class Image {
        final String tag;final DebianRegistry.Layer layer;
        Image(String tag,DebianRegistry.Layer layer) throws IOException {
            if(!tag.matches("([0-9]{1,3}\\.[0-9]{1,4}|[a-z]{2,32}-[0-9]{8})-slim"))throw new IOException("下载的版本信息无效");
            this.tag=tag;this.layer=layer;
        }
    }
    private Image header(DataInputStream in) throws IOException {
        return new Image(in.readUTF(),new DebianRegistry.Layer(in.readUTF(),in.readLong()));
    }
    Image pending() throws IOException {
        synchronized(LOCK){
            Files.deleteIfExists(part.toPath());
            if(!archive.isFile())return null;
            try(DataInputStream in=new DataInputStream(new BufferedInputStream(new FileInputStream(archive)))){
                Image image=header(in);
                // Tags and hex digests contain only ASCII; writeUTF prefixes each with two bytes.
                if(archive.length()==image.tag.length()+image.layer.digest.length()+12+image.layer.bytes)return image;
            }catch(EOFException | UTFDataFormatException ignored){/* Incomplete local metadata cannot be installed. */}
            throw new IOException("已下载的镜像不完整，请重新下载");
        }
    }
    Image save(String tag,DebianRegistry.Layer layer,InputStream in,Progress progress) throws IOException {
        Image image=new Image(tag,layer);
        synchronized(LOCK){
            try{
                try(FileOutputStream file=new FileOutputStream(part);DataOutputStream out=new DataOutputStream(new BufferedOutputStream(file))){
                    out.writeUTF(tag);out.writeUTF(layer.digest);out.writeLong(layer.bytes);
                    MessageDigest hash=DebianRegistry.sha256();byte[] buffer=new byte[65536];long written=0;long shown=-1;
                    progress.bytes(0,layer.bytes);int n;
                    while((n=in.read(buffer))!=-1){
                        if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("下载已中断");
                        written+=n;if(written>layer.bytes)throw new IOException("镜像超过清单声明的大小");
                        hash.update(buffer,0,n);out.write(buffer,0,n);long percent=written*100/layer.bytes;
                        if(percent!=shown && written<layer.bytes){progress.bytes(written,layer.bytes);shown=percent;}
                    }
                    if(written!=layer.bytes || !DebianRegistry.hex(hash.digest()).equals(layer.digest))throw new IOException("镜像长度或 SHA-256 校验失败，请重新下载");
                    out.flush();file.getFD().sync();
                }
                Files.move(part.toPath(),archive.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            }catch(IOException | RuntimeException ex){try{Files.deleteIfExists(part.toPath());}catch(IOException cleanup){ex.addSuppressed(cleanup);}throw ex;}
        }
        progress.bytes(layer.bytes,layer.bytes);return image;
    }
    void send(Image expected,OutputStream out) throws IOException {
        // A reader keeps the same inode if another activity replaces the saved download.
        try(DataInputStream in=new DataInputStream(new BufferedInputStream(new FileInputStream(archive)))){
            Image actual=header(in);
            if(!expected.tag.equals(actual.tag) || !expected.layer.digest.equals(actual.layer.digest) || expected.layer.bytes!=actual.layer.bytes)throw new IOException("已下载的镜像已改变，请重新选择");
            out.write((actual.layer.digest+" "+actual.layer.bytes+"\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);
        }
    }
    void clear() throws IOException {synchronized(LOCK){Files.deleteIfExists(archive.toPath());Files.deleteIfExists(part.toPath());}}
}
