package org.openbased.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import org.openbased.android.data.DownloadState
import java.io.File

@Composable
fun DownloadsScreen(onOpenItem: (String) -> Unit) {
    val container = rememberContainer()
    val downloads by container.downloads.all.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        Text("Downloads", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(16.dp))
        if (downloads.isEmpty()) {
            EmptyState("Downloaded movies and episodes appear here and play without a connection.")
        }
        LazyColumn {
            items(downloads, key = { it.mediaId }) { d ->
                ListItem(
                    headlineContent = { Text(d.title) },
                    supportingContent = {
                        Column {
                            d.subtitle?.let { Text(it) }
                            val available = d.state != DownloadState.COMPLETE || container.downloads.isAvailable(d)
                            Text(downloadStatus(d, available), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline)
                            downloadFraction(d)?.let {
                                LinearProgressIndicator(progress = { it },
                                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                            }
                        }
                    },
                    leadingContent = {
                        AsyncImage(
                            model = d.posterPath?.let(::File),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.width(48.dp).aspectRatio(2f / 3f),
                        )
                    },
                    trailingContent = {
                        IconButton(onClick = { scope.launch { container.downloads.delete(d.mediaId) } }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete download")
                        }
                    },
                    modifier = Modifier.clickable { onOpenItem(d.mediaId) },
                )
            }
        }
    }
}
