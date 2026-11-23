package ca.liberaudire.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(vm: AppViewModel, nav: NavHostController) {
    val now by vm.now.collectAsStateWithLifecycle()
    vm.g.prefs.changes.collectAsStateWithLifecycle().value // skip amounts may change
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var customSkip by remember { mutableStateOf<Boolean?>(null) } // true = forward, false = back
    var speedOpen by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf<Float?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    now?.let { n -> IconButton({ nav.navigate("book/${n.book.book.key}") }) { Icon(Icons.AutoMirrored.Filled.List, "Chapters") } }
                },
            )
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        val n = now
        if (n == null) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { Text("Nothing playing") }
            return@Scaffold
        }
        val back = vm.g.prefs.skipBackSec
        val fwd = vm.g.prefs.skipForwardSec
        val inChapter = n.positionMs - n.chapterStartMs
        Column(
            Modifier.padding(pad).fillMaxSize().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Cover(vm.artUrl(n.book.book.thumb, 800), Modifier.widthIn(max = 360.dp).fillMaxWidth(0.85f))
            Spacer(Modifier.height(20.dp))
            Text(n.book.book.title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(n.book.book.author, color = Gold)
            if (n.book.tracks.size > 1) {
                Text(
                    "${n.book.chapterTitle(n.chapterIndex)} · ${n.chapterIndex + 1} of ${n.book.tracks.size}",
                    color = Color(0xFFA9B2C3), maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(16.dp))

            // Scrubber for the current chapter (the whole book if it's one file).
            val len = n.chapterLengthMs.coerceAtLeast(1)
            Slider(
                value = dragging ?: (inChapter.toFloat() / len).coerceIn(0f, 1f),
                onValueChange = { dragging = it },
                onValueChangeFinished = { dragging?.let { vm.seekTo(n.chapterStartMs + (it * len).toLong()) }; dragging = null },
                colors = SliderDefaults.colors(thumbColor = Gold, activeTrackColor = Gold),
            )
            Row(Modifier.fillMaxWidth()) {
                Text(fmt(dragging?.let { (it * len).toLong() } ?: inChapter), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.weight(1f))
                Text("-" + fmt(len - inChapter), style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "${fmtLong(n.book.durationMs - n.positionMs)} left in book" + if (n.speed != 1f) " (at ${speedLabel(n.speed)})" else "",
                style = MaterialTheme.typography.bodySmall, color = Color(0xFFA9B2C3),
            )
            Spacer(Modifier.height(16.dp))

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton({ vm.chapter(-1) }) { Icon(Icons.Default.SkipPrevious, "Previous chapter", Modifier.size(32.dp)) }
                SkipButton("-$back", "Back $back seconds. Hold for a custom amount.", onClick = vm::skipBack) { customSkip = false }
                Surface(
                    shape = CircleShape, color = Gold, modifier = Modifier.size(76.dp),
                    onClick = vm::togglePlay,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(if (n.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Play/pause", tint = Navy, modifier = Modifier.size(44.dp))
                    }
                }
                SkipButton("+$fwd", "Forward $fwd seconds. Hold for a custom amount.", onClick = vm::skipForward) { customSkip = true }
                IconButton({ vm.chapter(1) }) { Icon(Icons.Default.SkipNext, "Next chapter", Modifier.size(32.dp)) }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton({ speedOpen = true }) { Text(speedLabel(n.speed)) }
                OutlinedButton({
                    vm.addBookmark()
                    scope.launch { snack.showSnackbar("Bookmark added at ${fmt(n.positionMs)}") }
                }) { Icon(Icons.Default.BookmarkAdd, null); Text(" Bookmark") }
            }
        }
    }

    customSkip?.let { forward ->
        CustomSkipDialog(
            vm = vm, forward = forward,
            onDismiss = { customSkip = null },
            onSkip = { ms -> vm.seekBy(ms); customSkip = null },
        )
    }
    if (speedOpen) SpeedDialog(now?.speed ?: 1f, onDismiss = { speedOpen = false }) { vm.setSpeed(it) }
}

fun speedLabel(s: Float) = if (s == s.toInt().toFloat()) "${s.toInt()}.0×" else "${"%.2f".format(s).trimEnd('0')}×"

/** Round skip button: tap = the set amount, long press = custom amount. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SkipButton(label: String, description: String, onClick: () -> Unit, onLongClick: () -> Unit) {
    Surface(
        shape = CircleShape, color = NavyLight,
        modifier = Modifier.size(64.dp).combinedClickable(onClick = onClick, onLongClick = onLongClick, onClickLabel = description),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Gold)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CustomSkipDialog(vm: AppViewModel, forward: Boolean, onDismiss: () -> Unit, onSkip: (Long) -> Unit) {
    val last = vm.g.prefs.customSkipSec
    var minutes by remember { mutableStateOf((last / 60).toString()) }
    var seconds by remember { mutableStateOf((last % 60).toString()) }
    fun total() = (minutes.toIntOrNull() ?: 0) * 60 + (seconds.toIntOrNull() ?: 0)
    fun go(dir: Int) {
        val t = total()
        if (t <= 0) return
        vm.g.prefs.customSkipSec = t
        onSkip(dir * t * 1000L)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (forward) "Skip forward" else "Skip back") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(60, 120, 300, 600, 900, 1800).forEach { s ->
                        FilterChip(
                            selected = total() == s,
                            onClick = { minutes = (s / 60).toString(); seconds = "0" },
                            label = { Text("${s / 60} min") },
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        minutes, { minutes = it.filter(Char::isDigit).take(3) }, Modifier.width(100.dp),
                        label = { Text("Minutes") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    OutlinedTextField(
                        seconds, { seconds = it.filter(Char::isDigit).take(2) }, Modifier.width(100.dp),
                        label = { Text("Seconds") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
            }
        },
        confirmButton = {
            Button({ go(if (forward) 1 else -1) }) { Text(if (forward) "Forward" else "Back") }
        },
        dismissButton = {
            Row {
                TextButton({ go(if (forward) -1 else 1) }) { Text(if (forward) "Back instead" else "Forward instead") }
                TextButton(onDismiss) { Text("Cancel") }
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpeedDialog(current: Float, onDismiss: () -> Unit, onSpeed: (Float) -> Unit) {
    var speed by remember { mutableFloatStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Playback speed: ${speedLabel(speed)}") },
        text = {
            Column {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0.8f, 1.0f, 1.1f, 1.2f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f).forEach { s ->
                        FilterChip(selected = speed == s, onClick = { speed = s; onSpeed(s) }, label = { Text(speedLabel(s)) })
                    }
                }
                Slider(
                    value = speed, valueRange = 0.5f..3f, steps = 49,
                    onValueChange = { speed = (Math.round(it * 20) / 20f) },
                    onValueChangeFinished = { onSpeed(speed) },
                    colors = SliderDefaults.colors(thumbColor = Gold, activeTrackColor = Gold),
                )
                Text("Saved for this book.", style = MaterialTheme.typography.bodySmall, color = Color(0xFFA9B2C3))
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("Done") } },
    )
}
