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
import com.github.livingwithhippos.phonarr.ui.theme.Button
import androidx.compose.material3.OutlinedCard
import com.github.livingwithhippos.phonarr.ui.theme.OutlinedButton
import com.github.livingwithhippos.phonarr.ui.theme.TextButton
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.text.input.KeyboardType
import com.github.livingwithhippos.phonarr.core.data.DohProvider
import com.github.livingwithhippos.phonarr.core.net.SwitchableDns
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.ui.text.font.FontWeight
import com.github.livingwithhippos.phonarr.core.data.ThemeColor
import com.github.livingwithhippos.phonarr.core.data.ThemeMode
import com.github.livingwithhippos.phonarr.ui.theme.FilterChip
import com.github.livingwithhippos.phonarr.ui.theme.shouldUseDarkTheme
import com.github.livingwithhippos.phonarr.ui.theme.swatchColor
import androidx.compose.material3.Surface
import androidx.compose.ui.tooling.preview.PreviewLightDark
import com.github.livingwithhippos.phonarr.core.data.AppSettings
import com.github.livingwithhippos.phonarr.ui.theme.PhonarrTheme
import kotlinx.coroutines.launch
import java.time.LocalDate

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val settings by vm.settings.collectAsState()
    val indexers by vm.indexers.collectAsState()
    val testingDoh by vm.testingDoh.collectAsState()
    val scope = rememberCoroutineScope()
    var pendingRestore by remember { mutableStateOf<BackupFile?>(null) }

    val createBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let(vm::writeBackup)
    }
    val openBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { pendingRestore = vm.readBackup(uri) }
    }

    SettingsContent(
        settings = settings,
        indexerCount = indexers.size,
        testingDoh = testingDoh,
        pendingRestore = pendingRestore,
        actions = SettingsActions(
            onCreateBackup = { createBackup.launch("phonarr-backup-${LocalDate.now()}.json") },
            // some file managers don't report .json files as application/json
            onOpenBackup = { openBackup.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
            onConfirmRestore = { vm.restore(it); pendingRestore = null },
            onDismissRestore = { pendingRestore = null },
            onSetOpenSiteIn = vm::setOpenSiteIn,
            onSetThemeMode = vm::setThemeMode,
            onSetThemeColor = vm::setThemeColor,
            onSetStandardFont = vm::setStandardFont,
            onSetDohEnabled = vm::setDohEnabled,
            onSetDohProvider = vm::setDohProvider,
            onSetDohCustomUrl = vm::setDohCustomUrl,
            onTestDoh = vm::testDoh,
        ),
    )
}

/** What the settings screen can ask for; no-ops by default so previews can pass SettingsActions(). */
data class SettingsActions(
    val onCreateBackup: () -> Unit = {},
    val onOpenBackup: () -> Unit = {},
    val onConfirmRestore: (BackupFile) -> Unit = {},
    val onDismissRestore: () -> Unit = {},
    val onSetOpenSiteIn: (SiteBrowser) -> Unit = {},
    val onSetThemeMode: (ThemeMode) -> Unit = {},
    val onSetThemeColor: (ThemeColor) -> Unit = {},
    val onSetStandardFont: (Boolean) -> Unit = {},
    val onSetDohEnabled: (Boolean) -> Unit = {},
    val onSetDohProvider: (DohProvider) -> Unit = {},
    val onSetDohCustomUrl: (String) -> Unit = {},
    val onTestDoh: () -> Unit = {},
)

@Composable
internal fun SettingsContent(
    settings: AppSettings,
    indexerCount: Int,
    testingDoh: Boolean,
    pendingRestore: BackupFile?,
    actions: SettingsActions,
) {
    pendingRestore?.let { backup ->
        AlertDialog(
            onDismissRequest = actions.onDismissRestore,
            title = { Text("Restore backup?") },
            text = {
                Text(
                    "Replace your $indexerCount indexers with the ${backup.indexers.size} from this backup? " +
                        "Your settings and API key are replaced too.",
                )
            },
            confirmButton = {
                TextButton(onClick = { actions.onConfirmRestore(backup) }) { Text("Restore") }
            },
            dismissButton = { TextButton(onClick = actions.onDismissRestore) { Text("Cancel") } },
        )
    }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AppearanceCard(settings, actions)
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp).selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Open site button", style = MaterialTheme.typography.titleMedium)
                Text("Where the indexer screen opens a site.", style = MaterialTheme.typography.bodySmall)
                RadioOption(
                    "In-app browser",
                    "Cookies from the site, such as a passed Cloudflare check, are reused by Phonarr's searches.",
                    settings.openSiteIn == SiteBrowser.InApp,
                ) { actions.onSetOpenSiteIn(SiteBrowser.InApp) }
                RadioOption(
                    "Installed browser",
                    "Uses your browser and its saved logins. Phonarr can't reuse its cookies.",
                    settings.openSiteIn == SiteBrowser.External,
                ) { actions.onSetOpenSiteIn(SiteBrowser.External) }
            }
        }
        DnsCard(settings, testingDoh, actions)
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Backup", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Saves your indexers and settings to a file. The file contains your site logins and API key " +
                        "in plain text: keep it somewhere private.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = actions.onCreateBackup) { Text("Create backup") }
                    OutlinedButton(onClick = actions.onOpenBackup) { Text("Restore backup") }
                }
            }
        }
    }
}

