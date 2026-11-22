package app.birch.debian

import java.io.IOException

internal sealed interface SetupState {
    sealed interface Page : SetupState
    sealed interface DownloadPage : Page

    data class Permission(val error: String? = null) : Page
    data object Checking : Page
    data class Download(
        val channel: DebianRegistry.Channel? = null,
        val versions: List<DebianRegistry.Version> = emptyList(),
        val selected: DebianRegistry.Version? = null,
        val error: String? = null
    ) : DownloadPage
    data class Catalog(val channel: DebianRegistry.Channel) : DownloadPage
    data class Transfer(val choice: Download, val received: Long = 0, val total: Long = 0) : DownloadPage
    data class Install(val image: ImageStore.Image, val choice: Download, val error: String? = null) : Page
    data class Installing(val detail: String) : Page
    data class Terminal(val cleanupError: String? = null) : SetupState
}

internal fun Exception.reason(): String = localizedMessage?.takeIf { it.isNotBlank() } ?: toString()

// On a fresh launch the helper and files, not an Android saved-state flag, are authoritative.
@Throws(IOException::class)
internal fun resolveStartup(reply: String, images: ImageStore): SetupState {
    if (reply.endsWith("BIRCH_READY")) return terminalAfterCleanup(images)
    if (!reply.endsWith("BIRCH_NEW")) throw IOException("无法识别启动器响应：$reply")
    return try {
        val saved = images.pending()
        if (saved == null) SetupState.Download()
        else SetupState.Install(saved, SetupState.Download())
    } catch (ex: IOException) {
        SetupState.Download(error = ex.reason())
    }
}

internal fun terminalAfterCleanup(images: ImageStore): SetupState.Terminal = try {
    images.clear()
    SetupState.Terminal()
} catch (ex: IOException) {
    SetupState.Terminal(ex.reason())
}
