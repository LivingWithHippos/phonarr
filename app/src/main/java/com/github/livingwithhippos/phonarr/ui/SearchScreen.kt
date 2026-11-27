package com.github.livingwithhippos.phonarr.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.github.livingwithhippos.phonarr.core.data.AppSettings
import com.github.livingwithhippos.phonarr.core.torznab.ReleaseInfo
import com.github.livingwithhippos.phonarr.ui.theme.Button
import com.github.livingwithhippos.phonarr.ui.theme.PhonarrTheme
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun SearchScreen(vm: MainViewModel) {
    val state by vm.search.collectAsState()
    val indexers by vm.indexers.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    val haptics = LocalHapticFeedback.current

    val settings by vm.settings.collectAsState()
    SearchContent(
        state = state,
        canSearch = indexers.any { it.enabled },
        detailed = settings.detailedResults,
        actions = SearchActions(
            onSearch = { vm.runSearch(it, "all") },
            onOpenDetail = vm::openRelease,
            onCopyMagnet = { r ->
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
            onOpen = { r ->
                scope.launch {
                    val link = vm.resolveDownload(r) ?: return@launch
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, link.toUri()))
                    } catch (e: ActivityNotFoundException) {
                        vm.showMessage("No app on this device can open this link")
                    }
                }
            },
        ),
    )
}

/** What the search screen can ask for; no-ops by default so previews can pass SearchActions(). */
data class SearchActions(
    val onSearch: (String) -> Unit = {},
    val onOpen: (ReleaseInfo) -> Unit = {},
    val onCopyMagnet: (ReleaseInfo) -> Unit = {},
    val onOpenDetail: (ReleaseInfo) -> Unit = {},
)

@Composable
internal fun SearchContent(
    state: SearchState,
    canSearch: Boolean,
    actions: SearchActions,
    detailed: Boolean = false,
    listState: LazyListState = rememberLazyListState(),
) {
    var query by rememberSaveable { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                label = { Text("Search all enabled indexers") },
                singleLine = true, modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { actions.onSearch(query) }),
            )
            Button(
                onClick = { actions.onSearch(query) },
                enabled = !state.running && canSearch,
                // the field reserves 8dp above its box for the floating label: offset to center on the box
                modifier = Modifier.padding(top = 8.dp),
            ) { Text("Go") }
        }
        if (state.running) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.errors.forEach { (name, err) ->
            Text(
                "$name: $err",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (state.searched && !state.running) Text(
            "${state.results.size} results",
            style = MaterialTheme.typography.labelMedium
        )
        if (detailed) {
            LazyColumn(
                Modifier.fillMaxSize(),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.results) { r ->
                    ResultCard(r, onLongClick = { actions.onCopyMagnet(r) }) {
                        actions.onOpenDetail(
                            r
                        )
                    }
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), state = listState) {
                items(state.results) { r ->
                    ResultRow(r, onLongClick = { actions.onCopyMagnet(r) }) { actions.onOpen(r) }
                    HorizontalDivider()
                }
            }
        }
    }
}

/** A result as a card with its key facts as tags; tapping opens the detail screen. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ResultCard(r: ReleaseInfo, onLongClick: () -> Unit, onClick: () -> Unit) {
    OutlinedCard(
        Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onClickLabel = "Show details",
                onLongClick = onLongClick,
                onLongClickLabel = "Copy magnet link",
            ),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                r.title ?: "",
                style = MaterialTheme.typography.titleSmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            ReleaseTags(r)
        }
    }
}

private val dateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ResultRow(r: ReleaseInfo, onLongClick: () -> Unit, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = "Copy magnet link"
            )
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


@PreviewLightDark
@Composable
private fun SearchPreview() {
    val state = SearchState(
        searched = true,
        results = sampleReleases(),
        errors = mapOf("Slow Site" to "Connection timed out"),
    )
    PhonarrTheme(AppSettings("preview")) {
        Surface { SearchContent(state, canSearch = true, actions = SearchActions()) }
    }
}

@PreviewLightDark
@Composable
private fun SearchDetailedPreview() {
    val state = SearchState(searched = true, results = sampleReleases())
    PhonarrTheme(AppSettings("preview")) {
        Surface {
            SearchContent(
                state,
                canSearch = true,
                actions = SearchActions(),
                detailed = true
            )
        }
    }
}
