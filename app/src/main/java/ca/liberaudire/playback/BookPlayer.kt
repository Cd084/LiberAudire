package ca.liberaudire.playback

import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import ca.liberaudire.Graph
import ca.liberaudire.plex.BookDetail

/**
 * The player with audiobook behaviour added:
 * - skip back/forward use the amounts from Settings, and cross chapter (file)
 *   boundaries: -30 s at the start of a chapter goes into the previous one;
 * - smart rewind: resuming after a pause backs up a little, more after
 *   longer pauses, so you don't lose the thread.
 * Everything that controls playback (app, lock screen, Bluetooth, Android
 * Auto) goes through here.
 */
@OptIn(UnstableApi::class)
class BookPlayer(private val exo: ExoPlayer, private val g: Graph) : ForwardingPlayer(exo) {
    /** The book loaded in the player. */
    @Volatile var book: BookDetail? = null

    private var pausedAt = 0L

    override fun getSeekBackIncrement(): Long = g.prefs.skipBackSec * 1000L
    override fun getSeekForwardIncrement(): Long = g.prefs.skipForwardSec * 1000L
    override fun seekBack() = seekBy(-seekBackIncrement)
    override fun seekForward() = seekBy(seekForwardIncrement)

    /** Position in the whole book (all files together). */
    val bookPosition: Long
        get() = (book?.offsetOf(exo.currentMediaItemIndex) ?: 0L) + exo.currentPosition

    /** Moves by [deltaMs] through the whole book, across files if needed. */
    fun seekBy(deltaMs: Long) = seekToBookPosition(bookPosition + deltaMs)

    fun seekToBookPosition(ms: Long) {
        val b = book
        if (b == null || b.tracks.isEmpty()) {
            exo.seekTo(ms.coerceAtLeast(0)) // no book details: within the current file
            return
        }
        val (i, pos) = b.locate(ms.coerceIn(0, b.durationMs))
        if (i == exo.currentMediaItemIndex) exo.seekTo(pos) else exo.seekTo(i, pos)
    }

    override fun play() {
        smartRewind()
        super.play()
    }

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (playWhenReady && !exo.playWhenReady) smartRewind()
        if (!playWhenReady && exo.playWhenReady) pausedAt = System.currentTimeMillis()
        super.setPlayWhenReady(playWhenReady)
    }

    override fun pause() {
        pausedAt = System.currentTimeMillis()
        super.pause()
    }

    private fun smartRewind() {
        if (!g.prefs.smartRewind || pausedAt == 0L) return
        val paused = System.currentTimeMillis() - pausedAt
        pausedAt = 0L
        val back = when {
            paused < 10_000 -> 0L          // a quick pause: carry on
            paused < 5 * 60_000 -> 3_000L
            paused < 60 * 60_000 -> 10_000L
            else -> 30_000L
        }
        if (back > 0) seekBy(-back)
    }
}
