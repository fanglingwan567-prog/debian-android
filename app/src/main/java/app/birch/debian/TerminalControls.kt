package app.birch.debian

import android.view.ViewConfiguration
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val rows = listOf(listOf("ESC", "TAB", "HOME", "↑", "END", "PGUP"), listOf("CTRL", "ALT", "←", "↓", "→", "PGDN"))

@Composable
internal fun TerminalKeys(ctrl: Boolean, alt: Boolean, press: (String) -> Unit) {
    for (labels in rows) {
        Row(Modifier.fillMaxWidth().height(48.dp)) {
            for (label in labels) {
                val active = label == "CTRL" && ctrl || label == "ALT" && alt
                val description = when (label) {
                    "↑" -> "向上"
                    "↓" -> "向下"
                    "←" -> "向左"
                    "→" -> "向右"
                    else -> label
                }
                val interaction = remember { MutableInteractionSource() }
                var keyModifier = Modifier.weight(1f).fillMaxHeight()
                if (label in listOf("↑", "↓", "←", "→")) {
                    keyModifier = keyModifier.repeatOnPress(interaction) { press(label) }
                }
                TextButton(
                    onClick = { press(label) },
                    interactionSource = interaction,
                    modifier = keyModifier.semantics {
                        contentDescription = description
                        if (label == "CTRL" || label == "ALT") selected = active
                    },
                    contentPadding = PaddingValues(0.dp),
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = if (active) Color(0xffa0d8af) else Color(0xffe1e9e2),
                        containerColor = if (active) Color(0xff2b4033) else Color.Transparent
                    )
                ) { Text(label, fontSize = 13.sp, maxLines = 1) }
            }
        }
    }
}

@Composable
private fun Modifier.repeatOnPress(interaction: MutableInteractionSource, press: () -> Unit): Modifier {
    val currentPress by rememberUpdatedState(press)
    val focused = LocalWindowInfo.current.isWindowFocused
    return pointerInput(interaction, focused) {
        if (!focused) return@pointerInput
        coroutineScope {
            awaitEachGesture {
                // Own the touch before TextButton's click handler; onClick remains
                // available for accessibility and hardware keyboard activation.
                val down = awaitFirstDown(pass = PointerEventPass.Initial)
                down.consume()
                val held = PressInteraction.Press(down.position)
                interaction.tryEmit(held)
                val repeating = launch {
                    delay(ViewConfiguration.getKeyRepeatTimeout().toLong())
                    while (true) {
                        currentPress()
                        delay(ViewConfiguration.getKeyRepeatDelay().toLong())
                    }
                }
                var released = false
                try {
                    currentPress()
                    val up = waitForUpOrCancellation(pass = PointerEventPass.Initial)
                    if (up != null) {
                        up.consume()
                        released = true
                    }
                } finally {
                    repeating.cancel()
                    if (released) interaction.tryEmit(PressInteraction.Release(held))
                    else interaction.tryEmit(PressInteraction.Cancel(held))
                }
            }
        }
    }
}

@Composable
internal fun TerminalDialog(message: String, accept: String, cancel: String, onAccept: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(onDismissRequest = onCancel, text = { Text(message) },
        confirmButton = { TextButton(onClick = onAccept) { Text(accept) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(cancel) } })
}
