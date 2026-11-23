package ca.liberaudire.ui

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import ca.liberaudire.App
import ca.liberaudire.data.Bookmark
import ca.liberaudire.playback.PlaybackService
import ca.liberaudire.plex.Book
import ca.liberaudire.plex.BookDetail
import ca.liberaudire.plex.Dir
import ca.liberaudire.plex.HomeUser
import ca.liberaudire.plex.Meta
import ca.liberaudire.plex.PlexServer
import ca.liberaudire.plex.ServerInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** What the player is doing, for the screens. Positions are in the whole book. */
data class NowPlaying(
    val book: BookDetail,
    val chapterIndex: Int,
    val isPlaying: Boolean,
    val positionMs: Long,
    val speed: Float,
    val buffering: Boolean,
) {
    val chapterStartMs get() = book.offsetOf(chapterIndex)
    val chapterLengthMs get() = book.tracks.getOrNull(chapterIndex)?.durationMs ?: 0L
}

sealed interface Setup {
    data object SignedOut : Setup
    data class WaitingForPlex(val authUrl: String) : Setup
    data class PickServer(val servers: List<ServerInfo>) : Setup
    data class PickLibrary(val libraries: List<Dir>) : Setup
    data object Ready : Setup
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val g = App.graph(app)
    private val prefs = g.prefs

    private val _setup = MutableStateFlow<Setup>(if (prefs.libraryKey != null && prefs.server != null) Setup.Ready else Setup.SignedOut)
    val setup: StateFlow<Setup> = _setup

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error
    fun clearError() { _error.value = null }

    // ------------------------------------------------------------ sign-in

    private var pinJob: Job? = null

    fun signIn(openBrowser: (String) -> Unit) {
        pinJob?.cancel()
        pinJob = viewModelScope.launch {
            try {
                val pin = g.plexTv.createPin()
                val url = g.plexTv.authUrl(pin)
                _setup.value = Setup.WaitingForPlex(url)
                openBrowser(url)
                repeat(300) { // ~10 minutes
                    delay(2000)
                    val token = g.plexTv.checkPin(pin)
                    if (token != null) {
                        prefs.ownerToken = token
                        useAccount(token)
                        return@launch
                    }
                }
                _setup.value = Setup.SignedOut
                _error.value = "Sign-in timed out. Please try again."
            } catch (e: Exception) {
                _setup.value = Setup.SignedOut
                _error.value = "Couldn't sign in: ${e.message}"
            }
        }
    }

    /** After sign-in or a profile switch: which servers can this account use? */
    private suspend fun useAccount(token: String, keepServerId: String? = null) {
        prefs.accountToken = token
        val user = g.plexTv.user(token)
        prefs.userTitle = user.title
        prefs.userThumb = user.thumb
        prefs.userHome = user.home || prefs.userHome
        val servers = g.plexTv.servers(token)
        val keep = servers.firstOrNull { it.clientId == keepServerId }
        when {
            keep != null -> { pickServer(keep, keepLibrary = true); return }
            servers.isEmpty() -> {
                _setup.value = Setup.SignedOut
                _error.value = "${user.title} doesn't have access to any Plex server."
            }
            servers.size == 1 -> pickServer(servers.first())
            else -> _setup.value = Setup.PickServer(servers.sortedByDescending { it.owned })
        }
    }

    fun pickServer(info: ServerInfo, keepLibrary: Boolean = false) {
        viewModelScope.launch {
            val server = g.newServer(info)
            if (server.connect() == null) {
                _error.value = "Can't reach ${info.name} right now."
                return@launch
            }
            g.useServer(server)
            if (keepLibrary && prefs.libraryKey != null) { _setup.value = Setup.Ready; refreshAll(); return@launch }
            val libs = runCatching { server.musicLibraries() }.getOrElse {
                _error.value = "Couldn't read libraries: ${it.message}"; return@launch
            }
            val books = libs.filter { it.title.contains("book", ignoreCase = true) }
            if (books.size == 1) pickLibrary(books.first()) else _setup.value = Setup.PickLibrary(libs)
        }
    }

