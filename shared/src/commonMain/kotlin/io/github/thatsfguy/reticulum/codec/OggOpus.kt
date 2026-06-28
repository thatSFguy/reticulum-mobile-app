package io.github.thatsfguy.reticulum.codec

/**
 * Ogg encapsulation for the Opus codec (RFC 7845) — the container half of
 * iOS voice-clip support (`FIELD_AUDIO` / `AudioMode.OPUS_OGG`).
 *
 * iOS's AVFoundation cannot read or write Ogg-Opus, so the container
 * framing is done here in memory-safe Kotlin and only the codec itself
 * (libopus, C) runs on raw Opus packets. Keeping the container parser out
 * of C deliberately shrinks the attack surface: [demux] runs on
 * attacker-controlled bytes (a clip a peer sent), so a memory-unsafe Ogg
 * parser would be exposed — instead the only untrusted bytes that reach C
 * are the bounded individual Opus packets handed to the decoder.
 *
 * The output of [mux] is a standard RFC 7845 stream playable by Android's
 * `MediaPlayer` and Sideband; [demux] accepts what their encoders produce.
 *
 * ## Robustness (untrusted input)
 *
 * [demux] is hostile-input-hardened: it bounds every length it reads
 * against the actual buffer, never allocates based on an unchecked field,
 * and throws [IllegalArgumentException] on any malformation rather than
 * over-reading. Callers bound the total input size before calling (the
 * engine already caps inbound attachments).
 */
object OggOpus {

    private const val CAPTURE_PATTERN = 0x4F676753 // "OggS" big-endian
    private val OPUS_HEAD = "OpusHead".encodeToByteArray()
    private val OPUS_TAGS = "OpusTags".encodeToByteArray()

    /** Opus always decodes at 48 kHz regardless of the original input rate. */
    const val OPUS_DECODE_RATE = 48000

    /** Parsed Opus-in-Ogg stream. [packets] are the raw Opus audio packets
     *  in order, ready to feed to `opus_decode`; the header packets
     *  (OpusHead / OpusTags) are consumed and not included. */
    class Stream(
        val channels: Int,
        val preSkip: Int,
        val inputSampleRate: Int,
        val packets: List<ByteArray>,
    )

    // ---- demux (decode side — runs on untrusted bytes) ------------------

    fun demux(bytes: ByteArray): Stream {
        val rawPackets = readAllPackets(bytes)
        require(rawPackets.isNotEmpty()) { "Ogg stream contains no packets" }

        // Packet 1 = OpusHead. RFC 7845 §5.1: magic(8) version(1)
        // channels(1) preSkip(2 LE) inputRate(4 LE) outputGain(2 LE)
        // mappingFamily(1).
        val head = rawPackets[0]
        require(head.size >= 19 && head.startsWith(OPUS_HEAD)) {
            "First Ogg packet is not an OpusHead header"
        }
        val channels = head[9].toInt() and 0xFF
        require(channels in 1..2) { "Unsupported Opus channel count: $channels" }
        val preSkip = le16(head, 10)
        val inputRate = le32(head, 12)

        // Packet 2 = OpusTags (metadata); skip it. Everything after the
        // header packets is audio. Some encoders may interleave, but RFC
        // 7845 mandates OpusHead then OpusTags then audio.
        val audioStart = if (rawPackets.size >= 2 && rawPackets[1].startsWith(OPUS_TAGS)) 2 else 1
        val audio = if (audioStart < rawPackets.size) rawPackets.subList(audioStart, rawPackets.size).toList()
        else emptyList()
        require(audio.isNotEmpty()) { "Ogg-Opus stream has no audio packets" }

        return Stream(channels, preSkip, inputRate, audio)
    }

