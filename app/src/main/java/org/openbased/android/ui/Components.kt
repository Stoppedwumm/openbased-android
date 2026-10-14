package org.openbased.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import org.openbased.android.data.MediaEntity

/** A 2:3 poster with a title below and, when started, a progress bar. */
@Composable
fun PosterCard(
    title: String,
    caption: String?,
    imageUrl: Any?,
    progress: Float?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.clickable(onClick = onClick).padding(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Movie, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
                if (imageUrl != null) {
                    AsyncImage(
                        model = imageUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)),
                    )
                }
                if (progress != null && progress > 0f) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(6.dp),
                    )
                }
            }
        }
        Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (caption != null) {
            Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                maxLines = 1)
        }
    }
}

@Composable
fun MediaPoster(item: MediaEntity, artworkUrl: (String?) -> String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    PosterCard(
        title = item.seriesTitle ?: item.title,
        caption = if (item.seriesTitle != null) episodeCode(item) else item.year?.toString(),
        imageUrl = artworkUrl(item.poster),
        progress = progressFraction(item),
        onClick = onClick,
        modifier = modifier.width(120.dp),
    )
}

fun progressFraction(item: MediaEntity): Float? {
    val position = item.progressPosition ?: return null
    val duration = item.progressDuration ?: item.durationMs?.div(1000.0) ?: return null
    if (item.progressCompleted || duration <= 0) return null
    return (position / duration).toFloat().coerceIn(0f, 1f)
}

fun episodeCode(item: MediaEntity): String? =
    if (item.seasonNumber != null && item.episodeNumber != null) {
        "S%02dE%02d".format(item.seasonNumber, item.episodeNumber)
    } else {
        null
    }

fun formatDuration(ms: Long?): String? {
    if (ms == null || ms <= 0) return null
    val minutes = ms / 60_000
    return if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"
}

fun formatBytes(bytes: Long?): String {
    if (bytes == null) return "?"
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return if (unit == 0) "$bytes B" else "%.1f %s".format(value, units[unit])
}

@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.outline)
    }
}
