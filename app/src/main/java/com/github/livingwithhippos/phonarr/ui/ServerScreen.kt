package com.github.livingwithhippos.phonarr.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.github.livingwithhippos.phonarr.core.data.AppSettings
import com.github.livingwithhippos.phonarr.core.util.lanAddresses
import com.github.livingwithhippos.phonarr.ui.theme.Button
import com.github.livingwithhippos.phonarr.ui.theme.OutlinedButton
import com.github.livingwithhippos.phonarr.ui.theme.PhonarrTheme
import com.github.livingwithhippos.phonarr.ui.theme.TextButton
import java.text.DateFormat
import java.util.Date

@Composable
fun ServerScreen(vm: MainViewModel) {
    val settings by vm.settings.collectAsState()
    val running by vm.serverRunning.collectAsState()
    val error by vm.serverError.collectAsState()
    val defs by vm.definitions.collectAsState()
    val updating by vm.updatingDefinitions.collectAsState()
    val indexers by vm.indexers.collectAsState()
    val clipboard = LocalClipboardManager.current
    // a real network lookup, so it stays out of the stateless content
    val addresses = remember(settings.listenOnNetwork, running) {
        if (settings.listenOnNetwork) lanAddresses() else emptyList()
    }
    ServerContent(
        settings = settings,
        running = running,
        error = error,
        definitionsCount = defs.size,
        updatingDefinitions = updating,
        enabledIndexers = indexers.count { it.enabled },
        lanAddresses = addresses,
        actions = ServerActions(
            onStart = vm::startServer,
            onStop = vm::stopServer,
            onSetPort = vm::setPort,
            onSetListenOnNetwork = vm::setListenOnNetwork,
            onRegenerateApiKey = vm::regenerateApiKey,
            onUpdateDefinitions = vm::updateDefinitions,
            onCopy = { clipboard.setText(AnnotatedString(it)) },
        ),
    )
}

/** What the server screen can ask for; no-ops by default so previews can pass ServerActions(). */
data class ServerActions(
    val onStart: () -> Unit = {},
    val onStop: () -> Unit = {},
    val onSetPort: (Int) -> Unit = {},
    val onSetListenOnNetwork: (Boolean) -> Unit = {},
    val onRegenerateApiKey: () -> Unit = {},
    val onUpdateDefinitions: () -> Unit = {},
    val onCopy: (String) -> Unit = {},
)

