package ca.liberaudire.data

import android.content.Context
import ca.liberaudire.plex.Book
import ca.liberaudire.plex.BookDetail
import ca.liberaudire.plex.PlexJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File

/** Where the listener is in a book, and whether Plex has been told yet. */
@Serializable
data class Progress(
    val trackIndex: Int,
    val positionMs: Long,
    val updatedAt: Long,
    val synced: Boolean = false,
    val finished: Boolean = false,
    /** Total listening time, for "download the whole book after N minutes". */
    val listenedMs: Long = 0,
    val speed: Float? = null,
)

@Serializable
data class Bookmark(
    val id: Long,
    val trackIndex: Int,
    val positionMs: Long,
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

/** A book whose files are (being) downloaded for offline listening. */
@Serializable
data class DownloadRecord(
    val bookKey: String,
    /** Downloaded on purpose (not by the 10-minute rule). */
    val pinned: Boolean,
    val trackIds: List<String>,
    val sizeBytes: Long,
    val addedAt: Long = System.currentTimeMillis(),
)

/**
 * Small JSON files in app storage. Everything here works offline: progress
 * is queued until Plex can be reached, and book details are kept so
 * downloaded books can be browsed and played without a connection.
 */
class Store(context: Context) {
    private val dir = File(context.filesDir, "store").apply { mkdirs() }
    private val bookDir = File(dir, "books").apply { mkdirs() }

    private fun <T> load(name: String, ser: KSerializer<T>, default: T): T =
        runCatching { PlexJson.decodeFromString(ser, File(dir, name).readText()) }.getOrDefault(default)

    private fun <T> save(name: String, ser: KSerializer<T>, value: T) {
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(PlexJson.encodeToString(ser, value))
        tmp.renameTo(File(dir, name))
    }

    // ---- progress ----
    private val progressSer = MapSerializer(String.serializer(), Progress.serializer())
    private val _progress = MutableStateFlow(load("progress.json", progressSer, emptyMap()))
    val progress: StateFlow<Map<String, Progress>> = _progress

    @Synchronized
    fun setProgress(bookKey: String, p: Progress) {
        _progress.value = _progress.value + (bookKey to p)
        save("progress.json", progressSer, _progress.value)
    }

    fun progress(bookKey: String): Progress? = _progress.value[bookKey]

    // ---- bookmarks ----
    private val bookmarkSer = MapSerializer(String.serializer(), ListSerializer(Bookmark.serializer()))
    private val _bookmarks = MutableStateFlow(load("bookmarks.json", bookmarkSer, emptyMap()))
    val bookmarks: StateFlow<Map<String, List<Bookmark>>> = _bookmarks

    @Synchronized
    fun setBookmarks(bookKey: String, list: List<Bookmark>) {
        _bookmarks.value = _bookmarks.value + (bookKey to list.sortedWith(compareBy({ it.trackIndex }, { it.positionMs })))
        save("bookmarks.json", bookmarkSer, _bookmarks.value)
    }

    // ---- downloads ----
    private val dlSer = MapSerializer(String.serializer(), DownloadRecord.serializer())
    private val _downloads = MutableStateFlow(load("downloads.json", dlSer, emptyMap()))
    val downloads: StateFlow<Map<String, DownloadRecord>> = _downloads

    @Synchronized
    fun setDownload(r: DownloadRecord?, bookKey: String) {
        _downloads.value = if (r == null) _downloads.value - bookKey else _downloads.value + (bookKey to r)
        save("downloads.json", dlSer, _downloads.value)
    }

    /** Whole books waiting for Wi-Fi before downloading automatically. */
    private val _waitingForWifi = MutableStateFlow(load("waiting.json", ListSerializer(String.serializer()), emptyList()))
    val waitingForWifi: StateFlow<List<String>> = _waitingForWifi

    @Synchronized
    fun setWaitingForWifi(keys: List<String>) {
        _waitingForWifi.value = keys.distinct()
        save("waiting.json", ListSerializer(String.serializer()), _waitingForWifi.value)
    }

    // ---- book details (offline browsing) ----
    private val bookMemo = java.util.concurrent.ConcurrentHashMap<String, BookDetail>()

    fun saveBook(detail: BookDetail) {
        bookMemo[detail.book.key] = detail
        File(bookDir, "${detail.book.key}.json").writeText(PlexJson.encodeToString(BookDetail.serializer(), detail))
    }

    fun book(key: String): BookDetail? = bookMemo[key] ?: runCatching {
        PlexJson.decodeFromString(BookDetail.serializer(), File(bookDir, "$key.json").readText())
    }.getOrNull()?.also { bookMemo[key] = it }

    // ---- last-seen lists (offline browsing) ----
    fun saveList(name: String, books: List<Book>) = save("list-$name.json", ListSerializer(Book.serializer()), books)
    fun list(name: String): List<Book> = load("list-$name.json", ListSerializer(Book.serializer()), emptyList())

    /** Clears the signed-in person's own data (sign-out / profile switch).
     *  Downloads and book details stay: they're the same files for everyone. */
    fun clearUserData() {
        dir.listFiles()?.filter {
            it.isFile && (it.name in setOf("progress.json", "bookmarks.json", "waiting.json") || it.name.startsWith("list-"))
        }?.forEach { it.delete() }
        _progress.value = emptyMap(); _bookmarks.value = emptyMap(); _waitingForWifi.value = emptyList()
    }
}
