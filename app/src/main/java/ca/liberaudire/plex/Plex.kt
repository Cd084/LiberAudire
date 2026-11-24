package ca.liberaudire.plex

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

val PlexJson = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }

class PlexException(message: String) : IOException(message)

// ------------------------------------------------------------------ JSON shapes

@Serializable
data class Envelope(@SerialName("MediaContainer") val mc: MediaContainer)

@Serializable
data class MediaContainer(
    val size: Int = 0,
    val totalSize: Int? = null,
    val machineIdentifier: String? = null,
    @SerialName("Metadata") val metadata: List<Meta> = emptyList(),
    @SerialName("Directory") val directory: List<Dir> = emptyList(),
)

@Serializable
data class Dir(val key: String, val title: String = "", val type: String = "")

@Serializable
data class Meta(
    val ratingKey: String,
    val type: String? = null,
    val title: String = "",
    val titleSort: String? = null,
    val parentTitle: String? = null,
    val grandparentTitle: String? = null,
    val parentRatingKey: String? = null,
    val grandparentRatingKey: String? = null,
    val originalTitle: String? = null,
    val summary: String? = null,
    val year: Int? = null,
    val thumb: String? = null,
    val parentThumb: String? = null,
    val index: Int? = null,
    val parentIndex: Int? = null,
    val duration: Long? = null,
    val viewOffset: Long? = null,
    val viewCount: Int? = null,
    val lastViewedAt: Long? = null,
    val addedAt: Long? = null,
    val leafCount: Int? = null,
    val viewedLeafCount: Int? = null,
    @SerialName("Media") val media: List<Media> = emptyList(),
)

@Serializable
data class Media(
    val bitrate: Int? = null,
    val container: String? = null,
    val duration: Long? = null,
    @SerialName("Part") val parts: List<Part> = emptyList(),
)

@Serializable
data class Part(val key: String, val size: Long? = null, val duration: Long? = null)

@Serializable
data class Pin(val id: Long, val code: String, val authToken: String? = null)

@Serializable
data class PlexUser(
    val uuid: String = "",
    val title: String = "",
    val username: String? = null,
    val thumb: String? = null,
    val home: Boolean = false,
)

@Serializable
data class Connection(
    val protocol: String = "http",
    val uri: String,
    val local: Boolean = false,
    val relay: Boolean = false,
)

@Serializable
data class Resource(
    val name: String = "",
    val clientIdentifier: String = "",
    val provides: String = "",
    val owned: Boolean = false,
    val accessToken: String? = null,
    val presence: Boolean = false,
    val connections: List<Connection> = emptyList(),
)

@Serializable
data class HomeUser(
    val uuid: String,
    val title: String = "",
    val thumb: String? = null,
    val admin: Boolean = false,
    val protected: Boolean = false,
)

@Serializable
private data class HomeUsers(val users: List<HomeUser> = emptyList())

// ------------------------------------------------------------------ app models

/** An audiobook = a Plex album in a music-type library. */
@Serializable
data class Book(
    val key: String,
    val title: String,
    val author: String,
    val authorKey: String? = null,
    val thumb: String? = null,
    val year: Int? = null,
    val summary: String? = null,
    val addedAt: Long? = null,
    val lastViewedAt: Long? = null,
    val fileCount: Int? = null,
)

/** One audio file of a book (often one per chapter). */
@Serializable
data class Track(
    val key: String,
    val title: String,
    val disc: Int,
    val index: Int,
    val durationMs: Long,
    val partKey: String,
    val sizeBytes: Long? = null,
    val bitrateKbps: Int? = null,
    val viewOffset: Long? = null,
    val viewCount: Int? = null,
    val lastViewedAt: Long? = null,
)

@Serializable
data class BookDetail(val book: Book, val tracks: List<Track>, val fetchedAt: Long = System.currentTimeMillis()) {
    val durationMs: Long get() = tracks.sumOf { it.durationMs }

    /** A readable name for part [i]. Many books name every file after the
     *  book itself; then "Part 3" is more useful. */
    fun chapterTitle(i: Int): String {
        val t = tracks.getOrNull(i)?.title?.trim().orEmpty()
        val repeated = tracks.count { it.title.trim() == t } > 1
        return if (t.isBlank() || t.equals(book.title, ignoreCase = true) || repeated) "Part ${i + 1}" else t
    }
    val sizeBytes: Long get() = tracks.sumOf { it.sizeBytes ?: 0L }

    /** Start of track [i] within the whole book. */
    fun offsetOf(i: Int): Long = tracks.take(i).sumOf { it.durationMs }

    /** (track index, position within it) for a position in the whole book. */
    fun locate(bookPosMs: Long): Pair<Int, Long> {
        var rest = bookPosMs.coerceAtLeast(0)
        tracks.forEachIndexed { i, t ->
            if (rest < t.durationMs || i == tracks.lastIndex) return i to rest.coerceAtMost(t.durationMs)
            rest -= t.durationMs
        }
        return 0 to 0L
    }
}