    /**
     * Walk every Ogg page and reassemble logical packets across page and
     * segment boundaries via the lacing table (RFC 3533). A lacing value
     * of 255 means the packet continues into the next segment; any value
     * < 255 (including 0) terminates the current packet.
     */
    private fun readAllPackets(b: ByteArray): List<ByteArray> {
        val packets = ArrayList<ByteArray>()
        var pos = 0
        // Bytes accumulated for a packet still being assembled across the
        // 255-lacing continuation (and across pages via the continued flag).
        var pending = ByteArray(0)
        while (pos + 27 <= b.size) {
            require(be32(b, pos) == CAPTURE_PATTERN) {
                "Bad Ogg capture pattern at offset $pos"
            }
            // b[pos+4] = stream structure version (must be 0)
            require((b[pos + 4].toInt() and 0xFF) == 0) { "Unsupported Ogg version" }
            // b[pos+5] = header type flags (bit0 continued; we trust the
            // lacing reassembly below rather than the flag for joining).
            val segCount = b[pos + 26].toInt() and 0xFF
            val segTableStart = pos + 27
            require(segTableStart + segCount <= b.size) { "Truncated Ogg segment table" }
            var dataPos = segTableStart + segCount
            // Validate the whole page body fits before reading any of it.
            var bodyLen = 0
            for (i in 0 until segCount) bodyLen += b[segTableStart + i].toInt() and 0xFF
            require(dataPos + bodyLen <= b.size) { "Truncated Ogg page body" }

            var i = 0
            while (i < segCount) {
                val lace = b[segTableStart + i].toInt() and 0xFF
                pending += b.copyOfRange(dataPos, dataPos + lace)
                dataPos += lace
                if (lace < 255) {
                    // Packet boundary. A zero-length terminating packet is
                    // legal framing noise; only emit non-empty packets.
                    if (pending.isNotEmpty()) packets.add(pending)
                    pending = ByteArray(0)
                }
                i++
            }
            pos = dataPos
            // Sanity ceiling: a voice clip never has a pathological page
            // count; bail rather than spin on crafted input.
            require(packets.size <= 100_000) { "Implausible Ogg packet count" }
        }
        // A trailing `pending` (last packet ended exactly on a 255 lacing
        // with no terminator) is malformed for our purposes; drop it.
        return packets
    }

    // ---- mux (encode side — our own trusted output) ---------------------

    /**
     * Wrap raw Opus [audioPackets] (each from `opus_encode` at 48 kHz) in
     * an RFC 7845 Ogg-Opus stream. [frameSamples] is the per-packet decoded
     * sample count at 48 kHz (e.g. 960 for a 20 ms frame) used to advance
     * the granule position. [serial] identifies the logical stream.
     */
    fun mux(
        audioPackets: List<ByteArray>,
        channels: Int,
        preSkip: Int,
        frameSamples: Int,
        serial: Int,
    ): ByteArray {
        require(channels in 1..2) { "channels must be 1 or 2" }
        val out = ByteArrayBuilder()
        var pageSeq = 0

        // Page 0 (BOS): OpusHead alone.
        out.append(buildPage(listOf(buildOpusHead(channels, preSkip)), serial, pageSeq++, 0L, bos = true, eos = false))
        // Page 1: OpusTags alone.
        out.append(buildPage(listOf(buildOpusTags()), serial, pageSeq++, 0L, bos = false, eos = false))

        // Audio pages. Keep it simple and robust: one audio packet per page.
        // Voice clips are short, so the per-page overhead (~28 B) is
        // negligible and this sidesteps multi-packet lacing edge cases.
        var granule = preSkip.toLong()
        for ((idx, pkt) in audioPackets.withIndex()) {
            granule += frameSamples
            val eos = idx == audioPackets.lastIndex
            out.append(buildPage(listOf(pkt), serial, pageSeq++, granule, bos = false, eos = eos))
        }
        return out.toByteArray()
    }

    private fun buildOpusHead(channels: Int, preSkip: Int): ByteArray {
        val h = ByteArray(19)
        OPUS_HEAD.copyInto(h, 0)
        h[8] = 1 // version
        h[9] = channels.toByte()
        putLe16(h, 10, preSkip)
        putLe32(h, 12, OPUS_DECODE_RATE) // original input rate (informational)
        // outputGain (16..17) = 0; mappingFamily (18) = 0 (mono/stereo)
        return h
    }

    private fun buildOpusTags(): ByteArray {
        val vendor = "reticulum-mobile".encodeToByteArray()
        val b = ByteArray(8 + 4 + vendor.size + 4)
        OPUS_TAGS.copyInto(b, 0)
        putLe32(b, 8, vendor.size)
        vendor.copyInto(b, 12)
        putLe32(b, 12 + vendor.size, 0) // user comment count
        return b
    }

