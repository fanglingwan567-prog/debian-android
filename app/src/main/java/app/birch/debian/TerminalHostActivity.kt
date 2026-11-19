package app.birch.debian

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.os.Build
import android.os.IBinder
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.webkit.*
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.doOnPreDraw
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

internal abstract class TerminalHostActivity : AppCompatActivity(), Console.Display {
    private val preferences by lazy { getSharedPreferences("terminal", MODE_PRIVATE) }
    private var terminalRoot: ComposeView? = null
    private var editor: WebView? = null
    private var console by mutableStateOf<Console?>(null)
    private var generation = 0
    private var failure by mutableStateOf<String?>(null)
    private var confirmingExit by mutableStateOf(false)
    private var service: TerminalService? = null
    private var connection: ServiceConnection? = null

    private fun stopSession(stop: Boolean = true) {
        generation++ // Drop pending callbacks before closing pipes or releasing the old view.
        console = null
        service?.detach()
        if (stop && connection != null) {
            service?.stopSession()
            stopService(Intent(this, TerminalService::class.java))
        }
        service = null
        connection?.let { unbindService(it) }
        connection = null
    }

    private fun reportFailure(id: Int, ex: IOException) = runOnUiThread {
        if (id != generation) return@runOnUiThread
        stopSession()
        confirmingExit = false
        failure = getString(R.string.terminal_start_failure, ex.reason())
    }

