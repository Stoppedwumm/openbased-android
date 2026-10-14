package org.openbased.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.openbased.android.data.MediaEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onOpenLibrary: (String) -> Unit, onOpenItem: (String) -> Unit) {
    val container = rememberContainer()
    val libraries by container.catalog.libraries.collectAsState(initial = emptyList())
    val continueWatching by container.catalog.continueWatching().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val refresh: () -> Unit = {
        refreshing = true
        scope.launch {
            error = runCatching { container.catalog.sync() }.exceptionOrNull()?.let { "Could not refresh: ${it.message}" }
            refreshing = false
        }
    }

    PullToRefreshBox(isRefreshing = refreshing, onRefresh = refresh, modifier = Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize()) {
            item { Text("OpenBased", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(16.dp)) }
            if (error != null) {
                item { Text(error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp)) }
            }
            if (continueWatching.isNotEmpty()) {
                item { SectionTitle("Continue watching") }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp)) {
                        items(continueWatching, key = { it.id }) { media ->
                            MediaPoster(media, { container.api.artworkUrl(it, 185) }, { onOpenItem(media.id) })
                        }
                    }
                }
            }
            item { SectionTitle("Libraries") }
            if (libraries.isEmpty()) {
                item {
                    Text(
                        if (refreshing) "Loading…" else "No libraries yet. Pull down to refresh.",
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            items(libraries, key = { it.id }) { library ->
                ListItem(
                    headlineContent = { Text(library.name) },
                    leadingContent = {
                        Icon(
                            when (library.type) {
                                "MOVIES" -> Icons.Outlined.Movie
                                "TV" -> Icons.Outlined.Tv
                                "MUSIC" -> Icons.Outlined.MusicNote
                                else -> Icons.Outlined.VideoLibrary
                            },
                            contentDescription = null,
                        )
                    },
                    modifier = Modifier.clickable { onOpenLibrary(library.id) },
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 8.dp))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    libraryId: String,
    onOpenSeries: (String, String) -> Unit,
    onOpenItem: (String) -> Unit,
    onBack: () -> Unit,
) {
    val container = rememberContainer()
    val library by container.catalog.library(libraryId).collectAsState(initial = null)
    val series by container.catalog.series(libraryId).collectAsState(initial = emptyList())
    val items by container.catalog.itemsWithoutSeries(libraryId).collectAsState(initial = emptyList())
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(library?.name ?: "") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
        if (series.isEmpty() && items.isEmpty()) {
            EmptyState("Nothing here yet.")
        } else {
            LazyVerticalGrid(GridCells.Adaptive(112.dp), contentPadding = PaddingValues(8.dp)) {
                items(series, key = { "series:" + it.seriesTitle }) { first ->
                    PosterCard(
                        title = first.seriesTitle!!,
                        caption = "Series",
                        imageUrl = container.api.artworkUrl(first.poster, 185),
                        progress = null,
                        onClick = { onOpenSeries(libraryId, first.seriesTitle) },
                    )
                }
                items(items, key = { it.id }) { media ->
                    PosterCard(
                        title = media.title,
                        caption = media.year?.toString(),
                        imageUrl = container.api.artworkUrl(media.poster, 185),
                        progress = progressFraction(media),
                        onClick = { onOpenItem(media.id) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeriesScreen(libraryId: String, seriesTitle: String, onOpenItem: (String) -> Unit, onBack: () -> Unit) {
    val container = rememberContainer()
    val episodes by container.catalog.episodes(libraryId, seriesTitle).collectAsState(initial = emptyList())
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(seriesTitle) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
        LazyColumn {
            var season: Int? = -1
            episodes.forEach { episode ->
                if (episode.seasonNumber != season) {
                    season = episode.seasonNumber
                    item(key = "season:$season") {
                        Text(
                            season?.let { "Season $it" } ?: "Other",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 4.dp),
                        )
                    }
                }
                item(key = episode.id) { EpisodeRow(episode, container.api::artworkUrl) { onOpenItem(episode.id) } }
            }
        }
    }
}

@Composable
private fun EpisodeRow(episode: MediaEntity, artworkUrl: (String?, Int) -> String?, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(episode.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                Text(listOfNotNull(episodeCode(episode), formatDuration(episode.durationMs)).joinToString(" · "))
                progressFraction(episode)?.let {
                    LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                }
            }
        },
        leadingContent = {
            AsyncImage(
                model = artworkUrl(episode.poster, 92),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.width(48.dp).aspectRatio(2f / 3f),
            )
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/** Searches the local catalogue, so it works offline too. */
@Composable
fun SearchScreen(onOpenItem: (String) -> Unit) {
    val container = rememberContainer()
    var query by rememberSaveable { mutableStateOf("") }
    val results by remember(query) {
        if (query.isBlank()) flowOf(emptyList()) else container.catalog.search(query.trim())
    }.collectAsState(initial = emptyList())
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search movies and shows") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
        if (query.isNotBlank() && results.isEmpty()) {
            EmptyState("No matches.")
        }
        LazyVerticalGrid(GridCells.Adaptive(112.dp), contentPadding = PaddingValues(8.dp)) {
            items(results, key = { it.id }) { media ->
                PosterCard(
                    title = media.seriesTitle ?: media.title,
                    caption = if (media.seriesTitle != null) "${episodeCode(media) ?: ""} ${media.title}".trim()
                    else media.year?.toString(),
                    imageUrl = container.api.artworkUrl(media.poster, 185),
                    progress = progressFraction(media),
                    onClick = { onOpenItem(media.id) },
                )
            }
        }
    }
}