/** Dark mode, theme color and font choices. */
@Composable
private fun AppearanceCard(settings: AppSettings, actions: SettingsActions) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Appearance", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

            Text("Dark mode", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ThemeMode.entries.forEach { mode ->
                    FilterChip(
                        selected = settings.themeMode == mode,
                        onClick = { actions.onSetThemeMode(mode) },
                        label = { Text(mode.label) },
                    )
                }
            }

            Text("Color", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            val dark = shouldUseDarkTheme(settings.themeMode)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ThemeColor.entries.forEach { color ->
                    val swatch = color.swatchColor(dark)
                    FilterChip(
                        selected = settings.themeColor == color,
                        onClick = { actions.onSetThemeColor(color) },
                        leadingIcon = swatch?.let {
                            {
                                Box(
                                    Modifier.size(12.dp).background(it)
                                        .border(1.dp, MaterialTheme.colorScheme.outline),
                                )
                            }
                        },
                        label = { Text(color.label) },
                    )
                }
            }

            Row(
                Modifier.fillMaxWidth()
                    .toggleable(value = settings.standardFont, onValueChange = actions.onSetStandardFont, role = Role.Switch),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Standard font", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Use the system font instead of monospace.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = settings.standardFont, onCheckedChange = null, modifier = Modifier.padding(start = 12.dp))
            }
        }
    }
}

private val ThemeMode.label: String
    get() = when (this) {
        ThemeMode.SYSTEM -> "System"
        ThemeMode.LIGHT -> "Light"
        ThemeMode.DARK -> "Dark"
        ThemeMode.AMOLED -> "AMOLED"
    }

private val ThemeColor.label: String
    get() = when (this) {
        ThemeColor.SYSTEM -> "Wallpaper"
        ThemeColor.PURPLE -> "Purple"
        ThemeColor.BLUE -> "Blue"
        ThemeColor.TEAL -> "Teal"
        ThemeColor.GREEN -> "Green"
        ThemeColor.ORANGE -> "Orange"
        ThemeColor.RED -> "Red"
    }

@Composable
private fun DnsCard(settings: AppSettings, testing: Boolean, actions: SettingsActions) {
    var customUrl by remember { mutableStateOf(settings.dohCustomUrl) }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                Modifier.fillMaxWidth()
                    .toggleable(value = settings.dohEnabled, onValueChange = actions.onSetDohEnabled, role = Role.Switch),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("DNS over HTTPS", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Looks up site addresses through an encrypted provider instead of your network's DNS. " +
                            "Helps with sites blocked by DNS. Doesn't apply to the in-app browser.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = settings.dohEnabled, onCheckedChange = null, modifier = Modifier.padding(start = 12.dp))
            }
            if (settings.dohEnabled) {
                Column(Modifier.selectableGroup()) {
                    DohProvider.entries.forEach { provider ->
                        RadioOption(
                            provider.label,
                            provider.url ?: "Your own DNS over HTTPS address",
                            settings.dohProvider == provider,
                        ) { actions.onSetDohProvider(provider) }
                    }
                }
                if (settings.dohProvider == DohProvider.Custom) {
                    val invalid = SwitchableDns.endpoint(DohProvider.Custom, customUrl) == null
                    OutlinedTextField(
                        value = customUrl,
                        onValueChange = { customUrl = it; actions.onSetDohCustomUrl(it) },
                        label = { Text("Address") },
                        placeholder = { Text("https://dns.example.com/dns-query") },
                        singleLine = true,
                        isError = invalid,
                        supportingText = if (invalid) {
                            { Text("Enter an https:// address") }
                        } else {
                            null
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                OutlinedButton(onClick = actions.onTestDoh, enabled = !testing) {
                    Text(if (testing) "Testing…" else "Test")
                }
            }
        }
    }
}

@Composable
private fun RadioOption(title: String, description: String, selected: Boolean, onSelect: () -> Unit) {
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

@PreviewLightDark
@Composable
private fun SettingsPreview() {
    val settings = AppSettings(
        apiKey = "0123456789abcdef0123456789abcdef",
        dohEnabled = true,
        dohProvider = DohProvider.Custom,
        dohCustomUrl = "https://dns.example.com/dns-query",
    )
    PhonarrTheme(settings) {
        Surface {
            SettingsContent(settings, indexerCount = 12, testingDoh = false, pendingRestore = null, actions = SettingsActions())
        }
    }
}