    fun pickLibrary(dir: Dir) {
        prefs.libraryKey = dir.key
        prefs.libraryTitle = dir.title
        _setup.value = Setup.Ready
        refreshAll()
    }

    fun changeServer() {
        val token = prefs.accountToken ?: return signOut()
        viewModelScope.launch { runCatching { useAccount(token) }.onFailure { _error.value = it.message } }
    }

    fun signOut() {
        controller?.stop()
        controller?.clearMediaItems()
        prefs.signOut()
        g.store.clearUserData()
        g.useServer(null)
        _setup.value = Setup.SignedOut
    }

    // ------------------------------------------------------------ Plex Home profiles

    suspend fun homeUsers(): List<HomeUser> = g.plexTv.homeUsers(prefs.ownerToken ?: error("Not signed in"))

    /** Switches profile (PIN if needed). Returns an error message, or null when done. */
    suspend fun switchProfile(user: HomeUser, pin: String?): String? = try {
        val token = g.plexTv.switchHomeUser(prefs.ownerToken ?: return "Not signed in", user.uuid, pin)
        controller?.pause()
        g.store.clearUserData()
        useAccount(token, keepServerId = prefs.server?.clientId)
        null
    } catch (e: Exception) {
        if (e.message == "PIN") "Wrong PIN" else e.message ?: "Couldn't switch profile"
    }

    // ------------------------------------------------------------ library lists

    val continueListening = MutableStateFlow(g.store.list("continue"))
    val recent = MutableStateFlow(g.store.list("recent"))
    val allBooks = MutableStateFlow(g.store.list("all"))
    val authors = MutableStateFlow<List<Meta>>(emptyList())
    val loading = MutableStateFlow(false)

    fun refreshAll() {
        val section = prefs.libraryKey ?: return
        val s = g.server.value ?: return
        viewModelScope.launch {
            loading.value = true
            runCatching { s.recentlyPlayed(section) }.onSuccess { remote ->
                val local = g.store.progress.value.entries.filter { !it.value.finished }
                    .sortedByDescending { it.value.updatedAt }.mapNotNull { g.store.book(it.key)?.book }
                val merged = (local + remote).distinctBy { it.key }
                continueListening.value = merged; g.store.saveList("continue", merged)
            }
            runCatching { s.recentlyAdded(section) }.onSuccess { recent.value = it; g.store.saveList("recent", it) }
            runCatching { s.authors(section) }.onSuccess { authors.value = it }
            runCatching { s.allBooks(section) }.onSuccess { allBooks.value = it; g.store.saveList("all", it) }
            loading.value = false
            g.syncProgress()
        }
    }

    suspend fun booksBy(authorKey: String): List<Book> = g.server.value?.booksBy(authorKey) ?: emptyList()

    suspend fun search(q: String): List<Book> {
        val section = prefs.libraryKey ?: return emptyList()
        return runCatching { g.server.value?.search(section, q) }.getOrNull()
            ?: allBooks.value.filter { it.title.contains(q, true) || it.author.contains(q, true) }
    }

    /** Book details, from Plex when online, else the saved copy. */
    suspend fun book(key: String): BookDetail? {
        val fresh = g.server.value?.let { runCatching { it.book(key) }.getOrNull() }
        if (fresh != null) g.store.saveBook(fresh)
        return fresh ?: g.store.book(key)
    }

    fun artUrl(thumb: String?, size: Int = 400) = g.server.value?.artUrl(thumb, size)

    // ------------------------------------------------------------ player

    private var controller: MediaController? = null
    private val _now = MutableStateFlow<NowPlaying?>(null)
    val now: StateFlow<NowPlaying?> = _now

