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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay30
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import ca.liberaudire.plex.Book
import kotlinx.coroutines.delay

@Composable
fun Root(vm: AppViewModel) {
    val setup by vm.setup.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(error) { error?.let { snack.showSnackbar(it); vm.clearError() } }

    Box(Modifier.fillMaxSize()) {
        when (val s = setup) {
            Setup.SignedOut -> SignInScreen { vm.signIn(vm::openInBrowser) }
            is Setup.WaitingForPlex -> WaitingScreen(s.authUrl) { vm.openInBrowser(s.authUrl) }
            is Setup.PickServer -> PickerScreen("Choose a Plex server", s.servers.map { it.name + if (it.owned) "" else "  (shared)" }) {
                vm.pickServer(s.servers[it])
            }
            is Setup.PickLibrary -> PickerScreen("Which library has your audiobooks?", s.libraries.map { it.title }) {
                vm.pickLibrary(s.libraries[it])
            }
            Setup.Ready -> MainNav(vm)
        }
        SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).padding(bottom = 80.dp))
    }
}

@Composable
private fun SignInScreen(onSignIn: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("LiberAudīre", style = MaterialTheme.typography.displaySmall, color = Gold)
        Spacer(Modifier.height(8.dp))
        Text("Your Plex audiobooks, at home and on the road.", textAlign = TextAlign.Center, color = Color(0xFFA9B2C3))
        Spacer(Modifier.height(32.dp))
        Button(onClick = onSignIn, Modifier.fillMaxWidth().height(52.dp)) { Text("Sign in with Plex") }
        Spacer(Modifier.height(16.dp))
        Text(
            "Use the Plex account that can see the audiobook library. LiberAudīre never sees your password.",
            style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, color = Color(0xFFA9B2C3),
        )
    }
}

@Composable
private fun WaitingScreen(url: String, onReopen: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(color = Gold)
        Spacer(Modifier.height(24.dp))
        Text("Sign in on the Plex page that just opened, then come back here.", textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onReopen) { Text("Open the Plex page again") }
    }
}

@Composable
private fun PickerScreen(title: String, options: List<String>, onPick: (Int) -> Unit) {
    Column(Modifier.fillMaxSize().padding(top = 64.dp, start = 16.dp, end = 16.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, color = Gold)
        Spacer(Modifier.height(16.dp))
        LazyColumn {
            items(options.size) { i ->
                ListItem(headlineContent = { Text(options[i]) }, modifier = Modifier.clickable { onPick(i) })
            }
        }
    }
}

// ------------------------------------------------------------------ main

@Composable
private fun MainNav(vm: AppViewModel) {
    val nav = rememberNavController()
    NavHost(nav, startDestination = "home") {
        composable("home") { LibraryScreen(vm, nav) }
        composable("book/{key}") { BookScreen(vm, nav, it.arguments?.getString("key")!!) }
        composable("author/{key}/{name}") {
            AuthorScreen(vm, nav, it.arguments?.getString("key")!!, it.arguments?.getString("name").orEmpty())
        }
        composable("player") { PlayerScreen(vm, nav) }
        composable("settings") { SettingsScreen(vm, nav) }
        composable("search") { SearchScreen(vm, nav) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryScreen(vm: AppViewModel, nav: NavHostController) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = listOf("Continue", "Recently added", "Authors", "Downloaded", "All books")
    val continueList by vm.continueListening.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val all by vm.allBooks.collectAsStateWithLifecycle()
    val authors by vm.authors.collectAsStateWithLifecycle()
    val downloads by vm.g.store.downloads.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refreshAll() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(vm.g.prefs.libraryTitle.ifBlank { "Audiobooks" }, color = Gold) },
                actions = {
                    IconButton({ nav.navigate("search") }) { Icon(Icons.Default.Search, "Search") }
                    IconButton({ nav.navigate("settings") }) { Icon(Icons.Default.Settings, "Settings") }
                },
            )
        },
        bottomBar = { MiniPlayer(vm) { nav.navigate("player") } },
    ) { pad ->
        Column(Modifier.padding(pad)) {
            PrimaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
                tabs.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Gold)
            when (tab) {
                0 -> BookGrid(vm, nav, continueList, "Nothing in progress yet. Pick a book to start.")
                1 -> BookGrid(vm, nav, recent, "No books found.")
                2 -> LazyColumn {
                    items(authors) { a ->
                        ListItem(
                            headlineContent = { Text(a.title) },
                            supportingContent = { a.leafCount?.let { Text("$it files") } },
                            modifier = Modifier.clickable {
                                nav.navigate("author/${a.ratingKey}/${java.net.URLEncoder.encode(a.title, "UTF-8")}")
                            },
                        )
                    }
                }
                3 -> BookGrid(
                    vm, nav, downloads.keys.mapNotNull { vm.g.store.book(it)?.book },
                    "No downloads yet. Open a book and tap Download to keep it for offline listening.",
                )
                else -> BookGrid(vm, nav, all, "No books found.")
            }
        }
    }
}