@Composable
internal fun ServerContent(
    settings: AppSettings,
    running: Boolean,
    error: String?,
    definitionsCount: Int,
    updatingDefinitions: Boolean,
    enabledIndexers: Int,
    lanAddresses: List<String>,
    actions: ServerActions,
) {
    var port by remember(settings.port) { mutableStateOf(settings.port.toString()) }
    var confirmNewKey by remember { mutableStateOf(false) }

    if (confirmNewKey) {
        AlertDialog(
            onDismissRequest = { confirmNewKey = false },
            title = { Text("Create a new API key?") },
            text = { Text("Apps using the current key will stop working until you give them the new one.") },
            confirmButton = {
                TextButton(onClick = {
                    actions.onRegenerateApiKey(); confirmNewKey = false
                }) { Text("New key") }
            },
            dismissButton = { TextButton(onClick = { confirmNewKey = false }) { Text("Cancel") } },
        )
    }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Torznab server", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.weight(1f))
                    StatusChip(running)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it.filter(Char::isDigit).take(5) },
                        label = { Text("Port") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        enabled = port.toIntOrNull()
                            ?.let { it in 1024..65535 && it != settings.port } == true,
                        onClick = { actions.onSetPort(port.toInt()) },
                        // the field reserves 8dp above its box for the floating label: offset to center on the box
                        modifier = Modifier.padding(top = 8.dp),
                    ) { Text("Set") }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = settings.listenOnNetwork,
                            onValueChange = actions.onSetListenOnNetwork,
                            role = Role.Switch,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Allow other devices", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Listens on all network interfaces (0.0.0.0). Anyone on your network can reach the server: " +
                                    "the API key is the only protection and traffic isn't encrypted.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = settings.listenOnNetwork,
                        onCheckedChange = null,
                        modifier = Modifier.padding(start = 12.dp)
                    )
                }
                if (running) {
                    OutlinedButton(
                        onClick = actions.onStop,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Stop") }
                } else {
                    Button(
                        onClick = actions.onStart,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Start") }
                }
            }
        }

        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Connect your apps", style = MaterialTheme.typography.titleMedium)
                Text("Add a Torznab indexer in your app with this URL and API key. Use \"all\" to search every enabled indexer, or an indexer number from the Indexers tab.")
                Text("API key", style = MaterialTheme.typography.labelLarge)
                Text(settings.apiKey, fontFamily = FontFamily.Monospace)
                Row {
                    TextButton(onClick = { actions.onCopy(settings.apiKey) }) { Text("Copy key") }
                    TextButton(onClick = { confirmNewKey = true }) { Text("New key") }
                }
                if (settings.listenOnNetwork) Text(
                    "On this phone",
                    style = MaterialTheme.typography.labelLarge
                )
                CopyableUrl("http://127.0.0.1:${settings.port}", actions.onCopy)
                if (settings.listenOnNetwork) {
                    Text("From other devices", style = MaterialTheme.typography.labelLarge)
                    if (lanAddresses.isEmpty()) {
                        Text(
                            "Not connected to a local network",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    lanAddresses.forEach { ip ->
                        CopyableUrl("http://$ip:${settings.port}", actions.onCopy)
                    }
                }
                Text(
                    "$enabledIndexers enabled indexers",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Indexer definitions", style = MaterialTheme.typography.titleMedium)
                val last = settings.lastDefinitionsUpdate
                Text(
                    "$definitionsCount sites available" +
                            if (last > 0) ", updated ${
                                DateFormat.getDateTimeInstance(
                                    DateFormat.SHORT,
                                    DateFormat.SHORT
                                ).format(Date(last))
                            }" else "",
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = actions.onUpdateDefinitions,
                        enabled = !updatingDefinitions
                    ) { Text("Update definitions") }
                    if (updatingDefinitions) CircularProgressIndicator(Modifier.padding(4.dp))
                }
                Text(
                    "Definitions come from the Prowlarr indexers project. Updating takes several seconds.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun CopyableUrl(url: String, onCopy: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(url, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
        TextButton(onClick = { onCopy(url) }) { Text("Copy") }
    }
}

/** Shows whether the server is running. Display only, so not a clickable chip. */
@Composable
private fun StatusChip(running: Boolean) {
    val description = if (running) "Server running" else "Server stopped"
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (running) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (running) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = description },
    ) {
        Row(
            Modifier
                .heightIn(min = 32.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (running) Box(Modifier
                .size(8.dp)
                .background(MaterialTheme.colorScheme.primary))
            Text(if (running) "Running" else "Stopped", style = MaterialTheme.typography.labelLarge)
        }
    }
}

private const val PREVIEW_KEY = "0123456789abcdef0123456789abcdef"

@PreviewLightDark
@Composable
private fun ServerStoppedPreview() {
    PhonarrTheme(AppSettings(PREVIEW_KEY)) {
        Surface {
            ServerContent(
                settings = AppSettings(PREVIEW_KEY),
                running = false,
                error = null,
                definitionsCount = 540,
                updatingDefinitions = false,
                enabledIndexers = 0,
                lanAddresses = emptyList(),
                actions = ServerActions(),
            )
        }
    }
}

@PreviewLightDark
@Composable
private fun ServerRunningPreview() {
    val settings =
        AppSettings(PREVIEW_KEY, listenOnNetwork = true, lastDefinitionsUpdate = 1_764_000_000_000)
    PhonarrTheme(AppSettings(PREVIEW_KEY)) {
        Surface {
            ServerContent(
                settings = settings,
                running = true,
                error = null,
                definitionsCount = 540,
                updatingDefinitions = false,
                enabledIndexers = 12,
                lanAddresses = listOf("192.168.1.23"),
                actions = ServerActions(),
            )
        }
    }
}
