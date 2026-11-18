package app.birch.debian

import android.content.Context
import android.net.ConnectivityManager
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal class RuntimeHost(context: Context) {
    private val app = context.applicationContext
    private val helper = File(app.applicationInfo.nativeLibraryDir, "libbirch.so")
    enum class Operation(val argument: String) { CHECK("check"), INSTALL("install"), SHELL("shell") }

    fun open(operation: Operation): Process {
        if (!helper.canExecute()) throw IOException("此版本需要 ARM64 设备及已解包的启动器")
        val files = app.filesDir.canonicalPath
        if (!files.matches(Regex("/[a-zA-Z0-9_./-]+")) || !helper.absolutePath.matches(Regex("/[A-Za-z0-9_./=+~@:-]+"))) {
            throw IOException("应用私有目录或安装路径无效")
        }
        val command = StringBuilder("exec '${helper.absolutePath}' ${operation.argument} ${android.os.Process.myUid()} '$files'")
        if (operation == Operation.SHELL) {
            command.append(' ').append(android.os.Process.myPid())
            val network = app.getSystemService(ConnectivityManager::class.java)
            val servers = network.getLinkProperties(network.activeNetwork)?.dnsServers.orEmpty()
            val literal = Regex("[a-fA-F0-9:.]+")
            var count = 0
            for (server in servers) {
                val ip = server.hostAddress ?: continue
                if (!literal.matches(ip)) continue
                command.append(' ').append(ip)
                if (++count == 4) break
            }
        }
        return ProcessBuilder("su", "-c", command.toString()).redirectErrorStream(true).start()
    }

    fun check(): String = run(Operation.CHECK, 90) {}

    fun install(store: ImageStore, image: ImageStore.Image, progress: (String) -> Unit) {
        run(Operation.INSTALL, 300) { out ->
            store.send(image, out)
            progress("正在校验镜像并展开文件系统…")
        }
    }

    private fun run(operation: Operation, timeoutSeconds: Long, send: (OutputStream) -> Unit): String {
        val child = open(operation)
        val io = Executors.newFixedThreadPool(2)
        try {
            val log = io.submit(Callable {
                child.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    val buffer = CharArray(1024)
                    val tail = StringBuilder()
                    while (true) {
                        val n = reader.read(buffer)
                        if (n < 0) break
                        tail.append(buffer, 0, n)
                        if (tail.length > 4096) tail.delete(0, tail.length - 4096)
                    }
                    tail.toString().trim()
                }
            })
            val copy = io.submit { child.outputStream.use(send) }
            if (!child.waitFor(timeoutSeconds, TimeUnit.SECONDS)) throw IOException("操作超时，请检查 root 管理器或剩余存储空间")
            val result = log.get(5, TimeUnit.SECONDS)
            if (child.exitValue() != 0) throw IOException(result.ifEmpty { "su 退出码：${child.exitValue()}" })
            copy.get(5, TimeUnit.SECONDS)
            return result
        } finally {
            child.destroy()
            io.shutdownNow()
        }
    }
}