    protected fun terminal() {
        stopSession()
        terminalRoot?.disposeComposition()
        val id = generation
        val bridge = Bridge(id)
        failure = null
        confirmingExit = false
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isStatusBarContrastEnforced = false
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            isAppearanceLightStatusBars = false
            show(WindowInsetsCompat.Type.statusBars())
            hide(WindowInsetsCompat.Type.navigationBars())
        }
        val root = ComposeView(this)
        terminalRoot = root
        // A temporary window detach must not replace the editor while the PTY is awaiting its ack.
        // A retry disposes the previous composition above; final disposal follows the Activity.
        root.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        root.setBackgroundColor(TERMINAL_BACKGROUND)
        var showKeys by mutableStateOf(false)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.ime() or
                WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.captionBar())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            val visible = insets.isVisible(WindowInsetsCompat.Type.ime())
            val visibilityChanged = bridge.keyboardVisible != visible
            bridge.keyboardVisible = visible
            if (id == generation && bridge.pageReady && visibilityChanged) {
                editor?.evaluateJavascript("keyboardVisibilityChanged($visible)", null)
            }
            if (id == generation && showKeys && !visible && console != null) editor?.evaluateJavascript("clearShortcutModifiers()", null)
            showKeys = visible
            WindowInsetsCompat.CONSUMED
        }
        root.setContent {
            MaterialTheme(colorScheme = appColors()) {
                Column(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(TERMINAL_BACKGROUND))) {
                    val error = failure
                    if (error == null) {
                        AndroidView(
                            factory = { createEditor(id, bridge) },
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            onRelease = { view ->
                                if (editor === view) editor = null
                                view.removeJavascriptInterface("Birch")
                                (view.parent as? ViewGroup)?.removeView(view)
                                view.destroy()
                            }
                        )
                        if (showKeys && console != null) TerminalKeys(bridge.ctrl, bridge.alt) { label ->
                            if (id == generation && console != null) editor?.evaluateJavascript("pressShortcut(${JSONObject.quote(label)})", null)
                        }
                    }
                    when {
                        error != null -> TerminalDialog(error, getString(R.string.terminal_retry), "退出", ::terminal, ::finish)
                        confirmingExit -> TerminalDialog("关闭终端会话并退出？文件会保留。", "退出", "取消", ::finish) { confirmingExit = false }
                    }
                }
            }
        }
        setContentView(root)
        ViewCompat.requestApplyInsets(root)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createEditor(id: Int, bridge: Bridge): WebView {
        val view = object : WebView(this) {
            override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
                if (!hasWindowFocus) bridge.cancelKeyboardRequest()
                super.onWindowFocusChanged(hasWindowFocus)
            }
        }
        editor = view
        view.setBackgroundColor(TERMINAL_BACKGROUND)
        view.settings.apply {
            javaScriptEnabled = true
            allowContentAccess = false
            allowFileAccess = false
            blockNetworkLoads = true
            setSupportMultipleWindows(false)
        }
        view.isFocusableInTouchMode = true
        view.setOnLongClickListener { true } // Selection belongs to xterm, not Android's action mode.
        view.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                val source = message.sourceId().orEmpty()
                if (!bridge.pageReady && message.messageLevel() == ConsoleMessage.MessageLevel.ERROR &&
                    source.startsWith(ORIGIN) && source.endsWith(".js")) {
                    reportFailure(id, IOException("终端页面初始化失败：${message.message().take(240)}"))
                    return true
                }
                return super.onConsoleMessage(message)
            }
        }
        view.webViewClient = object : WebViewClient() {
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) reportFailure(id, IOException("终端页面加载失败：${error.description}"))
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                val url = request.url.toString()
                val mime = when (url) {
                    "${ORIGIN}index.html" -> "text/html"
                    "${ORIGIN}xterm.css" -> "text/css"
                    "${ORIGIN}screen.js", "${ORIGIN}xterm.js", "${ORIGIN}fit.js" -> "application/javascript"
                    else -> null
                }
                if (mime != null) {
                    try { return WebResourceResponse(mime, "UTF-8", assets.open("term/${url.removePrefix(ORIGIN)}")) }
                    catch (ex: IOException) { reportFailure(id, ex) }
                }
                return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(byteArrayOf()))
            }
        }
        view.addJavascriptInterface(bridge, "Birch")
        view.doOnPreDraw {
            if (id == generation && editor === view) view.loadUrl("${ORIGIN}index.html")
        }
        return view
    }

    private inner class Bridge(private val id: Int) {
        private val keyboardRequest = AtomicInteger()
        // Insets are set on main; JS reads visibility synchronously from WebView's bridge thread.
        @Volatile var keyboardVisible = false
        var ctrl by mutableStateOf(false)
        var alt by mutableStateOf(false)
        var pageReady = false
            private set

        private fun ui(action: (WebView) -> Unit) = runOnUiThread {
            if (id == generation) editor?.let(action)
        }

        @JavascriptInterface fun ready(cols: Int, rows: Int) = ui {
            if (pageReady) return@ui
            pageReady = true
            val intent = Intent(this@TerminalHostActivity, TerminalService::class.java)
            val binding = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    if (id != generation) return
                    val host = (binder as TerminalService.Connection).service
                    service = host
                    try {
                        console = host.attach(this@TerminalHostActivity, cols, rows)
                    } catch (ex: IOException) { reportFailure(id, ex) }
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    reportFailure(id, IOException("终端服务连接已断开"))
                }
            }
            try {
                startService(intent)
                if (bindService(intent, binding, BIND_AUTO_CREATE)) connection = binding
                else {
                    stopService(intent)
                    reportFailure(id, IOException("无法连接终端服务"))
                }
            } catch (ex: IllegalStateException) {
                stopService(intent)
                reportFailure(id, IOException("无法启动终端服务", ex))
            }
        }

        @JavascriptInterface fun cancelKeyboardRequest(): Int = keyboardRequest.incrementAndGet()

        @JavascriptInterface fun showKeyboard(request: Int) = ui { view ->
            if (request != keyboardRequest.get()) return@ui
            if (!view.hasWindowFocus() || keyboardVisible || (!view.hasFocus() && !view.requestFocus())) return@ui
            // Wait for the tap's focus change. A stale bridge must not target a replacement editor.
            view.post {
                if (request == keyboardRequest.get() && id == generation && editor === view && !keyboardVisible && view.isAttachedToWindow && view.hasWindowFocus() && view.hasFocus()) {
                    WindowCompat.getInsetsController(window, view).show(WindowInsetsCompat.Type.ime())
                }
            }
        }
        @JavascriptInterface fun isKeyboardVisible() = keyboardVisible
        @JavascriptInterface fun touchSlopDp() = ViewConfiguration.get(this@TerminalHostActivity).scaledTouchSlop / resources.displayMetrics.density
        @JavascriptInterface fun copyText(text: String?) = ui {
            if (text.isNullOrEmpty()) return@ui
            if (text.length > 131072) {
                Toast.makeText(this@TerminalHostActivity, "选区过长，请分段复制", Toast.LENGTH_SHORT).show()
                return@ui
            }
            try {
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("终端文本", text))
                if (Build.VERSION.SDK_INT < 33) Toast.makeText(this@TerminalHostActivity, "已复制", Toast.LENGTH_SHORT).show()
            } catch (_: RuntimeException) {
                Toast.makeText(this@TerminalHostActivity, "未能写入系统剪贴板", Toast.LENGTH_SHORT).show()
            }
        }
        @JavascriptInterface fun fontSize() = preferences.getInt("fontSize", 14)
        @JavascriptInterface fun saveFontSize(size: Int) { if (size in 8..36) ui { preferences.edit().putInt("fontSize", size).apply() } }
        @JavascriptInterface fun input(text: String?) = ui { if (text != null) console?.input(text) }
        @JavascriptInterface fun resize(cols: Int, rows: Int) = ui { console?.resize(cols, rows) }
        @JavascriptInterface fun modifiersChanged(ctrlOn: Boolean, altOn: Boolean) = ui { ctrl = ctrlOn; alt = altOn }
        @JavascriptInterface fun consumed() = ui { service?.consumed() }
    }

    override fun output(source: Console, base64: String) = runOnUiThread {
        if (source === console) editor?.evaluateJavascript("deliver('$base64')", null)
    }

    override fun ended(source: Console, reason: String?) = runOnUiThread {
        if (source !== console) return@runOnUiThread
        console = null
        if (reason == null) finish() else Toast.makeText(this, reason, Toast.LENGTH_LONG).show()
    }

    @Deprecated("Keep the terminal exit confirmation")
    override fun onBackPressed() {
        if (console != null) confirmingExit = true else super.onBackPressed()
    }

    override fun onDestroy() {
        stopSession(!isChangingConfigurations)
        super.onDestroy()
    }

    private companion object {
        const val ORIGIN = "https://terminal.invalid/"
        val TERMINAL_BACKGROUND = Color.rgb(21, 26, 23)
    }
}
