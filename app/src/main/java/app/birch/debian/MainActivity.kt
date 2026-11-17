package app.birch.debian

import android.content.res.Configuration
import android.os.Bundle
import android.widget.Toast
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors

internal class MainActivity : TerminalHostActivity() {
    private lateinit var setup: SetupViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DynamicColors.applyToActivityIfAvailable(this)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        val bars = WindowCompat.getInsetsController(window, window.decorView)
        val light = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK != Configuration.UI_MODE_NIGHT_YES
        bars.show(WindowInsetsCompat.Type.systemBars())
        bars.isAppearanceLightStatusBars = light
        bars.isAppearanceLightNavigationBars = light
        val surface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface, "Debian")
        window.statusBarColor = surface
        window.navigationBarColor = surface

        setup = ViewModelProvider(this)[SetupViewModel::class.java]
        var page: MutableState<SetupState.Page>? = null
        setup.state.observe(this) { state ->
            when (state) {
                is SetupState.Page -> {
                    val current = page
                    if (current == null) {
                        val initial = mutableStateOf(state)
                        page = initial
                        setContentView(ComposeView(this).apply {
                            setContent { SetupScreen(initial.value, setup) }
                        })
                    } else {
                        current.value = state
                    }
                }
                is SetupState.Terminal -> {
                    // Replacing the content detaches the ComposeView and disposes its composition.
                    page = null
                    terminal()
                    if (state.cleanupError != null) {
                        Toast.makeText(this, "镜像暂未清理：${state.cleanupError}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    @Deprecated("Legacy terminal back handling is retained for compatibility")
    override fun onBackPressed() {
        if (setup.state.value is SetupState.Installing) {
            Toast.makeText(this, "安装进行中，请稍候", Toast.LENGTH_SHORT).show()
            return
        }
        super.onBackPressed()
    }
}
