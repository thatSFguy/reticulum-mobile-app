package io.github.thatsfguy.reticulum.android.ui.audio

import android.media.AudioAttributes
import android.media.MediaDataSource
import android.media.MediaPlayer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * One-at-a-time voice-clip playback for the conversation's audio bubbles.
 *
 * Owns the single [MediaPlayer] so that:
 *  - the bubble's ⏸ actually pauses (and ▶ resumes) the clip already
 *    playing, instead of each tap spinning up another player on top of it
 *    (issue #64);
 *  - the player is strongly referenced for its whole life — it used to be a
 *    local inside a fire-and-forget helper, so the GC could finalize it
 *    mid-clip and the audio cut out "randomly" (issue #66);
 *  - playback survives the bubble scrolling out of the LazyColumn (e.g. the
 *    list re-anchoring when a new message lands), which a per-bubble player
 *    tied to the item's composition would not.
 *
 * Clip bytes are fed through an in-memory [MediaDataSource], so there is no
 * shared temp file for two clips to overwrite or delete under each other.
 *
 * Main-thread only (Compose click handlers + MediaPlayer callbacks, which
 * post to the looper the player was created on).
 */
object VoiceClipPlayback {
    /** Id of the clip that is audibly playing right now, or null. Observable
     *  from Compose so bubbles can show ▶ / ⏸. */
    var playingId by mutableStateOf<Long?>(null)
        private set

    private var player: MediaPlayer? = null
    private var loadedId: Long? = null
    private var prepared = false

    /** If [id] is the loaded clip, pause or resume it and return true.
     *  Returns false when [id] isn't loaded — the caller then fetches the
     *  bytes and calls [play]. */
    fun pauseOrResume(id: Long): Boolean {
        val mp = player ?: return false
        if (loadedId != id) return false
        // Still preparing: a second tap shouldn't start a second clip.
        if (playingId != id && !prepared) return true
        runCatching {
            if (mp.isPlaying) {
                mp.pause(); playingId = null
            } else {
                // Also covers PlaybackCompleted, where start() replays from 0.
                mp.start(); playingId = id
            }
        }.onFailure { stop() }
        return true
    }

    /** Stop whatever is loaded and start [bytes] as clip [id]. */
    fun play(id: Long, bytes: ByteArray, onError: () -> Unit) {
        stop()
        val mp = MediaPlayer()
        player = mp
        loadedId = id
        prepared = false
        runCatching {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            mp.setDataSource(ByteArrayDataSource(bytes))
            mp.setOnPreparedListener {
                if (player !== it) return@setOnPreparedListener
                prepared = true
                it.start()
                playingId = id
            }
            // Keep the player loaded after the clip ends so the next tap
            // replays it without re-decoding; just flip the glyph back.
            mp.setOnCompletionListener {
                if (player === it) playingId = null
            }
            mp.setOnErrorListener { p, _, _ ->
                if (player === p) { stop(); onError() } else p.release()
                true
            }
            mp.prepareAsync()
        }.onFailure {
            stop()
            onError()
        }
    }

    /** Stop and release the current clip, if any. */
    fun stop() {
        val mp = player
        player = null
        loadedId = null
        prepared = false
        playingId = null
        if (mp != null) runCatching { mp.release() }
    }
}

private class ByteArrayDataSource(private val data: ByteArray) : MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= data.size) return -1
        val n = minOf(size.toLong(), data.size - position).toInt()
        System.arraycopy(data, position.toInt(), buffer, offset, n)
        return n
    }

    override fun getSize(): Long = data.size.toLong()

    override fun close() {}
}
