package app.birch.debian

import android.util.Base64
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Owns the process and both pipes. WebView acknowledges each output chunk before the next read. */
internal class Console(private val process: Process, private val display: Display) : AutoCloseable {
    interface Display {
        fun output(source: Console, base64: String)
        fun ended(source: Console, reason: String?)
    }

    private val writer = ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, ArrayBlockingQueue(64))
    private val ack = Semaphore(0)
    private val closed = AtomicBoolean(false)

    fun start() {
        thread(name = "birch-pty-read") {
            var message: String? = null
            try {
                process.inputStream.use { input ->
                    val buffer = ByteArray(8192)
                    while (!closed.get()) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        display.output(this, Base64.encodeToString(buffer, 0, n, Base64.NO_WRAP))
                        ack.acquire()
                    }
                }
                if (!closed.get()) {
                    val code = process.waitFor()
                    if (code != 0) message = "启动器退出码：$code，输出已保留。"
                }
            } catch (ex: Exception) {
                message = ex.toString()
            } finally {
                close()
                display.ended(this, message)
            }
        }
    }

    fun consumed() { if (!closed.get()) ack.release() }

    fun input(text: String) {
        if (text.length <= 65536) write('I', text.toByteArray(Charsets.UTF_8))
    }

    fun resize(cols: Int, rows: Int) {
        if (cols !in 1..2048 || rows !in 1..2048) return
        write('W', byteArrayOf((cols shr 8).toByte(), cols.toByte(), (rows shr 8).toByte(), rows.toByte()))
    }

    private fun write(op: Char, data: ByteArray) {
        if (closed.get()) return
        try {
            writer.execute {
                try {
                    val out = process.outputStream
                    var pos = 0
                    while (pos < data.size) {
                        val n = minOf(8192, data.size - pos)
                        val frame = ByteArray(n + 3)
                        frame[0] = op.code.toByte()
                        frame[1] = (n shr 8).toByte()
                        frame[2] = n.toByte()
                        data.copyInto(frame, 3, pos, pos + n)
                        out.write(frame)
                        pos += n
                    }
                    out.flush()
                } catch (ex: IOException) {
                    close()
                }
            }
        } catch (ex: RejectedExecutionException) {
            close()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        ack.release()
        writer.shutdownNow()
        process.destroy()
        try { process.outputStream.close() } catch (_: IOException) { /* Child already exited. */ }
    }
}