/** A Plex server the signed-in user can use, with every address Plex knows for it. */
@Serializable
data class ServerInfo(
    val name: String,
    val clientId: String,
    val accessToken: String,
    val owned: Boolean,
    val connections: List<Connection>,
)

// ------------------------------------------------------------------ plex.tv

class PlexTv(private val http: OkHttpClient, private val clientId: String) {
    private val base = "https://plex.tv/api/v2"

    private fun req(url: String, token: String? = null) = Request.Builder().url(url).apply {
        plexHeaders(clientId).forEach { (k, v) -> header(k, v) }
        if (token != null) header("X-Plex-Token", token)
    }

    private suspend fun call(r: Request): String = withContext(Dispatchers.IO) {
        http.newCall(r).execute().use { resp ->
            val body = resp.body.string()
            if (resp.code == 401 || resp.code == 403) throw PlexException("PIN")
            if (!resp.isSuccessful) throw PlexException("plex.tv returned ${resp.code}")
            body
        }
    }

    suspend fun createPin(): Pin =
        PlexJson.decodeFromString(call(req("$base/pins?strong=true").post(ByteArray(0).toRequestBody()).build()))

    fun authUrl(pin: Pin): String =
        "https://app.plex.tv/auth#?clientID=$clientId&code=${pin.code}&context%5Bdevice%5D%5Bproduct%5D=LiberAudire"

    suspend fun checkPin(pin: Pin): String? =
        PlexJson.decodeFromString<Pin>(call(req("$base/pins/${pin.id}").build())).authToken?.takeIf { it.isNotEmpty() }

    suspend fun user(token: String): PlexUser = PlexJson.decodeFromString(call(req("$base/user", token).build()))

    /** Servers this account can use (its own and ones shared with it). */
    suspend fun servers(token: String): List<ServerInfo> {
        val list: List<Resource> = PlexJson.decodeFromString(
            call(req("$base/resources?includeHttps=1&includeRelay=1", token).build())
        )
        return list.filter { "server" in it.provides && it.accessToken != null }.map {
            ServerInfo(it.name, it.clientIdentifier, it.accessToken!!, it.owned, it.connections)
        }
    }

    suspend fun homeUsers(token: String): List<HomeUser> =
        PlexJson.decodeFromString<HomeUsers>(call(req("$base/home/users", token).build())).users

    /** Switches to a Plex Home profile; returns that profile's plex.tv token. */
    suspend fun switchHomeUser(token: String, uuid: String, pin: String?): String {
        val url = "$base/home/users/$uuid/switch".toHttpUrl().newBuilder().apply {
            if (pin != null) addQueryParameter("pin", pin)
        }.build()
        val body = call(req(url.toString(), token).post(ByteArray(0).toRequestBody()).build())
        return PlexJson.decodeFromString<PlexUserToken>(body).authToken
            ?: throw PlexException("Plex did not return a token for this profile")
    }

    @Serializable
    private data class PlexUserToken(val authToken: String? = null)
}

fun plexHeaders(clientId: String) = mapOf(
    "Accept" to "application/json",
    "X-Plex-Product" to "LiberAudire",
    "X-Plex-Version" to ca.liberaudire.BuildConfig.VERSION_NAME,
    "X-Plex-Client-Identifier" to clientId,
    "X-Plex-Platform" to "Android",
    "X-Plex-Device-Name" to "LiberAudire (${android.os.Build.MODEL})",
)

// ------------------------------------------------------------------ server

/**
 * Talks to one Plex server. Picks the best reachable address for the current
 * network (home address on Wi-Fi, the remote one on mobile data) and re-picks
 * after network changes or failures.
 */
