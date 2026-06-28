package io.github.thatsfguy.reticulum.codec

import io.github.thatsfguy.reticulum.codec.cinterop.opus.opus_decode
import io.github.thatsfguy.reticulum.codec.cinterop.opus.opus_decoder_create
import io.github.thatsfguy.reticulum.codec.cinterop.opus.opus_decoder_destroy
import io.github.thatsfguy.reticulum.codec.cinterop.opus.opus_encode
import io.github.thatsfguy.reticulum.codec.cinterop.opus.opus_encoder_create
import io.github.thatsfguy.reticulum.codec.cinterop.opus.opus_encoder_destroy
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value

/**
 * iOS Opus codec — the C half of voice-clip support. Encodes captured mic
 * PCM into an Opus-in-Ogg clip ([AudioMode.OPUS_OGG], the format Android's
 * MediaRecorder and Sideband produce) and decodes an inbound clip back to
 * PCM for playback. Container framing is done in memory-safe Kotlin by
 * [OggOpus]; only bounded individual Opus packets reach libopus here.
 *
 * ## Security (decode path runs on attacker-controlled bytes)
 *
 * [decodeVoiceClipToPcm] is the only path that feeds peer-supplied bytes to
 * C, so it is bounded twice over:
 *  - [MAX_CLIP_BYTES] rejects an oversized container outright (the engine
 *    already caps inbound attachments; this is defense in depth).
 *  - [MAX_DECODED_SAMPLES] caps total decoded PCM, defeating a
 *    decompression bomb (a stream of tiny packets each expanding to a full
 *    120 ms frame). Decoding aborts the moment the cap is crossed.
 *  Callers should additionally gate playback to clips from
 *  signature-verified / known senders (the engine's drop-unverified path).
 */
@OptIn(ExperimentalForeignApi::class)
object OpusCodec {

    private const val SAMPLE_RATE = 48000        // Opus always runs at 48 kHz
    private const val FRAME_SAMPLES = 960        // 20 ms per packet at 48 kHz
    private const val MAX_FRAME_SAMPLES = 5760   // 120 ms — opus_decode capacity
    private const val MAX_PACKET_BYTES = 4000    // RFC-recommended encode ceiling
    private const val OPUS_OK = 0
    private const val OPUS_APPLICATION_VOIP = 2048

    /** ~4 MB container ceiling (matches the engine's inbound attachment cap). */
    const val MAX_CLIP_BYTES = 4 * 1024 * 1024

    /** 5 minutes of mono 48 kHz audio — decode-bomb guard. */
    const val MAX_DECODED_SAMPLES = SAMPLE_RATE * 60 * 5

    /** Decoded PCM ready for playback. [pcm] is interleaved little-endian
     *  signed 16-bit samples; Swift hands it straight to an AVAudioPCMBuffer. */
    class Pcm(val sampleRate: Int, val channels: Int, val pcm: ByteArray)

    // ---- encode: mic PCM → Opus-in-Ogg ----------------------------------

    /**
     * Encode interleaved little-endian 16-bit [pcmLe] at 48 kHz into an
     * Opus-in-Ogg clip. [channels] is 1 (we record mono). Throws on encoder
     * failure. The trailing partial frame is zero-padded to a full 20 ms.
     */
    fun encodePcmToOgg(pcmLe: ByteArray, channels: Int = 1): ByteArray {
        require(channels in 1..2) { "channels must be 1 or 2" }
        val samples = bytesToShorts(pcmLe)
        val frameLen = FRAME_SAMPLES * channels

        return memScoped {
            val err = alloc<IntVar>()
            val enc = opus_encoder_create(SAMPLE_RATE, channels, OPUS_APPLICATION_VOIP, err.ptr)
            check(enc != null && err.value == OPUS_OK) {
                "opus_encoder_create failed: ${err.value}"
            }
            try {
                val packets = ArrayList<ByteArray>()
                val out = ByteArray(MAX_PACKET_BYTES)
                var off = 0
                while (off < samples.size) {
                    // Last frame: copy what's left into a zero-padded frame.
                    val frame = if (off + frameLen <= samples.size) {
                        samples.copyOfRange(off, off + frameLen)
                    } else {
                        ShortArray(frameLen).also { samples.copyInto(it, 0, off, samples.size) }
                    }
                    val n = frame.usePinned { fin ->
                        out.usePinned { o ->
                            opus_encode(
                                enc,
                                fin.addressOf(0),
                                FRAME_SAMPLES,
                                o.addressOf(0).reinterpret(),
                                MAX_PACKET_BYTES,
                            )
                        }
                    }
                    check(n > 0) { "opus_encode failed: $n" }
                    packets.add(out.copyOf(n))
                    off += frameLen
                }
                OggOpus.mux(
                    audioPackets = packets,
                    channels = channels,
                    preSkip = 0,
                    frameSamples = FRAME_SAMPLES,
                    serial = OGG_SERIAL,
                )
            } finally {
                opus_encoder_destroy(enc)
            }
        }
    }

