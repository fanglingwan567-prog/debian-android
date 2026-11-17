package app.birch.debian

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
                TextButton(
                    onClick = { press(label) },
                    modifier = Modifier.weight(1f).fillMaxHeight().semantics {
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
internal fun TerminalDialog(message: String, accept: String, cancel: String, onAccept: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(onDismissRequest = onCancel, text = { Text(message) },
        confirmButton = { TextButton(onClick = onAccept) { Text(accept) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(cancel) } })
}
