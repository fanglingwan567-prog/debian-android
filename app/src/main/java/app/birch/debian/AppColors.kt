package app.birch.debian

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.google.android.material.R.attr
import com.google.android.material.color.MaterialColors

// The Activity applies the dynamic overlay. Read the same resolved roles as the system bars and wave.
@Composable
internal fun appColors(): ColorScheme {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    return remember(context, dark) {
        fun color(role: Int) = Color(MaterialColors.getColor(context, role, "Debian"))
        val base = if (dark) darkColorScheme() else lightColorScheme()
        base.copy(
            primary = color(attr.colorPrimary), onPrimary = color(attr.colorOnPrimary),
            secondaryContainer = color(attr.colorSecondaryContainer),
            surface = color(attr.colorSurface), onSurface = color(attr.colorOnSurface),
            onSurfaceVariant = color(attr.colorOnSurfaceVariant), surfaceTint = color(attr.colorPrimary),
            surfaceContainer = color(attr.colorSurfaceContainer), surfaceContainerHigh = color(attr.colorSurfaceContainerHigh),
            outline = color(attr.colorOutline), outlineVariant = color(attr.colorOutlineVariant), error = color(attr.colorError)
        )
    }
}
