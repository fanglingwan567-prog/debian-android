package app.birch.debian

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

/** Public registry traffic stays in the unprivileged app process. */
internal object DebianRegistry {
    enum class Channel(val suite: String) { STABLE("stable"), TESTING("testing") }
    class Version(val tag: String, val label: String, val manifest: String, val order: Int)
    data class Layer(val digest: String, val bytes: Long) {
        init {
            if (!digest.matches(hexDigest) || bytes !in 1..256L * 1024 * 1024) throw IOException("镜像摘要或大小不受支持")
        }
    }

    private val hexDigest = Regex("[a-f0-9]{64}")
    private val manifestDigest = Regex("sha256:[a-f0-9]{64}")
    private const val HUB = "https://hub.docker.com/v2/namespaces/library/repositories/debian/tags"
    private const val REGISTRY = "https://registry-1.docker.io/v2/library/debian/"
    private const val AUTH = "https://auth.docker.io/token?service=registry.docker.io&scope=repository:library/debian:pull"
    private const val MANIFEST = "application/vnd.oci.image.manifest.v1+json, application/vnd.docker.distribution.manifest.v2+json"

    private fun checkInterrupted() {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("操作已中断")
    }

    private fun <T> get(address: String, accept: String, token: String?, read: (InputStream) -> T): T {
        var url = URL(address)
        var bearer = token
        repeat(6) {
            checkInterrupted()
            if (url.protocol != "https" || url.userInfo != null) throw IOException("拒绝不安全的镜像地址")
            val connection = url.openConnection() as HttpsURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.connectTimeout = 15000
                connection.readTimeout = 25000
                connection.setRequestProperty("Accept", accept)
                connection.setRequestProperty("Accept-Encoding", "identity")
                if (bearer != null) connection.setRequestProperty("Authorization", "Bearer $bearer")
                when (val code = connection.responseCode) {
                    200 -> return connection.inputStream.use(read)
                    301, 302, 303, 307, 308 -> {
                        val location = connection.getHeaderField("Location") ?: throw IOException("镜像服务返回空跳转")
                        val next = URL(url, location)
                        if (!next.host.equals(url.host, ignoreCase = true) || next.port != url.port) bearer = null
                        url = next
                    }
                    429 -> throw IOException("镜像服务请求过于频繁，请稍后重试（HTTP 429）")
                    else -> throw IOException("${url.host} 返回 HTTP $code")
                }
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("镜像服务跳转次数过多")
    }

    private fun metadata(address: String, accept: String = "application/json", token: String? = null) = get(address, accept, token) { input ->
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            checkInterrupted()
            if (out.size() + n > 2 * 1024 * 1024) throw IOException("镜像目录过大")
            out.write(buffer, 0, n)
        }
        out.toByteArray()
    }

    fun catalog(channel: Channel): List<Version> {
        val key = get("https://deb.debian.org/debian/dists/${channel.suite}/Release", "text/plain", null) { input ->
            // Only read the release header, not the checksum table.
            val head = ByteArray(2048)
            var used = 0
            while (used < head.size) {
                val n = input.read(head, used, head.size - used)
                if (n < 0) break
                used += n
            }
            val text = String(head, 0, used, Charsets.US_ASCII)
            if (!text.contains("Suite: ${channel.suite}\n")) throw IOException("Debian 分支资料不匹配")
            val pattern = when (channel) {
                Channel.STABLE -> Regex("""(?m)^Version: ([0-9]{1,3})(?:\.[0-9]+)*$""")
                Channel.TESTING -> Regex("""(?m)^Codename: ([a-z]{2,32})$""")
            }
            pattern.find(text)?.groupValues?.get(1) ?: throw IOException("无法识别 Debian 分支版本")
        }
        val prefix = if (channel == Channel.STABLE) "$key." else "$key-"
        val page = JSONObject(metadata("$HUB?page_size=60&name=$prefix").toString(Charsets.UTF_8))
        return versions(channel, key, page.getJSONArray("results"))
    }

    fun versions(channel: Channel, key: String, results: JSONArray): List<Version> {
        val suffix = if (channel == Channel.STABLE) """\.([0-9]{1,4})-slim""" else "-([0-9]{8})-slim"
        val pattern = Regex(Regex.escape(key) + suffix)
        val matches = mutableListOf<Version>()
        for (i in 0 until results.length()) {
            val row = results.getJSONObject(i)
            val tag = row.getString("name")
            val number = pattern.matchEntire(tag)?.groupValues?.get(1) ?: continue
            val images = row.optJSONArray("images") ?: continue
            for (j in 0 until images.length()) {
                val image = images.getJSONObject(j)
                if (image.optString("os") != "linux" || image.optString("architecture") != "arm64") continue
                val variant = image.optString("variant", "")
                if (variant.isNotEmpty() && variant != "v8") continue
                val digest = image.optString("digest")
                if (!manifestDigest.matches(digest)) continue
                val label = when (channel) {
                    Channel.STABLE -> "Debian ${tag.removeSuffix("-slim")}"
                    Channel.TESTING -> "$key · ${number.take(4)}-${number.substring(4, 6)}-${number.takeLast(2)}"
                }
                matches.add(Version(tag, label, digest, number.toInt()))
                break
            }
        }
        if (matches.isEmpty()) throw IOException("此分支暂未列出可用的 ARM64 slim 镜像")
        return matches.sortedByDescending { it.order }.take(12)
    }

    fun layer(raw: ByteArray, expected: String): Layer {
        if (expected != "sha256:${MessageDigest.getInstance("SHA-256").digest(raw).hex()}") throw IOException("ARM64 镜像清单 SHA-256 校验失败")
        val manifest = JSONObject(raw.toString(Charsets.UTF_8))
        val layers = manifest.getJSONArray("layers")
        if (manifest.getInt("schemaVersion") != 2 || layers.length() != 1) throw IOException("不支持此镜像结构，需要单层 rootfs")
        val info = layers.getJSONObject(0)
        val type = info.getString("mediaType")
        val digest = info.getString("digest")
        if ((type != "application/vnd.oci.image.layer.v1.tar+gzip" && type != "application/vnd.docker.image.rootfs.diff.tar.gzip") || !digest.startsWith("sha256:")) {
            throw IOException("不支持此镜像格式")
        }
        return Layer(digest.substring(7), info.getLong("size"))
    }

    fun download(version: Version, store: ImageStore, progress: ImageStore.Progress): ImageStore.Image {
        val token = JSONObject(metadata(AUTH).toString(Charsets.UTF_8)).getString("token")
        val layer = layer(metadata("${REGISTRY}manifests/${version.manifest}", MANIFEST, token), version.manifest)
        return get("${REGISTRY}blobs/sha256:${layer.digest}", "application/octet-stream", token) { store.save(version.tag, layer, it, progress) }
    }
}

internal fun ByteArray.hex() = buildString(size * 2) {
    for (byte in this@hex) {
        val n = byte.toInt() and 255
        append("0123456789abcdef"[n ushr 4])
        append("0123456789abcdef"[n and 15])
    }
}
