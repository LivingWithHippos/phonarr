package com.github.livingwithhippos.phonarr.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.ClipData
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import com.github.livingwithhippos.phonarr.ui.theme.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.github.livingwithhippos.phonarr.core.torznab.ReleaseInfo
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun SearchScreen(vm: MainViewModel) {
    val state by vm.search.collectAsState()
    val indexers by vm.indexers.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    val haptics = LocalHapticFeedback.current

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                label = { Text("Search all enabled indexers") },
                singleLine = true, modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.runSearch(query, "all") }),
            )
            Button(onClick = { vm.runSearch(query, "all") }, enabled = !state.running && indexers.any { it.enabled }) { Text("Go") }
        }
        if (state.running) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.errors.forEach { (name, err) ->
            Text("$name: $err", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        if (state.searched && !state.running) Text("${state.results.size} results", style = MaterialTheme.typography.labelMedium)
        LazyColumn(Modifier.fillMaxSize()) {
            items(state.results) { r ->
                ResultRow(
                    r,
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        scope.launch {
                            val magnet = vm.resolveMagnet(r)
                            if (magnet == null) {
                                vm.showMessage("This result has no magnet link")
                                return@launch
                            }
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Magnet link", magnet)))
                            // Android 13+ shows its own confirmation when something is copied
                            if (android.os.Build.VERSION.SDK_INT < 33) vm.showMessage("Magnet link copied")
                        }
                    },
                ) {
                    scope.launch {
                        val link = vm.resolveDownload(r) ?: return@launch
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, link.toUri()))
                        } catch (e: ActivityNotFoundException) {
                            vm.showMessage("No app on this device can open this link")
                        }
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

private val dateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ResultRow(r: ReleaseInfo, onLongClick: () -> Unit, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = "Copy magnet link")
            .padding(vertical = 8.dp),
    ) {
        Text(r.title ?: "", style = MaterialTheme.typography.bodyMedium)
        Text(
            listOfNotNull(
                r.indexerName,
                r.size?.let(::humanSize),
                r.seeders?.let { "$it seeders" },
                r.publishDate?.let { dateFormat.format(it) },
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun humanSize(bytes: Long): String {
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var v = bytes.toDouble()
    var i = 0
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return if (i == 0) "$bytes B" else "%.1f %s".format(v, units[i])
}