class PlexServer(
    private val http: OkHttpClient,
    val info: ServerInfo,
    private val clientId: String,
    /** Last address that worked (kept between launches), for cover images offline. */
    @Volatile var lastBaseUrl: String? = null,
    private val onConnected: (String) -> Unit = {},
) {
    @Volatile var baseUrl: String? = null
        private set

    private val probeHttp = http.newBuilder().callTimeout(6, TimeUnit.SECONDS).build()

    /** Tries all addresses at once; local first, then remote, then Plex relay. */
    suspend fun connect(): String? = coroutineScope {
        val ordered = info.connections.sortedBy { (if (it.relay) 2 else if (it.local) 0 else 1) }
        val results = ordered.map { c -> async(Dispatchers.IO) { c.uri.takeIf { reachable(it) } } }.awaitAll()
        baseUrl = results.firstOrNull { it != null }
        baseUrl?.let { lastBaseUrl = it; onConnected(it) }
        baseUrl
    }

    fun invalidate() { baseUrl = null }

    private fun reachable(uri: String): Boolean = try {
        probeHttp.newCall(Request.Builder().url("$uri/identity").header("X-Plex-Token", info.accessToken).build())
            .execute().use { it.isSuccessful }
    } catch (e: IOException) {
        false
    }

    private suspend fun base(): String = baseUrl ?: connect() ?: throw PlexException("Can't reach ${info.name}")

    private suspend fun get(path: String): MediaContainer = withContext(Dispatchers.IO) {
        val b = base()
        try {
            http.newCall(
                Request.Builder().url("$b$path").apply {
                    plexHeaders(clientId).forEach { (k, v) -> header(k, v) }
                    header("X-Plex-Token", info.accessToken)
                }.build()
            ).execute().use { resp ->
                if (!resp.isSuccessful) throw PlexException("${info.name} returned ${resp.code}")
                PlexJson.decodeFromString<Envelope>(resp.body.string()).mc
            }
        } catch (e: IOException) {
            invalidate() // try the other addresses next time
            throw e
        }
    }

    suspend fun musicLibraries(): List<Dir> = get("/library/sections").directory.filter { it.type == "artist" }

    suspend fun recentlyAdded(section: String, n: Int = 60): List<Book> =
        get("/library/sections/$section/all?type=9&sort=addedAt:desc&X-Plex-Container-Start=0&X-Plex-Container-Size=$n")
            .metadata.map { it.toBook() }

    suspend fun recentlyPlayed(section: String, n: Int = 40): List<Book> =
        get("/library/sections/$section/all?type=9&sort=lastViewedAt:desc&lastViewedAt%3E%3E=1&X-Plex-Container-Start=0&X-Plex-Container-Size=$n")
            .metadata.filter { it.lastViewedAt != null }.map { it.toBook() }

    suspend fun allBooks(section: String): List<Book> =
        get("/library/sections/$section/all?type=9&sort=titleSort").metadata.map { it.toBook() }

    suspend fun authors(section: String): List<Meta> = get("/library/sections/$section/all?type=8&sort=titleSort").metadata

    suspend fun booksBy(authorKey: String): List<Book> = get("/library/metadata/$authorKey/children").metadata.map { it.toBook() }

    suspend fun book(key: String): BookDetail {
        val album = get("/library/metadata/$key").metadata.first().toBook()
        val tracks = get("/library/metadata/$key/children").metadata.mapNotNull { m ->
            val media = m.media.firstOrNull()
            val part = media?.parts?.firstOrNull() ?: return@mapNotNull null
            Track(
                key = m.ratingKey,
                title = m.title,
                disc = m.parentIndex ?: 1,
                index = m.index ?: 0,
                durationMs = m.duration ?: part.duration ?: media.duration ?: 0,
                partKey = part.key,
                sizeBytes = part.size,
                bitrateKbps = media.bitrate,
                viewOffset = m.viewOffset,
                viewCount = m.viewCount,
                lastViewedAt = m.lastViewedAt,
            )
        }.sortedWith(compareBy({ it.disc }, { it.index }))
        return BookDetail(album, tracks)
    }

    suspend fun search(section: String, query: String): List<Book> {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        val byTitle = get("/library/sections/$section/all?type=9&title=$q").metadata.map { it.toBook() }
        val byAuthor = get("/library/sections/$section/all?type=8&title=$q").metadata
        val authorBooks = byAuthor.take(5).flatMap { runCatching { booksBy(it.ratingKey) }.getOrDefault(emptyList()) }
        return (byTitle + authorBooks).distinctBy { it.key }
    }

    /** Reports listening progress to Plex (shared with other Plex apps). */
    suspend fun reportTimeline(track: Track, state: String, positionMs: Long) {
        get(
            "/:/timeline?ratingKey=${track.key}&key=%2Flibrary%2Fmetadata%2F${track.key}" +
                "&state=$state&time=$positionMs&duration=${track.durationMs}"
        )
    }

    /** Stream/download URL for a file (token in the URL: the player can't add headers). */
    fun partUrl(partKey: String): String? = baseUrl?.let { "$it$partKey?X-Plex-Token=${info.accessToken}" }

    /** Cover image URL. Uses the last working address when offline, so the
     *  image loader finds its saved copy under the same URL. */
    fun artUrl(thumb: String?, size: Int = 600): String? {
        val b = baseUrl ?: lastBaseUrl ?: return null
        if (thumb == null) return null
        val t = java.net.URLEncoder.encode(thumb, "UTF-8")
        return "$b/photo/:/transcode?width=$size&height=$size&minSize=1&url=$t&X-Plex-Token=${info.accessToken}"
    }
}

private fun Meta.toBook() = Book(
    key = ratingKey,
    title = title,
    author = parentTitle ?: grandparentTitle ?: "Unknown author",
    authorKey = parentRatingKey,
    thumb = thumb ?: parentThumb,
    year = year,
    summary = summary,
    addedAt = addedAt,
    lastViewedAt = lastViewedAt,
    fileCount = leafCount,
)
