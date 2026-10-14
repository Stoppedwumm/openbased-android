package org.openbased.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import org.openbased.android.api.MediaDetail
import org.openbased.android.data.DownloadEntity
import org.openbased.android.data.DownloadQuality
import org.openbased.android.data.DownloadState
import org.openbased.android.playback.PlayerActivity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(mediaId: String, onBack: () -> Unit, onNeedNotifications: () -> Unit) {
    val container = rememberContainer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val item by container.catalog.item(mediaId).collectAsState(initial = null)
    val download by container.downloads.observe(mediaId).collectAsState(initial = null)
    // Fresh details (files, progress) when online; the local catalogue is enough otherwise.
    var detail by remember(mediaId) { mutableStateOf<MediaDetail?>(null) }
    LaunchedEffect(mediaId) {
        detail = runCatching { container.api.mediaDetail(mediaId) }.getOrNull()
    }
    val canDownload by produceState(true) {
        value = runCatching { "media.download" in container.api.permissions() }.getOrDefault(true)
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
        val media = item ?: return@Column EmptyState("Loading…")
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                AsyncImage(
                    model = download?.posterPath?.let { java.io.File(it) } ?: container.api.artworkUrl(media.poster, 342),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.width(120.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)),
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(media.seriesTitle ?: media.title, style = MaterialTheme.typography.headlineSmall)
                    if (media.seriesTitle != null) {
                        Text(listOfNotNull(episodeCode(media), media.title).joinToString(" · "),
                            style = MaterialTheme.typography.titleSmall)
                    }
                    Text(
                        listOfNotNull(media.year?.toString(), formatDuration(media.durationMs),
                            media.genres.takeIf { it.isNotBlank() }).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    detail?.files?.firstOrNull()?.let { file ->
                        Text(
                            listOfNotNull(file.height?.let { "${it}p" }, file.videoCodec?.uppercase(),
                                file.container?.uppercase(), formatBytes(file.size)).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }

            val resumeAt = (detail?.progress ?: media.progressPosition?.let {
                org.openbased.android.api.UserProgress(it, media.progressDuration, media.progressCompleted)
            })?.takeIf { !it.completed && it.position > 30 }?.position
            // A download on an SD card that is not inserted cannot be played; stream instead.
            val offline = download?.let { container.downloads.isAvailable(it) } == true
            Button(
                onClick = { PlayerActivity.start(context, mediaId, offline, ((resumeAt ?: 0.0) * 1000).toLong()) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Text(
                    when {
                        resumeAt != null -> "Resume from ${formatPosition(resumeAt)}"
                        else -> "Play"
                    } + if (offline) " (downloaded)" else "",
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            if (resumeAt != null) {
                OutlinedButton(
                    onClick = { PlayerActivity.start(context, mediaId, offline, 0) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Play from the beginning") }
            }

            if (canDownload) {
                DownloadSection(
                    download = download,
                    available = download?.let { it.state != DownloadState.COMPLETE || container.downloads.isAvailable(it) } ?: true,
                    onDownload = { quality ->
                        onNeedNotifications()
                        scope.launch { container.downloads.start(media, quality) }
                    },
                    onRetry = { container.downloads.enqueue(mediaId) },
                    onDelete = { scope.launch { container.downloads.delete(mediaId) } },
                )
            }

            media.overview?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
private fun DownloadSection(
    download: DownloadEntity?,
    available: Boolean,
    onDownload: (DownloadQuality) -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    if (download == null) {
        Box {
            OutlinedButton(onClick = { menu = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Download, contentDescription = null)
                Text("Download", modifier = Modifier.padding(start = 8.dp))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DownloadQuality.entries.forEach { quality ->
                    DropdownMenuItem(
                        text = {
                            Text(if (quality == DownloadQuality.ORIGINAL) "Original file" else "Optimized · ${quality.label}")
                        },
                        onClick = {
                            menu = false
                            onDownload(quality)
                        },
                    )
                }
            }
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(downloadStatus(download, available), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (download.state == DownloadState.FAILED) {
                OutlinedButton(onClick = onRetry) { Text("Retry") }
            }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Delete download") }
        }
        downloadFraction(download)?.let {
            LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth())
        }
    }
}

fun downloadStatus(d: DownloadEntity, available: Boolean = true): String = when (d.state) {
    DownloadState.COMPLETE -> if (available) "Downloaded · ${d.quality.label} · ${formatBytes(d.totalBytes)}"
        else "Downloaded to storage that is not available (SD card removed?)"
    DownloadState.QUEUED -> "Waiting to download (${d.quality.label})"
    DownloadState.PREPARING -> "Preparing ${d.quality.label} on the server" + (d.preparingPercent?.let { " · $it%" } ?: "")
    DownloadState.DOWNLOADING -> "Downloading · ${formatBytes(d.downloadedBytes)} of ${formatBytes(d.totalBytes)}"
    DownloadState.FAILED -> "Download failed: ${d.error ?: "unknown error"}"
}

fun downloadFraction(d: DownloadEntity): Float? = when (d.state) {
    DownloadState.PREPARING -> (d.preparingPercent ?: 0) / 100f
    DownloadState.DOWNLOADING -> d.totalBytes?.takeIf { it > 0 }?.let { d.downloadedBytes.toFloat() / it }
    else -> null
}

fun formatPosition(seconds: Double): String {
    val total = seconds.toLong()
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
