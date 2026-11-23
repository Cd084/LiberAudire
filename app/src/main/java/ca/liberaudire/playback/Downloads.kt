package ca.liberaudire.playback

import android.app.Notification
import android.content.Context
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.PlatformScheduler
import androidx.media3.exoplayer.scheduler.Requirements
import androidx.media3.exoplayer.scheduler.Scheduler
import ca.liberaudire.Graph
import ca.liberaudire.R
import ca.liberaudire.plexPartUri
import ca.liberaudire.data.DownloadRecord
import ca.liberaudire.plex.BookDetail
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Same key whatever address the server was reached on, so cached audio is
 *  found again on Wi-Fi, mobile data or offline. */
fun cacheKey(partKey: String) = "plexpart:$partKey"

/** How far a book's download has got. */
data class BookDownloadState(val done: Int, val total: Int, val waitingForWifi: Boolean, val failed: Boolean) {
    val complete get() = total > 0 && done == total
}

@OptIn(UnstableApi::class)
class Downloads(private val g: Graph) {
    private val _states = MutableStateFlow<Map<String, BookDownloadState>>(emptyMap())
    /** Download progress per book key, for the UI. */
    val states: StateFlow<Map<String, BookDownloadState>> = _states

    init {
        g.downloadManager.addListener(object : DownloadManager.Listener {
            override fun onDownloadChanged(manager: DownloadManager, download: Download, finalException: Exception?) = refresh()
            override fun onDownloadRemoved(manager: DownloadManager, download: Download) = refresh()
            override fun onInitialized(downloadManager: DownloadManager) = refresh()
        })
        // Downloads may run on any network; whole-book auto-downloads wait for
        // Wi-Fi ourselves (see [downloadBook]) so manual ones aren't blocked.
        g.downloadManager.requirements = Requirements(Requirements.NETWORK)
        g.scope.launch { g.store.waitingForWifi.collect { refresh() } }
    }

    fun refresh() {
        val byId = mutableMapOf<String, Download>()
        runCatching {
            g.downloadManager.downloadIndex.getDownloads().use { c -> while (c.moveToNext()) byId[c.download.request.id] = c.download }
        }
        val waiting = g.store.waitingForWifi.value.toSet()
        _states.value = g.store.downloads.value.mapValues { (key, r) ->
            val ds = r.trackIds.mapNotNull { byId[it] }
            BookDownloadState(
                done = ds.count { it.state == Download.STATE_COMPLETED },
                total = r.trackIds.size,
                waitingForWifi = key in waiting,
                failed = ds.any { it.state == Download.STATE_FAILED },
            )
        } + waiting.filter { it !in g.store.downloads.value }.associateWith { BookDownloadState(0, 0, true, false) }
    }

    fun isDownloaded(bookKey: String) = _states.value[bookKey]?.complete == true

    /**
     * Downloads a whole book. [pinned] = chosen by the user (never removed to
     * make room); otherwise it's the automatic "after 10 minutes" download,
     * which waits for Wi-Fi when that setting is on.
     */
    fun downloadBook(detail: BookDetail, pinned: Boolean) {
        val key = detail.book.key
        val existing = g.store.downloads.value[key]
        if (existing != null) {
            if (pinned && !existing.pinned) g.store.setDownload(existing.copy(pinned = true), key)
            return
        }
        if (!pinned && g.prefs.wifiOnlyFullDownloads && !g.unmetered.value) {
            g.store.setWaitingForWifi(g.store.waitingForWifi.value + key)
            return
        }
        g.store.saveBook(detail)
        makeRoomFor(detail.sizeBytes, keep = key)
        val ids = detail.tracks.map { t ->
            val id = cacheKey(t.partKey)
            val req = DownloadRequest.Builder(id, plexPartUri(t.partKey).toUri()).setCustomCacheKey(id).setData(key.toByteArray()).build()
            DownloadService.sendAddDownload(g.context, BookDownloadService::class.java, req, false)
            id
        }
        g.store.setDownload(DownloadRecord(key, pinned, ids, detail.sizeBytes), key)
        g.store.setWaitingForWifi(g.store.waitingForWifi.value - key)
        refresh()
    }

    fun removeBook(bookKey: String) {
        g.store.downloads.value[bookKey]?.trackIds?.forEach {
            DownloadService.sendRemoveDownload(g.context, BookDownloadService::class.java, it, false)
        }
        g.store.setDownload(null, bookKey)
        g.store.setWaitingForWifi(g.store.waitingForWifi.value - bookKey)
        refresh()
    }

    /** Starts automatic downloads that were waiting for Wi-Fi. */
    fun startWaiting() {
        g.store.waitingForWifi.value.forEach { key ->
            val detail = g.store.book(key) ?: return@forEach
            g.store.setWaitingForWifi(g.store.waitingForWifi.value - key)
            downloadBook(detail, pinned = false)
        }
    }

    /** Keeps automatic downloads under the storage limit by removing the
     *  least recently played automatic ones first (never pinned books). */
    private fun makeRoomFor(bytes: Long, keep: String) {
        val limit = g.prefs.storageLimitGb * 1_000_000_000L
        if (limit <= 0) return
        val progress = g.store.progress.value
        var used = g.store.downloads.value.values.filter { !it.pinned }.sumOf { it.sizeBytes } + bytes
        g.store.downloads.value.values
            .filter { !it.pinned && it.bookKey != keep }
            .sortedBy { progress[it.bookKey]?.updatedAt ?: 0L }
            .forEach { if (used > limit) { removeBook(it.bookKey); used -= it.sizeBytes } }
    }

    /** Called when a book is finished: removes its download if that's switched on. */
    fun onBookFinished(bookKey: String) {
        if (g.prefs.deleteFinished) removeBook(bookKey)
    }

    /**
     * Streaming also caches audio (that's what keeps ~10 minutes ahead on the
     * phone). Clears streamed-but-not-downloaded audio of books other than
     * [keepBook] once it passes [maxBytes].
     */
    fun trimStreamingCache(keepBook: String?, maxBytes: Long = 500_000_000L) {
        val downloaded = g.store.downloads.value.values.flatMap { it.trackIds }.toSet()
        val keepKeys = keepBook?.let { g.store.book(it) }?.tracks?.map { cacheKey(it.partKey) }?.toSet() ?: emptySet()
        val streamed = g.cache.keys.filter { it !in downloaded && it !in keepKeys }
        var total = streamed.sumOf { g.cache.getCachedBytes(it, 0, Long.MAX_VALUE) }
        for (k in streamed) {
            if (total <= maxBytes) break
            total -= g.cache.getCachedBytes(k, 0, Long.MAX_VALUE)
            g.cache.removeResource(k)
        }
    }
}

/** Runs downloads in the background (a foreground service with a progress notification). */
@OptIn(UnstableApi::class)
class BookDownloadService : DownloadService(
    NOTIFICATION_ID,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    CHANNEL_ID,
    R.string.download_channel,
    R.string.download_channel_description,
) {
    private val helper by lazy { DownloadNotificationHelper(this, CHANNEL_ID) }

    override fun getDownloadManager(): DownloadManager = ca.liberaudire.App.graph(this).downloadManager

    override fun getScheduler(): Scheduler = PlatformScheduler(this, JOB_ID)

    override fun getForegroundNotification(downloads: MutableList<Download>, notMetRequirements: Int): Notification =
        helper.buildProgressNotification(this, R.drawable.ic_notification, null, null, downloads, notMetRequirements)

    companion object {
        const val NOTIFICATION_ID = 2
        const val JOB_ID = 2
        const val CHANNEL_ID = "downloads"
    }
}
