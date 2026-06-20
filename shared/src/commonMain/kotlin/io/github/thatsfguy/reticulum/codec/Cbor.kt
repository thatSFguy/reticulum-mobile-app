package io.github.thatsfguy.reticulum.codec

/**
 * Minimal CBOR (RFC 8949) codec for the Reticulum Relay Chat wire
 * format. RRC envelopes are CBOR maps with unsigned-integer keys; the
 * reference hub `rrcd` encodes them with Python `cbor2` (`rrcd/codec.py`).
 *
 * Supported subset — everything an RRC envelope can carry:
 *   - unsigned int        (major 0)
 *   - negative int        (major 1)
 *   - byte string         (major 2)
 *   - text string         (major 3, UTF-8)
 *   - array               (major 4)
 *   - map                 (major 5)
 *   - false / true / null  (major 7, simple values 20 / 21 / 22)
 *
 * Deliberately unsupported — RRC never emits these, and silently
 * mis-decoding is worse than failing loud: floats (major 7, 0xf9-0xfb),
 * tags (major 6), and indefinite-length items (additional-info 31).
 * Hitting one throws IllegalArgumentException.
 *
 * Decode returns a tree of: null, Boolean, Long, ByteArray, String,
 * List<Any?>, Map<Any?, Any?>. Maps preserve wire order (LinkedHashMap).
 *
 * Encoding is canonical for this subset: minimal-width integers,
 * definite-length items, map keys emitted in iteration order. RRC
 * envelopes are built with ascending integer keys, so iteration order
 * already equals CBOR's canonical key order — output is byte-identical
 * to `cbor2.dumps`, pinned by CborTest against cbor2 fixtures.
 */
object Cbor {

    fun encode(value: Any?): ByteArray {
        val out = CborWriter()
        write(out, value)
        return out.toByteArray()
    }

    fun decode(data: ByteArray): Any? = read(CborReader(data), 0)

    // ---- Encode ---------------------------------------------------------

    private fun write(out: CborWriter, v: Any?) {
        when (v) {
            null         -> out.appendU8(0xF6)
            is Boolean   -> out.appendU8(if (v) 0xF5 else 0xF4)
            is Byte      -> writeInt(out, v.toLong())
            is Short     -> writeInt(out, v.toLong())
            is Int       -> writeInt(out, v.toLong())
            is Long      -> writeInt(out, v)
            is ByteArray -> { writeHead(out, MT_BYTES, v.size.toLong()); out.appendBytes(v) }
            is String    -> {
                val b = v.encodeToByteArray()
                writeHead(out, MT_TEXT, b.size.toLong())
                out.appendBytes(b)
            }
            is List<*>   -> {
                writeHead(out, MT_ARRAY, v.size.toLong())
                for (e in v) write(out, e)
            }
            is Map<*, *> -> {
                writeHead(out, MT_MAP, v.size.toLong())
                for ((k, mv) in v) { write(out, k); write(out, mv) }
            }
            else -> throw IllegalArgumentException(
                "Unsupported CBOR type: ${v::class.simpleName}",
            )
        }
    }

    private fun writeInt(out: CborWriter, v: Long) {
        // CBOR major 1 (negative): the encoded argument is (-1 - v).
        if (v >= 0) writeHead(out, MT_UINT, v) else writeHead(out, MT_NEGINT, -1 - v)
    }

    /** Emit a major-type byte + minimal-width argument (RFC 8949 §3). */
    private fun writeHead(out: CborWriter, major: Int, arg: Long) {
        val hi = major shl 5
        when {
            arg < 0L           -> throw IllegalArgumentException("CBOR argument out of range: $arg")
            arg <= 23L         -> out.appendU8(hi or arg.toInt())
            arg <= 0xFFL       -> { out.appendU8(hi or 24); out.appendU8(arg.toInt()) }
            arg <= 0xFFFFL     -> { out.appendU8(hi or 25); out.appendU16BE(arg.toInt()) }
            arg <= 0xFFFFFFFFL -> { out.appendU8(hi or 26); out.appendU32BE(arg) }
            else               -> { out.appendU8(hi or 27); out.appendU64BE(arg) }
        }
    }

    // ---- Decode ---------------------------------------------------------

