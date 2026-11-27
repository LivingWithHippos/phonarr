package com.github.livingwithhippos.phonarr.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.github.livingwithhippos.phonarr.browser.ChallengeActivity
import com.github.livingwithhippos.phonarr.core.data.AppSettings
import com.github.livingwithhippos.phonarr.core.data.SiteBrowser
import com.github.livingwithhippos.phonarr.core.torznab.ReleaseInfo
import com.github.livingwithhippos.phonarr.ui.theme.Button
import com.github.livingwithhippos.phonarr.ui.theme.OutlinedButton
import com.github.livingwithhippos.phonarr.ui.theme.PhonarrTheme
import com.github.livingwithhippos.phonarr.ui.theme.TextButton
import kotlinx.coroutines.launch
import org.jsoup.Jsoup
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private const val TORRENT_MIME = "application/x-bittorrent"

@Composable
fun ReleaseDetailScreen(vm: MainViewModel, release: ReleaseInfo) {
    val settings by vm.settings.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current

    var magnet by remember(release) { mutableStateOf(knownMagnet(release)) }
    var torrent by remember(release) { mutableStateOf<ReleaseFile.Torrent?>(null) }
    // the download link turned out to be a magnet, so there's no torrent file to offer
    var downloadIsMagnet by remember(release) { mutableStateOf(false) }
    var loading by remember(release) { mutableStateOf(false) }

    val saveTorrent = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(TORRENT_MIME)) { uri ->
        val file = torrent
        if (uri != null && file != null) vm.saveTorrent(file.file, uri)
    }

    /** Downloads the .torrent once, then runs [block] with it. */
    fun withTorrent(block: (ReleaseFile.Torrent) -> Unit) {
        torrent?.let { block(it); return }
        if (loading) return
        loading = true
        scope.launch {
            when (val f = vm.fetchRelease(release, preferMagnet = false)) {
                is ReleaseFile.Torrent -> { torrent = f; block(f) }
                is ReleaseFile.Magnet -> {
                    magnet = f.uri
                    downloadIsMagnet = true
                    vm.showMessage("This site gives a magnet link instead of a torrent file")
                }
                null -> Unit
            }
            loading = false
        }
    }

    fun start(intent: Intent) {
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            vm.showMessage("No app on this device can open this")
        }
    }

    fun copy(label: String, text: String) {
        scope.launch {
            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(label, text)))
            // Android 13+ shows its own confirmation when something is copied
            if (Build.VERSION.SDK_INT < 33) vm.showMessage("$label copied")
        }
    }

    ReleaseDetailContent(
        release = release,
        magnet = magnet,
        hasTorrentFile = hasTorrentLink(release) && !downloadIsMagnet,
        loading = loading,
        actions = ReleaseDetailActions(
            onOpenMagnet = { start(Intent(Intent.ACTION_VIEW, it.toUri())) },
            onCopy = ::copy,
            onShareMagnet = {
                start(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, it),
                        "Share magnet link",
                    ),
                )
            },
            onOpenTorrent = {
                withTorrent { t ->
                    val uri = t.contentUri(context)
                    start(
                        Intent(Intent.ACTION_VIEW).setDataAndType(uri, TORRENT_MIME)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                    )
                }
            },
            onShareTorrent = {
                withTorrent { t ->
                    val uri = t.contentUri(context)
                    val send = Intent(Intent.ACTION_SEND).setType(TORRENT_MIME)
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    // the chooser needs the ClipData to pass the read grant on
                    send.clipData = ClipData.newRawUri(t.name, uri)
                    start(Intent.createChooser(send, "Share torrent file"))
                }
            },
            onSaveTorrent = { withTorrent { t -> saveTorrent.launch(t.name) } },
            onOpenSitePage = { url ->
                when (settings.openSiteIn) {
                    SiteBrowser.InApp -> start(ChallengeActivity.intent(context, url))
                    SiteBrowser.External -> start(Intent(Intent.ACTION_VIEW, url.toUri()))
                }
            },
            onOpenWeb = { start(Intent(Intent.ACTION_VIEW, it.toUri())) },
        ),
    )
}

private fun ReleaseFile.Torrent.contentUri(context: Context) =
    FileProvider.getUriForFile(context, "${context.packageName}.files", file)

/** Whether the download link points to a file (not a magnet). */
private fun hasTorrentLink(r: ReleaseInfo) =
    r.downloadUrl?.let { !it.startsWith("magnet:", ignoreCase = true) } == true

/** What the detail screen can ask for; no-ops by default so previews can pass ReleaseDetailActions(). */
data class ReleaseDetailActions(
    val onOpenMagnet: (String) -> Unit = {},
    val onCopy: (label: String, text: String) -> Unit = { _, _ -> },
    val onShareMagnet: (String) -> Unit = {},
    val onOpenTorrent: () -> Unit = {},
    val onShareTorrent: () -> Unit = {},
    val onSaveTorrent: () -> Unit = {},
    /** a page on the indexer's site: follows the "Open site" setting */
    val onOpenSitePage: (String) -> Unit = {},
    /** any other web page, such as IMDb */
    val onOpenWeb: (String) -> Unit = {},
)

private val publishFormat = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

