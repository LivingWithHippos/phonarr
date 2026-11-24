package com.github.livingwithhippos.phonarr.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.github.livingwithhippos.phonarr.core.data.BackupFile
import com.github.livingwithhippos.phonarr.core.data.SiteBrowser
import kotlinx.coroutines.launch
import java.time.LocalDate

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val settings by vm.settings.collectAsState()
    val indexers by vm.indexers.collectAsState()
    val scope = rememberCoroutineScope()
    var pendingRestore by remember { mutableStateOf<BackupFile?>(null) }

    val createBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let(vm::writeBackup)
    }
    val openBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { pendingRestore = vm.readBackup(uri) }
    }

    pendingRestore?.let { backup ->
        AlertDialog(
            onDismissRequest = { pendingRestore = null },
            title = { Text("Restore backup?") },
            text = {
                Text(
                    "Replace your ${indexers.size} indexers with the ${backup.indexers.size} from this backup? " +
                        "Your settings and API key are replaced too.",
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.restore(backup); pendingRestore = null }) { Text("Restore") }
            },
            dismissButton = { TextButton(onClick = { pendingRestore = null }) { Text("Cancel") } },
        )
    }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp).selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Open site button", style = MaterialTheme.typography.titleMedium)
                Text("Where the indexer screen opens a site.", style = MaterialTheme.typography.bodySmall)
                BrowserOption(
                    "In-app browser",
                    "Cookies from the site, such as a passed Cloudflare check, are reused by Phonarr's searches.",
                    settings.openSiteIn == SiteBrowser.InApp,
                ) { vm.setOpenSiteIn(SiteBrowser.InApp) }
                BrowserOption(
                    "Installed browser",
                    "Uses your browser and its saved logins. Phonarr can't reuse its cookies.",
                    settings.openSiteIn == SiteBrowser.External,
                ) { vm.setOpenSiteIn(SiteBrowser.External) }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Backup", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Saves your indexers and settings to a file. The file contains your site logins and API key " +
                        "in plain text: keep it somewhere private.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { createBackup.launch("phonarr-backup-${LocalDate.now()}.json") }) { Text("Create backup") }
                    // some file managers don't report .json files as application/json
                    OutlinedButton(onClick = { openBackup.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }) {
                        Text("Restore backup")
                    }
                }
            }
        }
    }
}

@Composable
private fun BrowserOption(title: String, description: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, onClick = onSelect, role = Role.RadioButton).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
    }
}
