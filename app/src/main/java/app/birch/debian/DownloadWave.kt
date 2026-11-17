package app.birch.debian

import android.animation.ValueAnimator
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/** Null is a waiting wave. A known transfer always ends at the actual byte fraction. */
@Composable
internal fun DownloadWave(fraction: Float?, modifier: Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.outlineVariant
    val path = remember { Path() }
    val moving = fraction == null || fraction > 0f && fraction < 1f
    val phase = if (moving && ValueAnimator.areAnimatorsEnabled()) {
        rememberInfiniteTransition(label = "download").animateFloat(0f, 1f,
            infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "wave phase")
    } else null
    Canvas(modifier) {
        val radius = 1.75.dp.toPx()
        val width = size.width - 2 * radius
        if (width <= 0f) return@Canvas
        val wavelength = 26.dp.toPx()
        val amplitude = if (fraction == null) 3.5.dp.toPx() * 0.55f else 3.5.dp.toPx()
        val filled = (fraction ?: 1f) * width
        val rtl = layoutDirection == LayoutDirection.Rtl
        withTransform({
            translate(radius, size.height / 2)
            if (rtl) { translate(width, 0f); scale(-1f, 1f, Offset.Zero) }
        }) {
            val start = if (filled == 0f) 0f else filled + 4 * radius
            if (start < width) drawLine(track, Offset(start, 0f), Offset(width, 0f), radius * 2, StrokeCap.Round)
            if (filled <= 0f) return@withTransform
            path.reset()
            path.moveTo(0f, 0f)
            val step = wavelength / 20
            val cycle = phase?.value ?: 0f
            var x = step
            while (x < filled) {
                val taper = minOf(1f, minOf(x, filled - x) / (wavelength * 0.4f))
                path.lineTo(x, amplitude * taper * sin(2 * PI * (x / wavelength - cycle)).toFloat())
                x += step
            }
            path.lineTo(filled, 0f)
            drawPath(path, primary, style = Stroke(2 * radius, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}