@Composable
internal fun ReleaseDetailContent(
    release: ReleaseInfo,
    magnet: String?,
    hasTorrentFile: Boolean,
    loading: Boolean,
    actions: ReleaseDetailActions,
) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SelectionContainer {
                Text(release.title ?: "Untitled", style = MaterialTheme.typography.titleLarge)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                release.indexerName?.let { InfoTag(it, MaterialTheme.colorScheme.primary) }
                InfoTag(if (release.indexerPrivacy.equals("public", true)) "Public" else "Private")
            }
            ReleaseTags(release, showIndexer = false)
        }

        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())

        if (magnet != null) {
            Section("Magnet link") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { actions.onOpenMagnet(magnet) }) { Text("Open") }
                    OutlinedButton(onClick = { actions.onCopy("Magnet link", magnet) }) { Text("Copy") }
                    OutlinedButton(onClick = { actions.onShareMagnet(magnet) }) { Text("Share") }
                }
                release.infoHash?.takeIf { it.isNotBlank() }?.let { hash ->
                    Text("Info hash", style = MaterialTheme.typography.labelLarge)
                    Row {
                        SelectionContainer(Modifier.weight(1f)) {
                            Text(hash, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { actions.onCopy("Info hash", hash) }) { Text("Copy") }
                    }
                }
            }
        }

        if (hasTorrentFile) {
            Section("Torrent file") {
                Text(
                    "Downloaded with this indexer's cookies and login the first time you use one of these.",
                    style = MaterialTheme.typography.bodySmall,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val enabled = !loading
                    Button(onClick = actions.onOpenTorrent, enabled = enabled) { Text("Open") }
                    OutlinedButton(onClick = actions.onShareTorrent, enabled = enabled) { Text("Share") }
                    OutlinedButton(onClick = actions.onSaveTorrent, enabled = enabled) { Text("Save") }
                }
            }
        }

        val details = detailRows(release)
        if (details.isNotEmpty()) {
            Section("Details") {
                details.forEach { (label, value) ->
                    Row {
                        Text(
                            label,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(120.dp),
                        )
                        SelectionContainer(Modifier.weight(1f)) {
                            Text(value, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }

        val links = linkButtons(release)
        val download = release.downloadUrl?.takeIf { hasTorrentLink(release) }
        if (links.isNotEmpty() || download != null) {
            Section("Links") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    links.forEach { link ->
                        OutlinedButton(
                            onClick = {
                                if (link.onIndexerSite) actions.onOpenSitePage(link.url) else actions.onOpenWeb(link.url)
                            },
                        ) { Text(link.label) }
                    }
                }
                if (download != null) {
                    TextButton(onClick = { actions.onCopy("Download link", download) }) { Text("Copy download link") }
                    Text(
                        "Private sites only accept this link with Phonarr's cookies: use the torrent file options instead.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        release.description?.let { Jsoup.parse(it).text() }?.takeIf { it.isNotBlank() }?.let { text ->
            Section("Description") {
                SelectionContainer { Text(text, style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

/** Label/value pairs for everything the result says about itself; empty values are left out. */
private fun detailRows(r: ReleaseInfo): List<Pair<String, String>> = buildList {
    r.size?.let { add("Size" to "${humanSize(it)} (${"%,d".format(it)} bytes)") }
    r.files?.let { add("Files" to it.toString()) }
    r.seeders?.let { add("Seeders" to it.toString()) }
    r.peers?.let { add("Peers" to it.toString()) }
    r.grabs?.let { add("Grabs" to it.toString()) }
    r.publishDate?.let { add("Published" to "${publishFormat.format(it)} (${relativeAge(it)})") }
    r.categories.map { it.name.ifBlank { it.id.toString() } }.takeIf { it.isNotEmpty() }
        ?.let { add("Categories" to it.joinToString(", ")) }
    downloadFactorLabel(r.downloadVolumeFactor)?.let { add("Download" to it) }
    uploadFactorLabel(r.uploadVolumeFactor)?.let { add("Upload" to it) }
    r.minimumRatio?.let { add("Minimum ratio" to it.toString()) }
    r.minimumSeedTime?.let { add("Minimum seed time" to humanDuration(it)) }
    if (r.year > 0) add("Year" to r.year.toString())
    r.genres?.takeIf { it.isNotEmpty() }?.let { add("Genres" to it.joinToString(", ")) }
    r.artist?.let { add("Artist" to it) }
    r.album?.let { add("Album" to it) }
    r.label?.let { add("Label" to it) }
    r.track?.let { add("Track" to it) }
    r.author?.let { add("Author" to it) }
    r.bookTitle?.let { add("Book" to it) }
    r.publisher?.let { add("Publisher" to it) }
}

private class LinkButton(val label: String, val url: String, val onIndexerSite: Boolean)

private fun linkButtons(r: ReleaseInfo): List<LinkButton> = buildList {
    r.infoUrl?.let { add(LinkButton("Details page", it, onIndexerSite = true)) }
    r.commentUrl?.takeIf { it != r.infoUrl }?.let { add(LinkButton("Comments", it, onIndexerSite = true)) }
    if (r.imdbId > 0) add(LinkButton("IMDb", imdbLink(r.imdbId), false))
    if (r.tmdbId > 0) add(LinkButton("TMDb", "https://www.themoviedb.org/movie/${r.tmdbId}", false))
    if (r.tvdbId > 0) add(LinkButton("TVDB", "https://www.thetvdb.com/dereferrer/series/${r.tvdbId}", false))
    if (r.tvMazeId > 0) add(LinkButton("TVmaze", "https://www.tvmaze.com/shows/${r.tvMazeId}", false))
}

@PreviewLightDark
@Composable
private fun ReleaseDetailMagnetPreview() {
    val r = sampleReleases()[0]
    PhonarrTheme(AppSettings("preview")) {
        Surface {
            ReleaseDetailContent(r, magnet = r.magnetUrl, hasTorrentFile = false, loading = false, actions = ReleaseDetailActions())
        }
    }
}

@PreviewLightDark
@Composable
private fun ReleaseDetailTorrentPreview() {
    PhonarrTheme(AppSettings("preview")) {
        Surface {
            ReleaseDetailContent(
                sampleReleases()[1],
                magnet = null,
                hasTorrentFile = true,
                loading = false,
                actions = ReleaseDetailActions(),
            )
        }
    }
}