    /** One Ogg page carrying [packets]. Each packet must be < 255*255 bytes
     *  (true for all Opus voice packets); the lacing splits it into 255-byte
     *  laces with a final <255 terminator. */
    private fun buildPage(
        packets: List<ByteArray>,
        serial: Int,
        pageSeq: Int,
        granule: Long,
        bos: Boolean,
        eos: Boolean,
    ): ByteArray {
        val laces = ArrayList<Int>()
        val body = ByteArrayBuilder()
        for (pkt in packets) {
            require(pkt.size < 255 * 255) { "Opus packet too large for a single page" }
            var remaining = pkt.size
            while (remaining >= 255) { laces.add(255); remaining -= 255 }
            laces.add(remaining) // final lace < 255 terminates the packet
            body.append(pkt)
        }
        require(laces.size <= 255) { "Too many segments for one page" }

        val header = ByteArray(27 + laces.size)
        putBe32(header, 0, CAPTURE_PATTERN)
        header[4] = 0 // version
        header[5] = ((if (bos) 0x02 else 0) or (if (eos) 0x04 else 0)).toByte()
        putLe64(header, 6, granule)
        putLe32(header, 14, serial)
        putLe32(header, 18, pageSeq)
        // 22..25 = CRC, left zero for the checksum pass below.
        header[26] = laces.size.toByte()
        for (i in laces.indices) header[27 + i] = laces[i].toByte()

        val bodyBytes = body.toByteArray()
        val crc = oggCrc32(header, bodyBytes)
        putLe32Crc(header, 22, crc)

        val page = ByteArray(header.size + bodyBytes.size)
        header.copyInto(page, 0)
        bodyBytes.copyInto(page, header.size)
        return page
    }

    // ---- Ogg CRC-32 (poly 0x04C11DB7, no reflection, init 0, no final XOR)

    private val CRC_TABLE = IntArray(256).also { t ->
        for (i in 0 until 256) {
            var r = i shl 24
            for (j in 0 until 8) {
                r = if ((r and 0x80000000.toInt()) != 0) (r shl 1) xor 0x04c11db7 else r shl 1
            }
            t[i] = r
        }
    }

    private fun oggCrc32(header: ByteArray, body: ByteArray): Int {
        var crc = 0
        for (byte in header) crc = (crc shl 8) xor CRC_TABLE[((crc ushr 24) xor (byte.toInt() and 0xFF)) and 0xFF]
        for (byte in body) crc = (crc shl 8) xor CRC_TABLE[((crc ushr 24) xor (byte.toInt() and 0xFF)) and 0xFF]
        return crc
    }

    // ---- byte helpers ---------------------------------------------------

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        for (i in prefix.indices) if (this[i] != prefix[i]) return false
        return true
    }

    private fun be32(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)

    private fun le16(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun le32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    private fun putLe16(b: ByteArray, o: Int, v: Int) {
        b[o] = (v and 0xFF).toByte(); b[o + 1] = ((v ushr 8) and 0xFF).toByte()
    }

    private fun putLe32(b: ByteArray, o: Int, v: Int) {
        b[o] = (v and 0xFF).toByte(); b[o + 1] = ((v ushr 8) and 0xFF).toByte()
        b[o + 2] = ((v ushr 16) and 0xFF).toByte(); b[o + 3] = ((v ushr 24) and 0xFF).toByte()
    }

    // CRC is stored little-endian like every other Ogg integer.
    private fun putLe32Crc(b: ByteArray, o: Int, v: Int) = putLe32(b, o, v)

    private fun putBe32(b: ByteArray, o: Int, v: Int) {
        b[o] = ((v ushr 24) and 0xFF).toByte(); b[o + 1] = ((v ushr 16) and 0xFF).toByte()
        b[o + 2] = ((v ushr 8) and 0xFF).toByte(); b[o + 3] = (v and 0xFF).toByte()
    }

    private fun putLe64(b: ByteArray, o: Int, v: Long) {
        var x = v
        for (i in 0 until 8) { b[o + i] = (x and 0xFF).toByte(); x = x ushr 8 }
    }

    /** Tiny growable byte buffer — avoids depending on okio just for this. */
    private class ByteArrayBuilder {
        private var buf = ByteArray(256)
        private var len = 0
        fun append(bytes: ByteArray) {
            if (len + bytes.size > buf.size) {
                var n = buf.size * 2
                while (n < len + bytes.size) n *= 2
                buf = buf.copyOf(n)
            }
            bytes.copyInto(buf, len)
            len += bytes.size
        }
        fun toByteArray(): ByteArray = buf.copyOf(len)
    }
}
