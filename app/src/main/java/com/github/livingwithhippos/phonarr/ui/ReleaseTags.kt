package com.github.livingwithhippos.phonarr.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import com.github.livingwithhippos.phonarr.core.torznab.ReleaseInfo

/** A small display-only label, styled like the server's status chip. */
@Composable
fun InfoTag(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = color,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier
                .heightIn(min = 24.dp)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

/** Seeder colors that read well on both light and dark surfaces. */
@Composable
fun seedColor(health: SeedHealth): Color {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return when (health) {
        SeedHealth.Good -> if (dark) Color(0xFF81C784) else Color(0xFF2E7D32)
        SeedHealth.Low -> if (dark) Color(0xFFFFB74D) else Color(0xFFB26A00)
        SeedHealth.Dead -> MaterialTheme.colorScheme.error
    }
}

/** Seeders/peers, size, age, category, indexer and freeleech, as tags. */
@Composable
fun ReleaseTags(r: ReleaseInfo, showIndexer: Boolean = true) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        if (r.seeders != null || r.peers != null) {
            InfoTag("▲ ${r.seeders ?: "?"} · ▼ ${r.peers ?: "?"}", seedColor(seedHealth(r.seeders)))
        }
        r.size?.let { InfoTag(humanSize(it)) }
        r.publishDate?.let { InfoTag(relativeAge(it)) }
        r.categories.firstOrNull { it.name.isNotBlank() }?.let { InfoTag(it.name) }
        if (showIndexer) r.indexerName?.let { InfoTag(it) }
        downloadFactorLabel(r.downloadVolumeFactor)?.let {
            InfoTag(
                it,
                MaterialTheme.colorScheme.primary
            )
        }
    }
}
