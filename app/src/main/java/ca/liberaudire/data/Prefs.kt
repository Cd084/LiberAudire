package ca.liberaudire.data

import android.content.Context
import ca.liberaudire.plex.PlexJson
import ca.liberaudire.plex.ServerInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/** App settings (SharedPreferences), observable as [changes]. */
class Prefs(context: Context) {
    private val p = context.getSharedPreferences("liberaudire", Context.MODE_PRIVATE)
    private val _changes = MutableStateFlow(0)
    val changes: StateFlow<Int> = _changes

    private fun edit(block: android.content.SharedPreferences.Editor.() -> Unit) {
        p.edit().apply(block).apply()
        _changes.value++
    }

    val clientId: String = p.getString("clientId", null) ?: UUID.randomUUID().toString().also {
        p.edit().putString("clientId", it).apply()
    }

    /** plex.tv token of the account that signed in (used to switch Plex Home profiles). */
    var ownerToken: String?
        get() = p.getString("ownerToken", null)
        set(v) = edit { putString("ownerToken", v) }

    /** plex.tv token of the profile in use (same as [ownerToken] unless switched). */
    var accountToken: String?
        get() = p.getString("accountToken", null)
        set(v) = edit { putString("accountToken", v) }

    /** The Plex user/profile we act as. */
    var userTitle: String
        get() = p.getString("userTitle", "") ?: ""
        set(v) = edit { putString("userTitle", v) }
    var userThumb: String?
        get() = p.getString("userThumb", null)
        set(v) = edit { putString("userThumb", v) }
    var userHome: Boolean
        get() = p.getBoolean("userHome", false)
        set(v) = edit { putBoolean("userHome", v) }

    var server: ServerInfo?
        get() = p.getString("server", null)?.let { runCatching { PlexJson.decodeFromString<ServerInfo>(it) }.getOrNull() }
        set(v) = edit { putString("server", v?.let { PlexJson.encodeToString(ServerInfo.serializer(), it) }) }

    /** Last server address that worked (for cover images when offline). */
    var lastBaseUrl: String?
        get() = p.getString("lastBaseUrl", null)
        set(v) { p.edit().putString("lastBaseUrl", v).apply() }

    var libraryKey: String?
        get() = p.getString("libraryKey", null)
        set(v) = edit { putString("libraryKey", v) }
    var libraryTitle: String
        get() = p.getString("libraryTitle", "") ?: ""
        set(v) = edit { putString("libraryTitle", v) }

    // ---- playback ----
    var skipBackSec: Int
        get() = p.getInt("skipBackSec", 30)
        set(v) = edit { putInt("skipBackSec", v) }
    var skipForwardSec: Int
        get() = p.getInt("skipForwardSec", 30)
        set(v) = edit { putInt("skipForwardSec", v) }
    /** Last custom skip entered with a long press, in seconds. */
    var customSkipSec: Int
        get() = p.getInt("customSkipSec", 300)
        set(v) = edit { putInt("customSkipSec", v) }
    var smartRewind: Boolean
        get() = p.getBoolean("smartRewind", true)
        set(v) = edit { putBoolean("smartRewind", v) }
    var defaultSpeed: Float
        get() = p.getFloat("defaultSpeed", 1.0f)
        set(v) = edit { putFloat("defaultSpeed", v) }

    // ---- caching / downloads ----
    /** Download the whole book after this many minutes of listening (0 = off). */
    var autoDownloadAfterMin: Int
        get() = p.getInt("autoDownloadAfterMin", 10)
        set(v) = edit { putInt("autoDownloadAfterMin", v) }
    /** Only download whole books on Wi-Fi/unmetered networks. */
    var wifiOnlyFullDownloads: Boolean
        get() = p.getBoolean("wifiOnlyFullDownloads", true)
        set(v) = edit { putBoolean("wifiOnlyFullDownloads", v) }
    /** Remove downloads of finished books. */
    var deleteFinished: Boolean
        get() = p.getBoolean("deleteFinished", false)
        set(v) = edit { putBoolean("deleteFinished", v) }
    /** Storage limit for automatic downloads, in GB (0 = no limit). */
    var storageLimitGb: Int
        get() = p.getInt("storageLimitGb", 10)
        set(v) = edit { putInt("storageLimitGb", v) }

    fun signOut() = edit {
        remove("ownerToken"); remove("accountToken"); remove("server"); remove("libraryKey"); remove("libraryTitle")
        remove("userTitle"); remove("userThumb"); remove("userHome")
    }
}