    // ---- decode: Opus-in-Ogg → PCM (untrusted input) --------------------

    /**
     * Decode an inbound Opus-in-Ogg clip to interleaved LE 16-bit PCM at
     * 48 kHz. Returns null on any failure or cap violation rather than
     * throwing, so the playback UI degrades quietly. Trims the stream's
     * declared pre-skip.
     */
    fun decodeVoiceClipToPcm(ogg: ByteArray): Pcm? {
        if (ogg.isEmpty() || ogg.size > MAX_CLIP_BYTES) return null
        val stream = runCatching { OggOpus.demux(ogg) }.getOrNull() ?: return null
        val channels = stream.channels

        return memScoped {
            val err = alloc<IntVar>()
            val dec = opus_decoder_create(SAMPLE_RATE, channels, err.ptr)
            if (dec == null || err.value != OPUS_OK) return@memScoped null
            try {
                val pcmFrame = ShortArray(MAX_FRAME_SAMPLES * channels)
                val acc = ShortArrayBuilder()
                for (pkt in stream.packets) {
                    if (pkt.isEmpty()) continue
                    val decoded = pkt.usePinned { pin ->
                        pcmFrame.usePinned { out ->
                            opus_decode(
                                dec,
                                pin.addressOf(0).reinterpret(),
                                pkt.size,
                                out.addressOf(0),
                                MAX_FRAME_SAMPLES,
                                0,
                            )
                        }
                    }
                    if (decoded <= 0) return@memScoped null // bad packet → give up
                    acc.append(pcmFrame, decoded * channels)
                    if (acc.size > MAX_DECODED_SAMPLES * channels) return@memScoped null // bomb guard
                }
                // Trim pre-skip (interleaved across channels).
                val skip = (stream.preSkip * channels).coerceAtMost(acc.size)
                val pcm = acc.toShortArray(skip)
                Pcm(SAMPLE_RATE, channels, shortsToBytes(pcm))
            } finally {
                opus_decoder_destroy(dec)
            }
        }
    }

    // ---- helpers --------------------------------------------------------

    private const val OGG_SERIAL = 0x52_4D_41_00 // "RMA\0" — arbitrary, fixed per clip

    private fun bytesToShorts(b: ByteArray): ShortArray {
        val out = ShortArray(b.size / 2)
        for (i in out.indices) {
            out[i] = ((b[i * 2].toInt() and 0xFF) or (b[i * 2 + 1].toInt() shl 8)).toShort()
        }
        return out
    }

    private fun shortsToBytes(s: ShortArray): ByteArray {
        val out = ByteArray(s.size * 2)
        for (i in s.indices) {
            out[i * 2] = (s[i].toInt() and 0xFF).toByte()
            out[i * 2 + 1] = ((s[i].toInt() shr 8) and 0xFF).toByte()
        }
        return out
    }

    /** Growable ShortArray — accumulates decoded PCM without per-frame realloc churn. */
    private class ShortArrayBuilder {
        private var buf = ShortArray(48000)
        var size = 0; private set
        fun append(src: ShortArray, count: Int) {
            if (size + count > buf.size) {
                var n = buf.size * 2
                while (n < size + count) n *= 2
                buf = buf.copyOf(n)
            }
            src.copyInto(buf, size, 0, count)
            size += count
        }
        fun toShortArray(from: Int): ShortArray = buf.copyOfRange(from, size)
    }
}

// ---- Swift-facing bridges (top-level → OpusCodecKt.* in Swift) ----------

/**
 * Encode mic PCM (interleaved LE 16-bit, 48 kHz, mono) into an Opus-in-Ogg
 * clip for [sendAudioMessage]. Swift passes the capture buffer as `Data`.
 */
fun encodeVoiceClipBridge(pcmLe: ByteArray, channels: Int): ByteArray =
    OpusCodec.encodePcmToOgg(pcmLe, channels)

/** Swift-facing decode result; null fields when [OpusCodec.decodeVoiceClipToPcm] fails. */
class VoicePcmResult(val ok: Boolean, val sampleRate: Int, val channels: Int, val pcm: ByteArray)

/**
 * Decode an inbound Opus-in-Ogg clip to PCM for playback. Returns a result
 * with `ok = false` on any failure / cap violation (Kotlin's nullable
 * bridges awkwardly to Swift, so we use an explicit flag).
 */
fun decodeVoiceClipBridge(ogg: ByteArray): VoicePcmResult {
    val pcm = OpusCodec.decodeVoiceClipToPcm(ogg)
        ?: return VoicePcmResult(false, 48000, 1, ByteArray(0))
    return VoicePcmResult(true, pcm.sampleRate, pcm.channels, pcm.pcm)
}