    init {
        viewModelScope.launch {
            val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
            val c = MediaController.Builder(app, token).buildAsync().await()
            controller = c
            c.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = updateNow()
            })
            updateNow()
            while (isActive) { delay(500); if (c.isPlaying) updateNow() }
        }
    }

    private fun updateNow() {
        val c = controller ?: return
        val bookKey = c.currentMediaItem?.mediaId?.split(":")?.getOrNull(1)
        val detail = bookKey?.let { k -> _now.value?.book?.takeIf { it.book.key == k } ?: g.store.book(k) }
        _now.value = detail?.let {
            NowPlaying(
                book = it,
                chapterIndex = c.currentMediaItemIndex,
                isPlaying = c.isPlaying,
                positionMs = it.offsetOf(c.currentMediaItemIndex) + c.currentPosition,
                speed = c.playbackParameters.speed,
                buffering = c.playbackState == Player.STATE_BUFFERING,
            )
        }
    }

    fun play(bookKey: String, fromChapter: Int? = null) {
        val c = controller ?: return
        if (_now.value?.book?.book?.key == bookKey && fromChapter == null) { c.play(); return }
        c.setMediaItem(PlaybackService.bookMediaItem(bookKey))
        c.prepare()
        if (fromChapter != null) {
            viewModelScope.launch {
                // Wait for the book to be expanded into its files, then jump.
                for (attempt in 0 until 30) {
                    if (c.currentMediaItem?.mediaId?.startsWith("track:") == true) break
                    delay(100)
                }
                c.seekTo(fromChapter, 0)
                c.play()
            }
        } else {
            c.play()
        }
    }

    fun togglePlay() { controller?.let { if (it.isPlaying) it.pause() else it.play() } }
    fun skipBack() { controller?.seekBack() }
    fun skipForward() { controller?.seekForward() }

    /** Moves through the whole book (crossing files) by [deltaMs]. */
    fun seekBy(deltaMs: Long) { _now.value?.let { seekTo(it.positionMs + deltaMs) } }

    fun seekTo(bookPosMs: Long) {
        val n = _now.value ?: return
        val (i, pos) = n.book.locate(bookPosMs.coerceIn(0, n.book.durationMs))
        controller?.seekTo(i, pos)
    }

    fun chapter(delta: Int) {
        val n = _now.value ?: return
        val c = controller ?: return
        // "Previous" restarts the chapter unless you're within its first 3 seconds.
        if (delta < 0 && n.positionMs - n.chapterStartMs > 3000) c.seekTo(n.chapterIndex, 0)
        else c.seekTo((n.chapterIndex + delta).coerceIn(0, n.book.tracks.lastIndex), 0)
    }

    fun setSpeed(speed: Float) { controller?.setPlaybackSpeed(speed) }

    // ------------------------------------------------------------ bookmarks

    fun addBookmark(note: String = "") {
        val n = _now.value ?: return
        val key = n.book.book.key
        val (i, pos) = n.book.locate(n.positionMs)
        val list = g.store.bookmarks.value[key].orEmpty()
        g.store.setBookmarks(key, list + Bookmark(System.currentTimeMillis(), i, pos, note))
    }

    fun deleteBookmark(bookKey: String, id: Long) =
        g.store.setBookmarks(bookKey, g.store.bookmarks.value[bookKey].orEmpty().filter { it.id != id })

    fun jumpTo(bookKey: String, b: Bookmark) {
        if (_now.value?.book?.book?.key != bookKey) {
            play(bookKey, fromChapter = b.trackIndex)
            viewModelScope.launch { delay(800); controller?.seekTo(b.trackIndex, b.positionMs) }
        } else {
            controller?.seekTo(b.trackIndex, b.positionMs)
            controller?.play()
        }
    }

    // ------------------------------------------------------------ downloads

    fun download(detail: BookDetail) = g.downloads.downloadBook(detail, pinned = true)
    fun removeDownload(bookKey: String) = g.downloads.removeBook(bookKey)

    fun openInBrowser(url: String) {
        getApplication<Application>().startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun onCleared() {
        controller?.release()
        super.onCleared()
    }
}
