package ca.liberaudire.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import ca.liberaudire.plex.Book

val Gold = Color(0xFFE0B04A)
val Navy = Color(0xFF101826)
val NavyLight = Color(0xFF1B2638)

@Composable
fun LiberTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Gold,
            onPrimary = Navy,
            secondary = Gold,
            background = Navy,
            surface = Navy,
            surfaceVariant = NavyLight,
            surfaceContainer = NavyLight,
            onBackground = Color(0xFFEDE6D6),
            onSurface = Color(0xFFEDE6D6),
        ),
        content = content,
    )
}

/** 1:23:45 / 23:45 */
fun fmt(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/** "5h 12m" */
fun fmtLong(ms: Long): String {
    val min = ms / 60_000
    return if (min >= 60) "${min / 60}h ${min % 60}m" else "${min}m"
}

@Composable
fun Cover(url: String?, modifier: Modifier = Modifier) {
    Box(
        modifier.aspectRatio(1f).clip(RoundedCornerShape(8.dp)).background(NavyLight),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Default.MenuBook, null, tint = Gold.copy(alpha = 0.5f), modifier = Modifier.fillMaxSize(0.4f))
        if (url != null) AsyncImage(url, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

/** A book tile: cover, title, author, progress and a "downloaded" badge. */
@Composable
fun BookCard(vm: AppViewModel, book: Book, progress: Float?, downloaded: Boolean, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick).padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box {
            Cover(vm.artUrl(book.thumb), Modifier.fillMaxWidth())
            if (downloaded) {
                Icon(
                    Icons.Default.DownloadDone, "Downloaded", tint = Navy,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
                        .background(Gold, RoundedCornerShape(50)).padding(3.dp),
                )
            }
        }
        if (progress != null && progress > 0f) {
            LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth(), color = Gold)
        }
        Text(book.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(book.author, style = MaterialTheme.typography.bodySmall, color = Color(0xFFA9B2C3), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** How far through a book (0–1), from this phone's saved progress. */
fun bookProgress(vm: AppViewModel, key: String): Float? {
    val p = vm.g.store.progress.value[key] ?: return null
    if (p.finished) return 1f
    val d = vm.g.store.book(key) ?: return null
    if (d.durationMs <= 0) return null
    return ((d.offsetOf(p.trackIndex) + p.positionMs).toFloat() / d.durationMs).coerceIn(0f, 1f)
}
