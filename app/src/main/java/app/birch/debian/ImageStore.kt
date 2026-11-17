package app.birch.debian

import java.io.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

internal class ImageStore(directory: File) {
    private val archive = File(directory, "debian.download")
    private val part = File(directory, "debian.download.part")
    private companion object {
        // Activities may briefly overlap. All instances share the same staging filename.
        val lock = Any()
        val tagPattern = Regex("""([0-9]{1,3}\.[0-9]{1,4}|[a-z]{2,32}-[0-9]{8})-slim""")
    }

    fun interface Progress { fun bytes(received: Long, total: Long) }
    data class Image(val tag: String, val layer: DebianRegistry.Layer) {
        init { if (!tagPattern.matches(tag)) throw IOException("下载的版本信息无效") }
    }

    private fun DataInputStream.header() = Image(readUTF(), DebianRegistry.Layer(readUTF(), readLong()))

    fun pending(): Image? = synchronized(lock) {
        Files.deleteIfExists(part.toPath())
        if (!archive.isFile) return null
        try {
            DataInputStream(archive.inputStream().buffered()).use { input ->
                val image = input.header()
                // Both writeUTF strings are ASCII; each has a two-byte length prefix.
                if (archive.length() == 12L + image.tag.length + image.layer.digest.length + image.layer.bytes) return image
            }
        } catch (ex: IOException) {
            if (ex !is EOFException && ex !is UTFDataFormatException) throw ex
        }
        throw IOException("已下载的镜像不完整，请重新下载")
    }

    fun save(tag: String, layer: DebianRegistry.Layer, input: InputStream, progress: Progress): Image {
        val image = Image(tag, layer)
        synchronized(lock) {
            try {
                part.outputStream().use { file ->
                    val out = DataOutputStream(file.buffered())
                    out.writeUTF(tag)
                    out.writeUTF(layer.digest)
                    out.writeLong(layer.bytes)
                    val hash = MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(65536)
                    var written = 0L
                    var shown = -1L
                    progress.bytes(0, layer.bytes)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("下载已中断")
                        written += n
                        if (written > layer.bytes) throw IOException("镜像超过清单声明的大小")
                        hash.update(buffer, 0, n)
                        out.write(buffer, 0, n)
                        val percent = written * 100 / layer.bytes
                        if (percent != shown && written < layer.bytes) {
                            progress.bytes(written, layer.bytes)
                            shown = percent
                        }
                    }
                    if (written != layer.bytes || hash.digest().hex() != layer.digest) throw IOException("镜像长度或 SHA-256 校验失败，请重新下载")
                    out.flush()
                    file.fd.sync()
                }
                Files.move(part.toPath(), archive.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (ex: Exception) {
                try { Files.deleteIfExists(part.toPath()) } catch (cleanup: IOException) { ex.addSuppressed(cleanup) }
                throw ex
            }
        }
        progress.bytes(layer.bytes, layer.bytes)
        return image
    }

    fun send(expected: Image, out: OutputStream) {
        // Keep reading this inode even if another activity replaces the archive.
        DataInputStream(archive.inputStream().buffered()).use { input ->
            val actual = input.header()
            if (actual != expected) throw IOException("已下载的镜像已改变，请重新选择")
            out.write("${actual.layer.digest} ${actual.layer.bytes}\n".toByteArray(Charsets.US_ASCII))
            input.copyTo(out, 65536)
        }
    }

    fun clear() {
        synchronized(lock) {
            Files.deleteIfExists(archive.toPath())
            Files.deleteIfExists(part.toPath())
        }
    }
}
