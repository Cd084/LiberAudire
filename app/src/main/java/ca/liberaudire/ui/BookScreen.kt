package ca.liberaudire.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import ca.liberaudire.plex.BookDetail

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookScreen(vm: AppViewModel, nav: NavHostController, key: String) {
    var detail by remember { mutableStateOf<BookDetail?>(vm.g.store.book(key)) }
    LaunchedEffect(key) { vm.book(key)?.let { detail = it } }
    val now by vm.now.collectAsStateWithLifecycle()
    val dl by vm.g.downloads.states.collectAsStateWithLifecycle()
    val progressMap by vm.g.store.progress.collectAsStateWithLifecycle()
    val bookmarks by vm.g.store.bookmarks.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(detail?.book?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        bottomBar = { MiniPlayer(vm) { nav.navigate("player") } },
    ) { pad ->
        val d = detail
        if (d == null) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Gold) }
            return@Scaffold
        }
        val playingThis = now?.book?.book?.key == key
        val progress = progressMap[key]
        val position = if (playingThis) now!!.positionMs else progress?.let { d.offsetOf(it.trackIndex) + it.positionMs } ?: 0L
        val state = dl[key]

        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
            item {
                Row {
                    Cover(vm.artUrl(d.book.thumb, 600), Modifier.width(150.dp))
                    Spacer(Modifier.width(16.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(d.book.title, style = MaterialTheme.typography.titleLarge)
                        Text(d.book.author, color = Gold)
                        d.book.year?.let { Text("$it", color = Color(0xFFA9B2C3)) }
                        Text("${fmtLong(d.durationMs)} · ${d.tracks.size} ${if (d.tracks.size == 1) "file" else "parts"}", color = Color(0xFFA9B2C3))
                        if (d.sizeBytes > 0) Text("%.0f MB".format(d.sizeBytes / 1e6), color = Color(0xFFA9B2C3))
                    }
                }
                Spacer(Modifier.height(16.dp))
                if (position > 0 && d.durationMs > 0) {
                    LinearProgressIndicator(progress = { position.toFloat() / d.durationMs }, Modifier.fillMaxWidth(), color = Gold)
                    Text(
                        if (progress?.finished == true) "Finished" else "${fmtLong(d.durationMs - position)} left",
                        style = MaterialTheme.typography.bodySmall, color = Color(0xFFA9B2C3),
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { vm.play(key); nav.navigate("player") }, Modifier.weight(1f)) {
                        Icon(Icons.Default.PlayArrow, null)
                        Text(if (position > 0 && progress?.finished != true) " Resume" else " Play")
                    }
                    when {
                        state?.complete == true -> OutlinedButton({ vm.removeDownload(key) }) {
                            Icon(Icons.Default.DownloadDone, null); Text(" Downloaded")
                        }
                        state?.waitingForWifi == true -> OutlinedButton({ vm.download(d) }) {
                            Icon(Icons.Default.Wifi, null); Text(" Waiting for Wi-Fi")
                        }
                        state != null && state.total > 0 -> OutlinedButton({ vm.removeDownload(key) }) {
                            Text("Downloading ${state.done}/${state.total}")
                        }
                        else -> OutlinedButton({ vm.download(d) }) { Icon(Icons.Default.Download, null); Text(" Download") }
                    }
                }
                if (state?.complete == true) {
                    Text("Tap Downloaded to remove it from the phone.", style = MaterialTheme.typography.bodySmall, color = Color(0xFFA9B2C3))
                }
                d.book.summary?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 8, overflow = TextOverflow.Ellipsis)
                }
            }

            val marks = bookmarks[key].orEmpty()
            if (marks.isNotEmpty()) {
                item { SectionTitle("Bookmarks") }
                items(marks, key = { it.id }) { b ->
                    ListItem(
                        headlineContent = { Text(b.note.ifBlank { "Bookmark" }) },
                        supportingContent = { Text(fmt(d.offsetOf(b.trackIndex) + b.positionMs)) },
                        leadingContent = { Icon(Icons.Default.Bookmark, null, tint = Gold) },
                        trailingContent = { IconButton({ vm.deleteBookmark(key, b.id) }) { Icon(Icons.Default.Delete, "Delete") } },
                        modifier = Modifier.clickable { vm.jumpTo(key, b); nav.navigate("player") },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }

            if (d.tracks.size > 1) {
                item { SectionTitle("Chapters") }
                itemsIndexed(d.tracks) { i, t ->
                    val current = playingThis && now!!.chapterIndex == i
                    ListItem(
                        headlineContent = { Text(d.chapterTitle(i), color = if (current) Gold else Color.Unspecified) },
                        supportingContent = { Text("${fmt(d.offsetOf(i))} · ${fmt(t.durationMs)}") },
                        leadingContent = { if (current) Icon(Icons.Default.GraphicEq, null, tint = Gold) else Text("${i + 1}") },
                        modifier = Modifier.clickable { vm.play(key, fromChapter = i); nav.navigate("player") },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
        }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = Gold, modifier = Modifier.padding(top = 20.dp, bottom = 4.dp))
}