    private fun read(r: CborReader, depth: Int): Any? {
        // Bound nesting — without this a frame of N array/map head bytes
        // recurses N deep and StackOverflowErrors the decode thread.
        if (depth > MAX_DEPTH) {
            throw IllegalArgumentException("CBOR nesting exceeds depth cap $MAX_DEPTH")
        }
        val initial = r.readU8()
        val major = initial shr 5
        val ai = initial and 0x1F

        if (major == MT_SIMPLE) {
            return when (ai) {
                20   -> false
                21   -> true
                22   -> null
                // ai 25/26/27 are float16/32/64; everything else here
                // is a simple value RRC never uses.
                else -> throw IllegalArgumentException(
                    "Unsupported CBOR simple/float value: ai=$ai",
                )
            }
        }

        val arg = readArgument(r, ai)
        return when (major) {
            MT_UINT   -> arg
            MT_NEGINT -> -1 - arg
            MT_BYTES  -> r.readBytes(lengthOf(arg))
            MT_TEXT   -> r.readBytes(lengthOf(arg)).decodeToString()
            MT_ARRAY  -> {
                val n = lengthOf(arg)
                // n <= bytes-remaining: every element is >= 1 byte, so a
                // container can never declare more elements than the input
                // has bytes left. Blocks the pre-allocation OOM where
                // nested arrays each declare MAX_CONTAINER_LEN elements.
                require(n <= MAX_CONTAINER_LEN && n <= r.remaining()) {
                    "CBOR array length $n implausible (cap $MAX_CONTAINER_LEN, ${r.remaining()} bytes left)"
                }
                ArrayList<Any?>(n).apply { repeat(n) { add(read(r, depth + 1)) } }
            }
            MT_MAP    -> {
                val n = lengthOf(arg)
                require(n <= MAX_CONTAINER_LEN && n <= r.remaining()) {
                    "CBOR map length $n implausible (cap $MAX_CONTAINER_LEN, ${r.remaining()} bytes left)"
                }
                LinkedHashMap<Any?, Any?>(n).apply { repeat(n) { put(read(r, depth + 1), read(r, depth + 1)) } }
            }
            // major 6 = tags — unsupported.
            else -> throw IllegalArgumentException("Unsupported CBOR major type: $major")
        }
    }

    /** Read the integer argument that follows the initial byte. */
    private fun readArgument(r: CborReader, ai: Int): Long = when {
        ai <= 23 -> ai.toLong()
        ai == 24 -> r.readU8().toLong()
        ai == 25 -> r.readU16BE().toLong()
        ai == 26 -> r.readU32BE()
        ai == 27 -> r.readU64BE()
        // ai 28-30 reserved, 31 = indefinite length — neither is valid
        // canonical CBOR, which is all RRC speaks.
        else     -> throw IllegalArgumentException(
            "Unsupported CBOR additional-info: $ai (reserved or indefinite-length)",
        )
    }

    private fun lengthOf(arg: Long): Int {
        if (arg < 0 || arg > Int.MAX_VALUE.toLong()) {
            throw IllegalArgumentException("CBOR length out of range: $arg")
        }
        return arg.toInt()
    }

    private const val MT_UINT   = 0
    private const val MT_NEGINT = 1
    private const val MT_BYTES  = 2
    private const val MT_TEXT   = 3
    private const val MT_ARRAY  = 4
    private const val MT_MAP    = 5
    private const val MT_SIMPLE = 7

    /**
     * Defensive ceiling on container element counts from untrusted
     * input — a malicious 0x9b/0xbb prefix could otherwise advertise a
     * billions-long array and OOM the phone before any bytes arrive.
     * 65,536 is far above any real RRC envelope. Mirrors MessagePack's
     * MAX_CONTAINER_LEN.
     */
    private const val MAX_CONTAINER_LEN = 65_536

    /**
     * Maximum array/map nesting depth. RRC envelopes nest ~3 deep
     * (envelope → body → caps); 64 is far above anything legitimate and
     * keeps a malicious deeply-nested frame from exhausting the stack.
     */
    private const val MAX_DEPTH = 64
}

private class CborWriter {
    private val buf = ArrayList<Byte>()
    fun appendU8(v: Int)    { buf.add((v and 0xFF).toByte()) }
    fun appendU16BE(v: Int) {
        buf.add(((v shr 8) and 0xFF).toByte())
        buf.add((v and 0xFF).toByte())
    }
    fun appendU32BE(v: Long) {
        buf.add(((v shr 24) and 0xFF).toByte())
        buf.add(((v shr 16) and 0xFF).toByte())
        buf.add(((v shr  8) and 0xFF).toByte())
        buf.add(( v         and 0xFF).toByte())
    }
    fun appendU64BE(v: Long) {
        for (i in 7 downTo 0) buf.add(((v shr (i * 8)) and 0xFF).toByte())
    }
    fun appendBytes(b: ByteArray) { for (x in b) buf.add(x) }
    fun toByteArray(): ByteArray = buf.toByteArray()
}

private class CborReader(private val data: ByteArray) {
    private var pos = 0
    /** Bytes not yet consumed — an upper bound on remaining container elements. */
    fun remaining(): Int = data.size - pos
    fun readU8(): Int {
        if (pos >= data.size) throw IllegalArgumentException("CBOR underrun")
        return data[pos++].toInt() and 0xFF
    }
    fun readU16BE(): Int = (readU8() shl 8) or readU8()
    fun readU32BE(): Long {
        val a = readU8().toLong(); val b = readU8().toLong()
        val c = readU8().toLong(); val d = readU8().toLong()
        return (a shl 24) or (b shl 16) or (c shl 8) or d
    }
    fun readU64BE(): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or readU8().toLong()
        return v
    }
    fun readBytes(n: Int): ByteArray {
        // Long math so a large declared length can't overflow `pos + n`
        // into a negative that slips past the bound (parity with
        // MessagePack.Reader.readBytes). `lengthOf` already caps n to
        // [0, Int.MAX]; this is defense-in-depth.
        if (n < 0 || pos.toLong() + n.toLong() > data.size.toLong()) {
            throw IllegalArgumentException("CBOR underrun: need $n bytes")
        }
        val out = data.copyOfRange(pos, pos + n)
        pos += n
        return out
    }
}
