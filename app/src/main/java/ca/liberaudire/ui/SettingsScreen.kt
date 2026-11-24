package ca.liberaudire.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import ca.liberaudire.plex.HomeUser
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(vm: AppViewModel, nav: NavHostController) {
    val p = vm.g.prefs
    p.changes.collectAsStateWithLifecycle().value
    val downloads by vm.g.store.downloads.collectAsStateWithLifecycle()
    var profiles by remember { mutableStateOf<List<HomeUser>?>(null) }
    var cacheBytes by remember { mutableStateOf(0L) }
    LaunchedEffect(downloads) { cacheBytes = vm.g.cache.cacheSpace }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            SectionTitle("Account")
            Text("Signed in as ${p.userTitle}", style = MaterialTheme.typography.bodyLarge)
            Text("${p.server?.name ?: "-"} · ${p.libraryTitle}", color = Color(0xFFA9B2C3))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (p.userHome) OutlinedButton({ profiles = emptyList() }) { Text("Switch profile") }
                OutlinedButton(vm::changeServer) { Text("Change server/library") }
                OutlinedButton(vm::signOut) { Text("Sign out") }
            }

            SectionTitle("Skip buttons")
            Text("Back (tap)", color = Color(0xFFA9B2C3))
            Choices(listOf(10, 15, 30, 45, 60, 90), p.skipBackSec, { "$it s" }) { p.skipBackSec = it }
            Text("Forward (tap)", color = Color(0xFFA9B2C3))
            Choices(listOf(10, 15, 30, 45, 60, 90), p.skipForwardSec, { "$it s" }) { p.skipForwardSec = it }
            Text(
                "Hold either button for a custom amount. Android Auto uses these amounts too (no typing while driving).",
                style = MaterialTheme.typography.bodySmall, color = Color(0xFFA9B2C3),
            )

            SectionTitle("Playback")
            Text("Default speed for new books", color = Color(0xFFA9B2C3))
            Choices(listOf(0.8f, 1.0f, 1.1f, 1.2f, 1.25f, 1.5f, 1.75f, 2.0f), p.defaultSpeed, ::speedLabel) { p.defaultSpeed = it }
            Toggle("Smart rewind", "After a pause, go back a little: 3 s after a few minutes, 10 s after up to an hour, 30 s after longer.", p.smartRewind) { p.smartRewind = it }

            SectionTitle("Offline listening")
            Text("Download the whole book after listening for", color = Color(0xFFA9B2C3))
            Choices(listOf(0, 5, 10, 15, 30), p.autoDownloadAfterMin, { if (it == 0) "Never" else "$it min" }) { p.autoDownloadAfterMin = it }
            Toggle(
                "Only download whole books on Wi-Fi",
                "Whole-book downloads wait for Wi-Fi. Streaming on mobile data still keeps about 10 minutes ahead on the phone.",
                p.wifiOnlyFullDownloads,
            ) { p.wifiOnlyFullDownloads = it }
            Toggle("Delete finished books", "Remove a book's download once you've finished it.", p.deleteFinished) { p.deleteFinished = it }
            Text("Storage limit for automatic downloads", color = Color(0xFFA9B2C3))
            Choices(listOf(2, 5, 10, 20, 50, 0), p.storageLimitGb, { if (it == 0) "No limit" else "$it GB" }) { p.storageLimitGb = it }
            Text(
                "Books you download yourself don't count and are never removed to make room.",
                style = MaterialTheme.typography.bodySmall, color = Color(0xFFA9B2C3),
            )
            val dlBytes = downloads.values.sumOf { it.sizeBytes }
            Text(
                "Using %.1f GB: %d downloaded books (%.1f GB) plus streaming cache.".format(cacheBytes / 1e9, downloads.size, dlBytes / 1e9),
                modifier = Modifier.padding(top = 8.dp),
            )
            OutlinedButton({
                vm.g.downloads.trimStreamingCache(vm.now.value?.book?.book?.key, maxBytes = 0)
                cacheBytes = vm.g.cache.cacheSpace
            }) { Text("Clear streaming cache") }

            SectionTitle("About")
            val ctx = LocalContext.current
            val version = remember { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }
            Text("LiberAudīre $version", color = Color(0xFFA9B2C3))
            Text(
                "Copyright © 2026 Cd084. Free software under the GNU General Public License v3: " +
                    "you may share and change it under that licence's terms. It comes with no warranty. " +
                    "Not affiliated with or endorsed by Plex.",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFA9B2C3),
                modifier = Modifier.padding(top = 4.dp),
            )
            TextButton({ vm.openInBrowser("https://github.com/Cd084/LiberAud-re") }) { Text("Source code and licence") }
        }
    }

    profiles?.let { ProfileDialog(vm) { profiles = null } }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> Choices(options: List<T>, selected: T, label: (T) -> String, onPick: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o -> FilterChip(selected = o == selected, onClick = { onPick(o) }, label = { Text(label(o)) }) }
    }
}

@Composable
private fun Toggle(title: String, sub: String, value: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(sub) },
        trailingContent = { Switch(value, onChange) },
        modifier = Modifier.clickable { onChange(!value) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

/** "Who's listening?": pick a Plex Home profile (PIN if it has one). */
@Composable
private fun ProfileDialog(vm: AppViewModel, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var users by remember { mutableStateOf<List<HomeUser>?>(null) }
    var pinFor by remember { mutableStateOf<HomeUser?>(null) }
    var pin by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { users = runCatching { vm.homeUsers() }.getOrElse { message = it.message; emptyList() } }

    fun switchTo(u: HomeUser, p: String?) = scope.launch {
        message = vm.switchProfile(u, p)
        if (message == null) onDone()
    }

    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(pinFor?.let { "PIN for ${it.title}" } ?: "Who's listening?") },
        text = {
            Column {
                val target = pinFor
                if (target != null) {
                    OutlinedTextField(
                        pin, { pin = it.filter(Char::isDigit).take(4) }, label = { Text("PIN") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    )
                } else {
                    users?.forEach { u ->
                        ListItem(
                            headlineContent = { Text(u.title) },
                            trailingContent = { if (u.protected) Icon(Icons.Default.Lock, "PIN") },
                            modifier = Modifier.clickable { if (u.protected) pinFor = u else switchTo(u, null) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        )
                    }
                }
                message?.let { Text(it, color = Color(0xFFE5534B)) }
            }
        },
        confirmButton = {
            val target = pinFor
            if (target != null) TextButton({ switchTo(target, pin) }) { Text("Continue") }
        },
        dismissButton = { TextButton(onDone) { Text("Cancel") } },
    )
}
