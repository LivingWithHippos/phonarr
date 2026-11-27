package com.github.livingwithhippos.phonarr.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.core.text.HtmlCompat
import com.github.livingwithhippos.phonarr.browser.ChallengeActivity
import com.github.livingwithhippos.phonarr.core.cardigann.CardigannDefinition
import com.github.livingwithhippos.phonarr.core.cardigann.CardigannIndexer
import com.github.livingwithhippos.phonarr.core.data.IndexerConfig
import com.github.livingwithhippos.phonarr.core.data.SiteBrowser
import com.github.livingwithhippos.phonarr.ui.theme.Button
import com.github.livingwithhippos.phonarr.ui.theme.FilterChip
import com.github.livingwithhippos.phonarr.ui.theme.OutlinedButton
import com.github.livingwithhippos.phonarr.ui.theme.TextButton

@Composable
fun IndexerListScreen(vm: MainViewModel, onAdd: () -> Unit, onEdit: (IndexerConfig) -> Unit) {
    val indexers by vm.indexers.collectAsState()
    val testing by vm.testing.collectAsState()
    val challenges by vm.challenges.collectAsState()
    val settings by vm.settings.collectAsState()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var toDelete by remember { mutableStateOf<IndexerConfig?>(null) }

    Box(Modifier.fillMaxSize()) {
        if (indexers.isEmpty()) {
            Text("No indexers yet. Tap \"Add indexer\" to pick a site.", Modifier.padding(24.dp))
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                16.dp,
                16.dp,
                16.dp,
                96.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(indexers, key = { it.id }) { idx ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "#${idx.id}  ${idx.name}",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(idx.definitionId, style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(
                                checked = idx.enabled,
                                onCheckedChange = { vm.setEnabled(idx, it) })
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (idx.id in testing) CircularProgressIndicator(
                                Modifier
                                    .size(24.dp)
                                    .padding(2.dp)
                            )
                            else TextButton(onClick = { vm.testIndexer(idx) }) { Text("Test") }
                            TextButton(onClick = { onEdit(idx) }) { Text("Edit") }
                            TextButton(onClick = {
                                clipboard.setText(AnnotatedString("http://127.0.0.1:${settings.port}/${idx.id}/api"))
                            }) { Text("Copy URL") }
                            TextButton(onClick = { toDelete = idx }) { Text("Delete") }
                        }
                        challenges[idx.id]?.let { url ->
                            Text(
                                "The site showed an anti-bot check that couldn't be passed automatically.",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            OutlinedButton(onClick = {
                                context.startActivity(
                                    ChallengeActivity.intent(
                                        context,
                                        url
                                    )
                                )
                            }) {
                                Text("Pass site check")
                            }
                        }
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = onAdd,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        ) { Text("Add indexer") }
    }

    toDelete?.let { idx ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Delete ${idx.name}?") },
            text = { Text("Its settings will be removed from this device.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteIndexer(idx); toDelete = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
fun DefinitionPickerScreen(vm: MainViewModel, onPick: (String) -> Unit, onBack: () -> Unit) {
    val defs by vm.definitions.collectAsState()
    val updating by vm.updatingDefinitions.collectAsState()
    var query by remember { mutableStateOf("") }
    var publicOnly by remember { mutableStateOf(true) }
    var privateOnly by remember { mutableStateOf(false) }
    var supportedOnly by remember { mutableStateOf(true) }

    val filtered = remember(defs, query, publicOnly, privateOnly, supportedOnly) {
        defs.filter {
            (!publicOnly || it.type == "public") &&
                    (!privateOnly || it.type == "private") &&
                    (!supportedOnly || it.unsupportedReason == null) &&
                    (query.isBlank() || it.name.contains(query, true) || it.id.contains(
                        query,
                        true
                    ) ||
                            it.description?.contains(query, true) == true)
        }
    }

    Column(Modifier
        .fillMaxSize()
        .padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("Pick a site (${filtered.size})", style = MaterialTheme.typography.titleMedium)
        }
        OutlinedTextField(
            value = query, onValueChange = { query = it }, label = { Text("Search sites") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // public and private exclude each other: picking one clears the other
            CheckFilterChip("Public only", publicOnly) {
                publicOnly = !publicOnly; if (publicOnly) privateOnly = false
            }
            CheckFilterChip("Private only", privateOnly) {
                privateOnly = !privateOnly; if (privateOnly) publicOnly = false
            }
            CheckFilterChip("Supported only", supportedOnly) { supportedOnly = !supportedOnly }
        }
        if (defs.isEmpty()) {
            Text(
                if (updating) "Downloading definitions…" else "No definitions yet: update them from the Server tab.",
                Modifier.padding(16.dp)
            )
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(filtered, key = { it.id }) { d ->
                val enabled = d.unsupportedReason == null
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = enabled) { onPick(d.id) }
                        .padding(vertical = 10.dp),
                ) {
                    Text(d.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        listOfNotNull(
                            d.type,
                            d.language,
                            if (d.requiresLogin) "Login required" else null
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    d.description?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    d.unsupportedReason?.let {
                        Text(
                            it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IndexerEditScreen(
    vm: MainViewModel,
    definitionId: String,
    existing: IndexerConfig?,
    onDone: () -> Unit
) {
    var definition by remember { mutableStateOf<CardigannDefinition?>(null) }
    LaunchedEffect(definitionId) { definition = vm.loadDefinition(definitionId) }
    val def = definition
    if (def == null) {
        Box(
            Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) { CircularProgressIndicator() }
        return
    }

    val context = LocalContext.current
    val appSettings by vm.settings.collectAsState()
    var name by remember { mutableStateOf(existing?.name ?: def.name) }
    var baseUrl by remember { mutableStateOf(existing?.baseUrl ?: def.links.first()) }
    val values = remember {
        mutableStateMapOf<String, String>().apply {
            def.settings.forEach { s -> s.default?.let { put(s.name, it) } }
            existing?.settings?.let { putAll(it) }
        }
    }

    fun config() = IndexerConfig(
        id = existing?.id ?: 0,
        definitionId = def.id,
        name = name.ifBlank { def.name },
        enabled = existing?.enabled ?: true,
        baseUrl = baseUrl,
        settings = values.toMap(),
    )

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(def.name, style = MaterialTheme.typography.headlineSmall)
        def.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        CardigannIndexer.unsupportedReason(def)
            ?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Dropdown("Site URL", def.links.map { it to it }, baseUrl) { baseUrl = it }
        OutlinedButton(onClick = {
            when (appSettings.openSiteIn) {
                SiteBrowser.InApp -> context.startActivity(
                    ChallengeActivity.intent(
                        context,
                        baseUrl
                    )
                )

                SiteBrowser.External -> try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, baseUrl.toUri()))
                } catch (e: ActivityNotFoundException) {
                    vm.showMessage("No browser is installed: change Open site in Settings")
                }
            }
        }) {
            Text("Open site")
        }

        for (s in def.settings) {
            val label = s.label ?: s.name
            when (s.type) {
                "text" -> OutlinedTextField(
                    value = values[s.name] ?: "", onValueChange = { values[s.name] = it },
                    label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )

                "password" -> OutlinedTextField(
                    value = values[s.name] ?: "", onValueChange = { values[s.name] = it },
                    label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )

                "checkbox" -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, Modifier.weight(1f))
                    Switch(
                        checked = values[s.name]?.equals("true", true) == true,
                        onCheckedChange = { values[s.name] = it.toString() },
                    )
                }

                "select" -> Dropdown(
                    label,
                    s.options.orEmpty().map { it.first to (it.second ?: it.first) },
                    values[s.name] ?: s.default ?: ""
                ) {
                    values[s.name] = it
                }

                "info", "info_cookie", "info_useragent", "info_category_8000" -> s.default?.let { html ->
                    Text(
                        HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_COMPACT).toString()
                            .trim(),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                "info_flaresolverr" -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val inApp = appSettings.openSiteIn == SiteBrowser.InApp
                    Text(
                        "This site may use Cloudflare protection. Phonarr passes it in a hidden browser; if that fails, " +
                                (if (inApp) "use Open site above" else "open the site in Phonarr") + " and complete the check.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    // the installed browser's cookies can't be reused, so the check must be passed in the app
                    if (!inApp) {
                        OutlinedButton(onClick = {
                            context.startActivity(
                                ChallengeActivity.intent(
                                    context,
                                    baseUrl
                                )
                            )
                        }) {
                            Text("Pass site check")
                        }
                    }
                }

                else -> {}
            }
        }

        Spacer(Modifier.size(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                vm.saveIndexer(
                    config(),
                    test = true
                ); onDone()
            }) { Text("Save and test") }
            OutlinedButton(onClick = {
                vm.saveIndexer(
                    config(),
                    test = false
                ); onDone()
            }) { Text("Save") }
            TextButton(onClick = onDone) { Text("Cancel") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
/** A Material 3 filter chip that shows a check mark while selected. */
@Composable
private fun CheckFilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = if (selected) {
            {
                Icon(
                    Icons.Filled.Done,
                    contentDescription = null,
                    modifier = Modifier.size(FilterChipDefaults.IconSize)
                )
            }
        } else {
            null
        },
    )
}

@Composable
private fun Dropdown(
    label: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = options.firstOrNull { it.first == selected }?.second ?: selected,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (key, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = { onSelect(key); expanded = false })
            }
        }
    }
}
