package app.birch.debian

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun SetupScreen(state: SetupState.Page, model: SetupViewModel) {
    MaterialTheme(colorScheme = appColors()) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column {
                Box(Modifier.fillMaxWidth().height(64.dp).padding(start = 20.dp), contentAlignment = Alignment.CenterStart) {
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
                }
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    Box(
                        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight)
                            .padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 28.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            Modifier.widthIn(max = 460.dp).fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(28.dp))
                                .padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 24.dp)
                        ) {
                            when (state) {
                                is SetupState.PermissionFailed, SetupState.Checking -> {
                                    PageHeading(R.string.permission_step, R.string.permission_title,
                                        stringResource(R.string.root_grant_message, stringResource(R.string.app_name)))
                                    if (state is SetupState.PermissionFailed) {
                                        Failure(stringResource(R.string.permission_failure, state.error))
                                        Action(R.string.permission_retry, onClick = model::checkPermission)
                                    } else {
                                        BusyIndicator()
                                    }
                                }
                                is SetupState.DownloadPage -> DownloadPage(state, model)
                                is SetupState.Install -> {
                                    PageHeading(R.string.install_step, R.string.install_title,
                                        stringResource(R.string.install_description, state.image.tag))
                                    state.error?.let { Failure(stringResource(R.string.install_failure, it)) }
                                    val label = if (state.error == null) R.string.install_action else R.string.install_retry
                                    Action(label, onClick = model::install)
                                    TextButton(onClick = model::reselect, modifier = Modifier.fillMaxWidth()) {
                                        Text(stringResource(R.string.download_reselect))
                                    }
                                }
                                is SetupState.Installing -> {
                                    PageHeading(R.string.install_step, R.string.install_title, state.detail)
                                    BusyIndicator()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PageHeading(step: Int, title: Int, description: String) {
    Text(stringResource(step), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    Text(stringResource(title), Modifier.padding(top = 8.dp).semantics { heading() }, style = MaterialTheme.typography.headlineMedium)
    Text(description, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyLarge)
}

@Composable
private fun Failure(message: String) {
    SelectionContainer {
        Text(message, Modifier.padding(top = 12.dp).semantics { liveRegion = LiveRegionMode.Polite },
            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun BusyIndicator() {
    LinearProgressIndicator(modifier = Modifier.padding(top = 24.dp).fillMaxWidth(), trackColor = MaterialTheme.colorScheme.secondaryContainer)
}

@Composable
private fun Action(label: Int, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) {
        Text(stringResource(label))
    }
}

@Composable
private fun DownloadPage(state: SetupState.DownloadPage, model: SetupViewModel) {
    PageHeading(R.string.download_step, R.string.download_title, stringResource(R.string.download_intro))
    val choice = when (state) {
        is SetupState.Catalog -> SetupState.Download(channel = state.channel)
        is SetupState.Transfer -> state.choice
        is SetupState.Download -> state
    }
    val editable = state is SetupState.Download
    val stable = stringResource(R.string.stable)
    val testing = stringResource(R.string.testing)
    Spacer(Modifier.height(8.dp))
    Selector(R.string.channel_label, R.string.channel_prompt, DebianRegistry.Channel.entries, choice.channel,
        editable, model::loadCatalog) { channel ->
        when (channel) {
            DebianRegistry.Channel.STABLE -> stable
            DebianRegistry.Channel.TESTING -> testing
        }
    }
    Selector(R.string.version_label, R.string.version_prompt, choice.versions, choice.selected,
        editable && choice.versions.isNotEmpty(), model::selectVersion) { it.label }

    when (state) {
        is SetupState.Catalog -> DownloadProgress(R.string.catalog_loading)
        is SetupState.Transfer -> {
            val label = if (state.total == 0L) R.string.download_connecting else R.string.download_active
            DownloadProgress(label, state.received, state.total)
        }
        is SetupState.Download -> state.error?.let { Failure(it) }
    }
    val channel = choice.channel
    if (editable && choice.error != null && choice.versions.isEmpty() && channel != null) {
        Action(R.string.catalog_retry) { model.loadCatalog(channel) }
    } else {
        val label = if (editable && choice.error != null && choice.selected != null) R.string.download_retry else R.string.download_action
        Action(label, editable && choice.selected != null, model::download)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T : Any> Selector(label: Int, prompt: Int, options: List<T>, selected: T?, enabled: Boolean,
    onSelect: (T) -> Unit, display: (T) -> String) {
    var expanded by remember(enabled) { mutableStateOf(false) }
    val fieldLabel = stringResource(label)
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(fieldLabel, Modifier.padding(end = 12.dp), style = MaterialTheme.typography.bodyLarge)
        ExposedDropdownMenuBox(expanded, { if (enabled) expanded = it }, Modifier.weight(1f)) {
            var anchor = Modifier.fillMaxWidth().semantics { contentDescription = fieldLabel }
            if (enabled) anchor = anchor.menuAnchor()
            OutlinedTextField(
                value = selected?.let(display).orEmpty(), onValueChange = {}, modifier = anchor,
                readOnly = true, enabled = enabled, singleLine = true,
                placeholder = { Text(stringResource(prompt), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }
            )
            ExposedDropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                for (item in options) {
                    DropdownMenuItem(text = { Text(display(item)) }, onClick = {
                        expanded = false
                        onSelect(item)
                    })
                }
            }
        }
    }
}

@Composable
private fun DownloadProgress(label: Int, received: Long = 0, total: Long = 0) {
    Column(Modifier.fillMaxWidth().padding(top = 20.dp)
        .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(16.dp)).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(label), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            if (total > 0) {
                Text(stringResource(R.string.download_percent, received * 100 / total), Modifier.padding(start = 8.dp),
                    color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"))
            }
        }
        val fraction = if (total == 0L) null else (received.toDouble() / total).toFloat()
        DownloadWave(fraction, Modifier.fillMaxWidth().padding(top = 4.dp).height(24.dp))
        if (total > 0) {
            Text(stringResource(R.string.download_amount, received / 1048576.0, total / 1048576.0),
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"))
        }
    }
}
