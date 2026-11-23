package ca.liberaudire

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.DownloadManager
import ca.liberaudire.data.Prefs
import ca.liberaudire.data.Progress
import ca.liberaudire.data.Store
import ca.liberaudire.playback.Downloads
import ca.liberaudire.plex.BookDetail
import ca.liberaudire.plex.PlexServer
import ca.liberaudire.plex.PlexTv
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class App : Application() {
    lateinit var graph: Graph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = Graph(this)
    }

    companion object {
        fun graph(context: Context): Graph = (context.applicationContext as App).graph
    }
}

/** Placeholder URI for a Plex file; see Graph.upstream. */
fun plexPartUri(partKey: String): String = "plex://part$partKey"

/** App-wide singletons. */
@OptIn(UnstableApi::class)
class Graph(val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val prefs = Prefs(context)
    val store = Store(context)

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    val plexTv = PlexTv(http, prefs.clientId)

    /** A connection to [info], remembering the address that works. */
    fun newServer(info: ca.liberaudire.plex.ServerInfo) = PlexServer(
        http, info, prefs.clientId,
        lastBaseUrl = prefs.lastBaseUrl.takeIf { prefs.server?.clientId == info.clientId },
    ) { prefs.lastBaseUrl = it }

    private val _server = MutableStateFlow(prefs.server?.let { newServer(it) })
    /** The Plex server in use (null until signed in and chosen). */
    val server: StateFlow<PlexServer?> = _server

    fun useServer(server: PlexServer?) {
        prefs.server = server?.info
        _server.value = server
    }

    // ---- network ----
    private val _online = MutableStateFlow(false)
    val online: StateFlow<Boolean> = _online
    private val _unmetered = MutableStateFlow(false)
    /** On Wi-Fi (or another network that isn't charged by the MB). */
    val unmetered: StateFlow<Boolean> = _unmetered

    // ---- audio cache (streaming + downloads share it) ----
    private val databaseProvider = StandaloneDatabaseProvider(context)

    /** Kept in app files (not the system cache) so Android never clears downloads.
     *  Nothing is evicted automatically: Downloads manages what stays. */
    val cache = SimpleCache(File(context.filesDir, "audio"), NoOpCacheEvictor(), databaseProvider)

    /**
     * Player and downloads use "plex://part/library/parts/..." placeholders,
     * resolved to the server's current best address when each file is opened,
     * so switching from home Wi-Fi to mobile data mid-book keeps working.
     */
    private val upstream = ResolvingDataSource.Factory(OkHttpDataSource.Factory(http)) { spec ->
        if (spec.uri.scheme != "plex") return@Factory spec
        val s = server.value ?: return@Factory spec
        val base = s.baseUrl ?: runBlocking { s.connect() } ?: return@Factory spec
        // Identity in the URL too, so Plex shows "LiberAudire" in its Dashboard / Tautulli.
        val id = java.net.URLEncoder.encode(prefs.clientId, "UTF-8")
        val device = java.net.URLEncoder.encode("LiberAudire (${android.os.Build.MODEL})", "UTF-8")
        spec.withUri(
            ("$base${spec.uri.path}?X-Plex-Token=${s.info.accessToken}&X-Plex-Client-Identifier=$id" +
                "&X-Plex-Product=LiberAudire&X-Plex-Device-Name=$device&X-Plex-Platform=Android").toUri()
        )
    }

    /** Plays from the cache when the bytes are there, streams (and caches) the rest. */
    val cacheDataSourceFactory: CacheDataSource.Factory = CacheDataSource.Factory()
        .setCache(cache)
        .setUpstreamDataSourceFactory(upstream)
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    val downloadManager = DownloadManager(context, databaseProvider, cache, upstream, Executors.newFixedThreadPool(3))
        .apply { maxParallelDownloads = 2 }

    val downloads = Downloads(this)

    init {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val wasOnline = _online.value
                val wasUnmetered = _unmetered.value
                _online.value = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                _unmetered.value = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                if (!wasOnline && _online.value) onBackOnline()
                if (!wasUnmetered && _unmetered.value) downloads.startWaiting()
            }

            override fun onLost(network: Network) {
                _online.value = false
                _unmetered.value = false
                // Another network (e.g. mobile data instead of home Wi-Fi) may need another address.
                server.value?.invalidate()
            }
        })
    }

    private fun onBackOnline() {
        server.value?.invalidate()
        scope.launch { syncProgress() }
    }

    // ---- progress sync ----

    /** Sends progress that couldn't reach Plex while offline. */
    suspend fun syncProgress() {
        val s = server.value ?: return
        store.progress.value.filterValues { !it.synced }.forEach { (bookKey, p) ->
            val detail = store.book(bookKey) ?: return@forEach
            val track = detail.tracks.getOrNull(p.trackIndex) ?: return@forEach
            runCatching { s.reportTimeline(track, "stopped", p.positionMs) }.onSuccess {
                store.setProgress(bookKey, p.copy(synced = true))
            }
        }
    }

    /**
     * Where to resume a book: the newer of this phone's saved position and
     * Plex's (so progress from other Plex apps/devices is picked up).
     */
    fun resumePosition(detail: BookDetail): Pair<Int, Long> {
        val local = store.progress(detail.book.key)
        var plex: Pair<Int, Long>? = null
        var plexAt = 0L
        detail.tracks.forEachIndexed { i, t ->
            val at = (t.lastViewedAt ?: 0L) * 1000
            if (at > plexAt) {
                plexAt = at
                plex = when {
                    (t.viewOffset ?: 0) > 0 -> i to t.viewOffset!!
                    (t.viewCount ?: 0) > 0 && i < detail.tracks.lastIndex -> i + 1 to 0L
                    else -> i to 0L
                }
            }
        }
        val p = plex
        if (local == null) return p ?: (0 to 0L)
        if (p != null && plexAt > local.updatedAt + 30_000) {
            // Plex doesn't store positions in a file's first minute, so a
            // "newer" Plex position up to ~a minute behind ours is that rule,
            // not listening elsewhere: keep ours.
            val plexPos = detail.offsetOf(p.first) + p.second
            val localPos = detail.offsetOf(local.trackIndex) + local.positionMs
            val justDropped = plexPos <= localPos && localPos - plexPos <= 65_000
            if (!justDropped) return p
        }
        return if (local.finished) 0 to 0L else local.trackIndex to local.positionMs
    }

    fun saveProgress(bookKey: String, transform: (Progress?) -> Progress) {
        store.setProgress(bookKey, transform(store.progress(bookKey)))
    }
}