@Composable
fun BookGrid(vm: AppViewModel, nav: NavHostController, books: List<Book>, empty: String) {
    val states by vm.g.downloads.states.collectAsStateWithLifecycle()
    vm.g.store.progress.collectAsStateWithLifecycle().value // recompose when progress changes
    if (books.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text(empty, textAlign = TextAlign.Center, color = Color(0xFFA9B2C3))
        }
        return
    }
    LazyVerticalGrid(GridCells.Adaptive(150.dp), Modifier.fillMaxSize().padding(horizontal = 6.dp)) {
        items(books, key = { it.key }) { b ->
            BookCard(vm, b, bookProgress(vm, b.key), states[b.key]?.complete == true) { nav.navigate("book/${b.key}") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AuthorScreen(vm: AppViewModel, nav: NavHostController, key: String, name: String) {
    var books by remember { mutableStateOf<List<Book>?>(null) }
    LaunchedEffect(key) { books = runCatching { vm.booksBy(key) }.getOrDefault(emptyList()) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(java.net.URLDecoder.decode(name, "UTF-8")) },
                navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        bottomBar = { MiniPlayer(vm) { nav.navigate("player") } },
    ) { pad ->
        Box(Modifier.padding(pad)) {
            books?.let { BookGrid(vm, nav, it, "No books.") } ?: CircularProgressIndicator(Modifier.align(Alignment.Center), color = Gold)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchScreen(vm: AppViewModel, nav: NavHostController) {
    var q by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Book>>(emptyList()) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(q) {
        if (q.length < 2) { results = emptyList(); return@LaunchedEffect }
        delay(350)
        results = vm.search(q)
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth().padding(end = 8.dp).focusRequester(focus),
                        placeholder = { Text("Title or author") }, singleLine = true)
                },
                navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { pad -> Box(Modifier.padding(pad)) { BookGrid(vm, nav, results, if (q.length < 2) "Type to search" else "No matches") } }
}

/** The bar at the bottom while a book is loaded: cover, title, -30 / play / +30. */
@Composable
fun MiniPlayer(vm: AppViewModel, onOpen: () -> Unit) {
    val now by vm.now.collectAsStateWithLifecycle()
    val n = now ?: return
    Surface(color = NavyLight, tonalElevation = 4.dp) {
        Column {
            LinearProgressIndicator(
                progress = { if (n.book.durationMs > 0) n.positionMs.toFloat() / n.book.durationMs else 0f },
                Modifier.fillMaxWidth(), color = Gold,
            )
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(8.dp).padding(bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Cover(vm.artUrl(n.book.book.thumb, 200), Modifier.size(48.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(n.book.book.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${fmtLong(n.book.durationMs - n.positionMs)} left",
                        style = MaterialTheme.typography.bodySmall, color = Color(0xFFA9B2C3),
                    )
                }
                IconButton(vm::skipBack) { Icon(Icons.Default.Replay30, "Back") }
                IconButton(vm::togglePlay) {
                    Icon(if (n.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Play/pause", tint = Gold)
                }
                IconButton(vm::skipForward) { Icon(Icons.Default.Forward30, "Forward") }
            }
        }
    }
}
