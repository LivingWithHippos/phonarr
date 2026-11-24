package com.github.livingwithhippos.phonarr.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
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
    var port by remember(settings.port) { mutableStateOf(settings.port.toString()) }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Torznab server", style = MaterialTheme.typography.titleMedium)
                Text(if (running) "Running on 127.0.0.1:${settings.port}" else "Stopped")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (running) OutlinedButton(onClick = vm::stopServer) { Text("Stop") }
                    else Button(onClick = vm::startServer) { Text("Start") }
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it.filter(Char::isDigit).take(5) },
                        label = { Text("Port") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        enabled = port.toIntOrNull()?.let { it in 1024..65535 && it != settings.port } == true,
                        onClick = { vm.setPort(port.toInt()) },
                    ) { Text("Set") }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Connect your apps", style = MaterialTheme.typography.titleMedium)
                Text("Add a Torznab indexer in your app with this URL and API key. Use \"all\" to search every enabled indexer, or an indexer number from the Indexers tab.")
                val url = "http://127.0.0.1:${settings.port}"
                Text(url, fontFamily = FontFamily.Monospace)
                TextButton(onClick = { clipboard.setText(AnnotatedString(url)) }) { Text("Copy URL") }
                Text("API key", style = MaterialTheme.typography.labelLarge)
                Text(settings.apiKey, fontFamily = FontFamily.Monospace)
                Row {
                    TextButton(onClick = { clipboard.setText(AnnotatedString(settings.apiKey)) }) { Text("Copy key") }
                    TextButton(onClick = vm::regenerateApiKey) { Text("New key") }
                }
                Text("${indexers.count { it.enabled }} enabled indexers", style = MaterialTheme.typography.bodySmall)
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Indexer definitions", style = MaterialTheme.typography.titleMedium)
                val last = settings.lastDefinitionsUpdate
                Text(
                    "${defs.size} sites available" +
                        if (last > 0) ", updated ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(last))}" else "",
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = vm::updateDefinitions, enabled = !updating) { Text("Update definitions") }
                    if (updating) CircularProgressIndicator(Modifier.padding(4.dp))
                }
                Text(
                    "Definitions come from the Prowlarr indexers project. Updating takes several seconds.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
