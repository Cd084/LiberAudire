package ca.liberaudire.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import ca.liberaudire.App
import ca.liberaudire.Graph
import ca.liberaudire.MainActivity
import ca.liberaudire.data.Progress
import ca.liberaudire.plex.Book
import ca.liberaudire.plex.BookDetail
import ca.liberaudire.plexPartUri
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Plays books in the background, with the lock-screen/notification controls,
 * Bluetooth buttons and Android Auto all talking to it.
 *
 * Media items: "book:<key>" (a whole book; expanded into its files and the
 * resume position when played), "author:<key>" and the browse folders below.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {
    private lateinit var g: Graph
    private lateinit var exo: ExoPlayer
    private lateinit var player: BookPlayer
    private lateinit var session: MediaLibrarySession
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var ticker: Job? = null

    override fun onCreate() {
        super.onCreate()
        g = App.graph(this)
        exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(g.cacheDataSourceFactory))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true) // pause when headphones/car disconnect
            .setWakeMode(C.WAKE_MODE_NETWORK)  // keep CPU + Wi-Fi awake while playing
            // Read ~10 minutes ahead. It goes through the cache, so those
            // minutes are on the phone and survive losing signal.
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(5 * 60_000, 12 * 60_000, 2_500, 5_000)
                    .setTargetBufferBytes(64 * 1024 * 1024)
                    .setPrioritizeTimeOverSizeThresholds(true)
                    .build()
            )
            .build()
        player = BookPlayer(exo, g)
        exo.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) startTicker() else saveProgress(sync = true, state = "paused")
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) markPreviousFileFinished()
                saveProgress(sync = true, state = if (exo.isPlaying) "playing" else "paused")
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) bookFinished()
            }

            override fun onPlaybackParametersChanged(params: PlaybackParameters) {
                val key = player.book?.book?.key ?: return
                g.saveProgress(key) { (it ?: Progress(0, 0, System.currentTimeMillis())).copy(speed = params.speed) }
            }
        })
        session = MediaLibrarySession.Builder(this, player, Callback())
            .setSessionActivity(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            )
            .setMediaButtonPreferences(skipButtons())
            .build()
    }

    /** -30/+30 (or the amounts set in Settings) on the notification, lock screen and car. */
    private fun skipButtons(): List<CommandButton> = listOf(
        CommandButton.Builder(
            if (g.prefs.skipBackSec == 30) CommandButton.ICON_SKIP_BACK_30
            else if (g.prefs.skipBackSec == 15) CommandButton.ICON_SKIP_BACK_15
            else if (g.prefs.skipBackSec == 10) CommandButton.ICON_SKIP_BACK_10 else CommandButton.ICON_SKIP_BACK
        ).setPlayerCommand(Player.COMMAND_SEEK_BACK).setSlots(CommandButton.SLOT_BACK)
            .setDisplayName("Back ${g.prefs.skipBackSec} seconds").build(),
        CommandButton.Builder(
            if (g.prefs.skipForwardSec == 30) CommandButton.ICON_SKIP_FORWARD_30
            else if (g.prefs.skipForwardSec == 15) CommandButton.ICON_SKIP_FORWARD_15
            else if (g.prefs.skipForwardSec == 10) CommandButton.ICON_SKIP_FORWARD_10 else CommandButton.ICON_SKIP_FORWARD
        ).setPlayerCommand(Player.COMMAND_SEEK_FORWARD).setSlots(CommandButton.SLOT_FORWARD)
            .setDisplayName("Forward ${g.prefs.skipForwardSec} seconds").build(),
    )

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away while paused stops the service; while playing it carries on.
        if (!exo.playWhenReady || exo.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        saveProgress(sync = true, state = "stopped")
        session.release()
        exo.release()
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------ progress

    private var lastTick = 0L
    private var ticks = 0

    private fun startTicker() {
        ticker?.cancel()
        lastTick = System.currentTimeMillis()
        ticker = scope.launch {
            while (isActive) {
                delay(15_000)
                if (!exo.isPlaying) break
                // Locally every 15 s; to Plex every 30 s.
                saveProgress(sync = ++ticks % 2 == 0, state = "playing")
            }
        }
    }

    private fun saveProgress(sync: Boolean, state: String) {
        val detail = player.book ?: return
        val key = detail.book.key
        val now = System.currentTimeMillis()
        val listened = if (lastTick > 0 && (exo.isPlaying || state == "paused")) (now - lastTick).coerceIn(0, 60_000) else 0
        lastTick = if (exo.isPlaying) now else 0
        val idx = exo.currentMediaItemIndex
        val pos = exo.currentPosition
        g.saveProgress(key) { old ->
            Progress(
                trackIndex = idx, positionMs = pos, updatedAt = now, synced = false,
                finished = false, listenedMs = (old?.listenedMs ?: 0) + listened,
                speed = exo.playbackParameters.speed,
            )
        }
        maybeDownloadWholeBook(detail)
        if (sync) {
            val track = detail.tracks.getOrNull(idx) ?: return
            val server = g.server.value ?: return
            g.scope.launch {
                runCatching { server.reportTimeline(track, state, pos) }.onSuccess {
                    g.store.progress(key)?.let { p ->
                        if (p.trackIndex == idx && p.positionMs == pos) g.store.setProgress(key, p.copy(synced = true))
                    }
                }
            }
        }
    }

    /** After N minutes (Settings, default 10) of a book, download all of it. */
    private fun maybeDownloadWholeBook(detail: BookDetail) {
        val min = g.prefs.autoDownloadAfterMin
        if (min <= 0) return
        val p = g.store.progress(detail.book.key) ?: return
        if (p.listenedMs >= min * 60_000L && detail.book.key !in g.store.downloads.value) {
            g.downloads.downloadBook(detail, pinned = false)
        }
    }

    private fun markPreviousFileFinished() {
        val detail = player.book ?: return
        val prev = detail.tracks.getOrNull(exo.currentMediaItemIndex - 1) ?: return
        val server = g.server.value ?: return
        g.scope.launch { runCatching { server.reportTimeline(prev, "stopped", prev.durationMs) } }
    }

    private fun bookFinished() {
        val detail = player.book ?: return
        val last = detail.tracks.lastOrNull() ?: return
        g.saveProgress(detail.book.key) {
            (it ?: Progress(0, 0, 0)).copy(
                trackIndex = detail.tracks.lastIndex, positionMs = last.durationMs,
                updatedAt = System.currentTimeMillis(), synced = false, finished = true,
            )
        }
        g.server.value?.let { s -> g.scope.launch { runCatching { s.reportTimeline(last, "stopped", last.durationMs) } } }
        g.downloads.onBookFinished(detail.book.key)
    }

    // ------------------------------------------------------------ media items

    private suspend fun loadBook(key: String): BookDetail? {
        val fresh = g.server.value?.let { s -> runCatching { s.book(key) }.getOrNull() }
        if (fresh != null) g.store.saveBook(fresh)
        return fresh ?: g.store.book(key)
    }

    private fun bookItem(b: Book): MediaItem = MediaItem.Builder()
        .setMediaId("book:${b.key}")
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(b.title)
                .setArtist(b.author)
                .setArtworkUri(g.server.value?.artUrl(b.thumb, 400)?.toUri())
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
                .build()
        ).build()

    private fun folder(id: String, title: String) = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder().setTitle(title).setIsBrowsable(true).setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_AUDIO_BOOKS).build()
        ).build()

    private fun trackItems(detail: BookDetail): List<MediaItem> {
        val art = g.server.value?.artUrl(detail.book.thumb, 600)?.toUri()
        return detail.tracks.mapIndexed { i, t ->
            MediaItem.Builder()
                .setMediaId("track:${detail.book.key}:$i")
                .setUri(plexPartUri(t.partKey))
                .setCustomCacheKey(cacheKey(t.partKey))
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(if (detail.tracks.size > 1) detail.chapterTitle(i) else detail.book.title)
                        .setArtist(detail.book.author)
                        .setAlbumTitle(detail.book.title)
                        .setAlbumArtist(detail.book.author)
                        .setArtworkUri(art)
                        .setTrackNumber(i + 1)
                        .setTotalTrackCount(detail.tracks.size)
                        .setDurationMs(t.durationMs)
                        .setIsBrowsable(false)
                        .setIsPlayable(true)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER)
                        .build()
                ).build()
        }
    }

    /** A book as files + where to resume, ready for the player. */
    private suspend fun prepare(bookKey: String): MediaSession.MediaItemsWithStartPosition? {
        val detail = loadBook(bookKey) ?: return null
        if (detail.tracks.isEmpty()) return null
        player.book = detail
        val (i, pos) = g.resumePosition(detail)
        val speed = g.store.progress(bookKey)?.speed ?: g.prefs.defaultSpeed
        exo.setPlaybackSpeed(speed)
        g.downloads.trimStreamingCache(keepBook = bookKey)
        return MediaSession.MediaItemsWithStartPosition(trackItems(detail), i, pos)
    }

    private suspend fun continueListening(): List<Book> {
        val local = g.store.progress.value.entries
            .filter { !it.value.finished }
            .sortedByDescending { it.value.updatedAt }
            .mapNotNull { g.store.book(it.key)?.book }
        val section = g.prefs.libraryKey
        val remote = section?.let { s -> g.server.value?.let { runCatching { it.recentlyPlayed(s) }.getOrNull() } }
        return (local + (remote ?: emptyList())).distinctBy { it.key }.take(30)
    }

    private inner class Callback : MediaLibrarySession.Callback {
        override fun onGetLibraryRoot(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            com.google.common.util.concurrent.Futures.immediateFuture(LibraryResult.ofItem(folder("root", "LiberAudīre"), params))

        override fun onGetChildren(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String,
            page: Int, pageSize: Int, params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            val section = g.prefs.libraryKey
            val s = g.server.value
            val items: List<MediaItem> = when {
                parentId == "root" -> listOf(
                    folder("continue", "Continue listening"),
                    folder("downloads", "Downloaded"),
                    folder("recent", "Recently added"),
                    folder("authors", "Authors"),
                )
                parentId == "continue" -> continueListening().map(::bookItem)
                parentId == "downloads" -> g.store.downloads.value.keys.mapNotNull { g.store.book(it)?.book }.map(::bookItem)
                parentId == "recent" -> {
                    val books = section?.let { sec -> s?.let { runCatching { it.recentlyAdded(sec) }.getOrNull() } }
                    (books?.also { g.store.saveList("recent", it) } ?: g.store.list("recent")).map(::bookItem)
                }
                parentId == "authors" -> section?.let { sec ->
                    s?.let { runCatching { it.authors(sec) }.getOrNull() }
                }?.map { folder("author:${it.ratingKey}", it.title) } ?: emptyList()
                parentId.startsWith("author:") ->
                    s?.let { runCatching { it.booksBy(parentId.removePrefix("author:")) }.getOrNull() }?.map(::bookItem) ?: emptyList()
                else -> emptyList()
            }
            val from = (page * pageSize).coerceAtMost(items.size)
            LibraryResult.ofItemList(ImmutableList.copyOf(items.subList(from, (from + pageSize).coerceAtMost(items.size))), params)
        }

        override fun onGetItem(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = scope.future {
            val book = mediaId.removePrefix("book:").let { g.store.book(it)?.book ?: loadBook(it)?.book }
            if (book != null) LibraryResult.ofItem(bookItem(book), null) else LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
        }

        /** Playing a "book:<key>" item plays the whole book from where you left off. */
        override fun onSetMediaItems(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>,
            startIndex: Int, startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
            val bookId = mediaItems.firstOrNull()?.mediaId?.takeIf { it.startsWith("book:") }
            bookId?.let { prepare(it.removePrefix("book:")) }
                ?: MediaSession.MediaItemsWithStartPosition(mediaItems, startIndex, startPositionMs)
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = scope.future {
            val bookId = mediaItems.firstOrNull()?.mediaId?.takeIf { it.startsWith("book:") }
            bookId?.let { prepare(it.removePrefix("book:"))?.mediaItems?.toMutableList() } ?: mediaItems
        }

        /** "Resume" from Bluetooth, the car or the system media controls: the last book played. */
        override fun onPlaybackResumption(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
            val last = g.store.progress.value.entries.filter { !it.value.finished }.maxByOrNull { it.value.updatedAt }?.key
            last?.let { prepare(it) } ?: throw UnsupportedOperationException("Nothing to resume")
        }
    }

    companion object {
        /** What the app hands the player to play a whole book from where you left off. */
        fun bookMediaItem(key: String): MediaItem = MediaItem.Builder().setMediaId("book:$key").build()
    }
}
