package io.github.thatsfguy.reticulum.codec

/**
 * Minimal MessagePack codec covering the subset Reticulum / LXMF uses:
 *   - nil
 *   - bool
 *   - int (positive and negative, all widths)
 *   - float32 / float64
 *   - str (fixstr / str8 / str16 / str32)
 *   - bin (bin8 / bin16 / bin32)
 *   - array (fixarray / array16 / array32)
 *   - map (fixmap / map16 / map32)
 *
 * Decode returns a tree of: null, Boolean, Long, Double, String, ByteArray,
 * List<Any?>, Map<Any?, Any?>. We default str to String (UTF-8) and bin to
 * ByteArray; LXMF stores text fields as `bin` so callers expecting a string
 * must UTF-8 decode the ByteArray themselves.
 *
 * Encoding is deterministic enough for LXMF's "stripped" signature variant
 * (the dual-variant verify in lxmf.js handles encoder drift). Integers use
 * the smallest viable width; floats always emit float64; ByteArrays emit
 * bin; Strings emit str.
 */
object MessagePack {

    fun encode(value: Any?): ByteArray {
        val out = ByteArrayBuilder()
        write(out, value)
        return out.toByteArray()
    }

    fun decode(data: ByteArray): Any? {
        val r = Reader(data)
        return read(r, 0)
    }

    // ---- Encode ---------------------------------------------------------

    private fun write(out: ByteArrayBuilder, v: Any?) {
        when (v) {
            null              -> out.appendU8(0xC0)
            is Boolean        -> out.appendU8(if (v) 0xC3 else 0xC2)
            is Byte           -> writeInt(out, v.toLong())
            is Short          -> writeInt(out, v.toLong())
            is Int            -> writeInt(out, v.toLong())
            is Long           -> writeInt(out, v)
            is Float          -> writeFloat64(out, v.toDouble())
            is Double         -> writeFloat64(out, v)
            is String         -> writeStr(out, v)
            is ByteArray      -> writeBin(out, v)
            is List<*>        -> writeArray(out, v)
            is Map<*, *>      -> writeMap(out, v)
            else              -> throw IllegalArgumentException("Unsupported msgpack type: ${v::class.simpleName}")
        }
    }

    private fun writeInt(out: ByteArrayBuilder, v: Long) {
        if (v in 0..0x7F) {
            out.appendU8(v.toInt())
        } else if (v in -32..-1) {
            out.appendU8(v.toInt() and 0xFF)
        } else if (v in 0..0xFF) {
            out.appendU8(0xCC); out.appendU8(v.toInt())
        } else if (v in 0..0xFFFF) {
            out.appendU8(0xCD); out.appendU16BE(v.toInt())
        } else if (v in 0..0xFFFFFFFFL) {
            out.appendU8(0xCE); out.appendU32BE(v)
        } else if (v >= 0) {
            out.appendU8(0xCF); out.appendU64BE(v)
        } else if (v in -128..127) {
            out.appendU8(0xD0); out.appendU8(v.toInt() and 0xFF)
        } else if (v in -32768..32767) {
            out.appendU8(0xD1); out.appendU16BE(v.toInt() and 0xFFFF)
        } else if (v in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
            out.appendU8(0xD2); out.appendU32BE(v and 0xFFFFFFFFL)
        } else {
            out.appendU8(0xD3); out.appendU64BE(v)
        }
    }

    private fun writeFloat64(out: ByteArrayBuilder, v: Double) {
        out.appendU8(0xCB)
        val bits = v.toRawBits()
        out.appendU64BE(bits)
    }

    private fun writeStr(out: ByteArrayBuilder, s: String) {
        val bytes = s.encodeToByteArray()
        val n = bytes.size
        when {
            n <= 31           -> out.appendU8(0xA0 or n)
            n <= 0xFF         -> { out.appendU8(0xD9); out.appendU8(n) }
            n <= 0xFFFF       -> { out.appendU8(0xDA); out.appendU16BE(n) }
            else              -> { out.appendU8(0xDB); out.appendU32BE(n.toLong()) }
        }
        out.appendBytes(bytes)
    }

    private fun writeBin(out: ByteArrayBuilder, b: ByteArray) {
        val n = b.size
        when {
            n <= 0xFF         -> { out.appendU8(0xC4); out.appendU8(n) }
            n <= 0xFFFF       -> { out.appendU8(0xC5); out.appendU16BE(n) }
            else              -> { out.appendU8(0xC6); out.appendU32BE(n.toLong()) }
        }
        out.appendBytes(b)
    }

    private fun writeArray(out: ByteArrayBuilder, a: List<*>) {
        val n = a.size
        when {
            n <= 15           -> out.appendU8(0x90 or n)
            n <= 0xFFFF       -> { out.appendU8(0xDC); out.appendU16BE(n) }
            else              -> { out.appendU8(0xDD); out.appendU32BE(n.toLong()) }
        }
        for (e in a) write(out, e)
    }

    private fun writeMap(out: ByteArrayBuilder, m: Map<*, *>) {
        val n = m.size
        when {
            n <= 15           -> out.appendU8(0x80 or n)
            n <= 0xFFFF       -> { out.appendU8(0xDE); out.appendU16BE(n) }
            else              -> { out.appendU8(0xDF); out.appendU32BE(n.toLong()) }
        }
        for ((k, v) in m) { write(out, k); write(out, v) }
    }

    // ---- Decode ---------------------------------------------------------

    private fun read(r: Reader, depth: Int): Any? {
        // Bound nesting — a frame of N array/map head bytes otherwise
        // recurses N deep and StackOverflowErrors the decode thread.
        if (depth > MAX_DEPTH) {
            throw IllegalArgumentException("msgpack nesting exceeds depth cap $MAX_DEPTH")
        }
        val b = r.readU8()
        return when {
            b <= 0x7F                  -> b.toLong()                 // positive fixint
            b in 0x80..0x8F            -> readMap(r, b and 0x0F, depth)
            b in 0x90..0x9F            -> readArray(r, b and 0x0F, depth)
            b in 0xA0..0xBF            -> readStrBytes(r, b and 0x1F).decodeToString()
            b == 0xC0                  -> null
            b == 0xC2                  -> false
            b == 0xC3                  -> true
            b == 0xC4                  -> r.readBytes(r.readU8())
            b == 0xC5                  -> r.readBytes(r.readU16BE())
            b == 0xC6                  -> r.readBytes(r.readU32BE().toIntChecked())
            b == 0xCA                  -> Float.fromBits(r.readU32BE().toInt()).toDouble()
            b == 0xCB                  -> Double.fromBits(r.readU64BE())
            b == 0xCC                  -> r.readU8().toLong()
            b == 0xCD                  -> r.readU16BE().toLong()
            b == 0xCE                  -> r.readU32BE()
            b == 0xCF                  -> r.readU64BE()
            b == 0xD0                  -> r.readI8().toLong()
            b == 0xD1                  -> r.readI16BE().toLong()
            b == 0xD2                  -> r.readI32BE().toLong()
            b == 0xD3                  -> r.readU64BE()              // signed 64
            b == 0xD9                  -> readStrBytes(r, r.readU8()).decodeToString()
            b == 0xDA                  -> readStrBytes(r, r.readU16BE()).decodeToString()
            b == 0xDB                  -> readStrBytes(r, r.readU32BE().toIntChecked()).decodeToString()
            b == 0xDC                  -> readArray(r, r.readU16BE(), depth)
            b == 0xDD                  -> readArray(r, r.readU32BE().toIntChecked(), depth)
            b == 0xDE                  -> readMap(r, r.readU16BE(), depth)
            b == 0xDF                  -> readMap(r, r.readU32BE().toIntChecked(), depth)
            b in 0xE0..0xFF            -> (b - 256).toLong()         // negative fixint
            else                       -> throw IllegalArgumentException("Unsupported msgpack tag: 0x${b.toString(16)}")
        }
    }

    private fun readStrBytes(r: Reader, n: Int): ByteArray = r.readBytes(n)

    private fun readArray(r: Reader, n: Int, depth: Int): List<Any?> {
        require(n in 0..MAX_CONTAINER_LEN) {
            "msgpack array length $n exceeds defensive cap $MAX_CONTAINER_LEN"
        }
        // n <= bytes-remaining: each element is >= 1 byte, so a container
        // cannot hold more elements than the input has bytes left — blocks
        // the nested pre-allocation OOM (an array of N arrays of N…).
        require(n <= r.remaining()) { "msgpack array length $n exceeds ${r.remaining()} bytes left" }
        val out = ArrayList<Any?>(n)
        repeat(n) { out.add(read(r, depth + 1)) }
        return out
    }

    private fun readMap(r: Reader, n: Int, depth: Int): Map<Any?, Any?> {
        require(n in 0..MAX_CONTAINER_LEN) {
            "msgpack map length $n exceeds defensive cap $MAX_CONTAINER_LEN"
        }
        require(n <= r.remaining()) { "msgpack map length $n exceeds ${r.remaining()} bytes left" }
        val out = LinkedHashMap<Any?, Any?>(n)
        repeat(n) { out[read(r, depth + 1)] = read(r, depth + 1) }
        return out
    }

    /**
     * Defensive ceiling on container element counts read from
     * untrusted msgpack input. The 0xDD (array32) and 0xDF (map32)
     * length prefixes are 32-bit unsigned ints, so a malicious peer
     * could advertise a 4 GB element count and force us to
     * `ArrayList(Int.MAX_VALUE)` — instant OOM on a phone.
     *
     * 65,536 elements is well above anything legitimate LXMF /
     * NomadNet / Resource traffic uses (the largest real container
     * is a propagation-node app_data with 7 elements). The cap
     * still allows multi-megabyte byte BLOBs to deserialize because
     * those go through `readBytes` not `readArray` — only the
     * structural element count is bounded.
     *
     * Audit reference: 2026-05-13 LOW-4.
     */
    private val MAX_CONTAINER_LEN = 65_536

    /**
     * Max array/map nesting depth — see [read]. 64 is far above any real
     * LXMF / announce / Resource-ADV structure (the deepest is ~4) and
     * keeps a maliciously deeply-nested blob from exhausting the stack.
     */
    private val MAX_DEPTH = 64
}

private class ByteArrayBuilder {
    private val buf = ArrayList<Byte>()
    fun appendU8(v: Int)            { buf.add((v and 0xFF).toByte()) }
    fun appendU16BE(v: Int)         { buf.add(((v shr 8) and 0xFF).toByte()); buf.add((v and 0xFF).toByte()) }
    fun appendU32BE(v: Long)        {
        buf.add(((v shr 24) and 0xFF).toByte())
        buf.add(((v shr 16) and 0xFF).toByte())
        buf.add(((v shr  8) and 0xFF).toByte())
        buf.add(( v         and 0xFF).toByte())
    }
    fun appendU64BE(v: Long) {
        for (i in 7 downTo 0) buf.add(((v shr (i * 8)) and 0xFF).toByte())
    }
    fun appendBytes(b: ByteArray)   { for (x in b) buf.add(x) }
    fun toByteArray(): ByteArray    = buf.toByteArray()
}

private class Reader(private val data: ByteArray) {
    private var pos = 0
    /** Bytes not yet consumed — an upper bound on remaining container elements. */
    fun remaining(): Int = data.size - pos
    fun readU8(): Int = (data[pos++].toInt() and 0xFF)
    fun readI8(): Int = data[pos++].toInt()
    fun readU16BE(): Int = (readU8() shl 8) or readU8()
    fun readI16BE(): Int { val v = readU16BE(); return if (v >= 0x8000) v - 0x10000 else v }
    fun readU32BE(): Long {
        val a = readU8().toLong(); val b = readU8().toLong()
        val c = readU8().toLong(); val d = readU8().toLong()
        return (a shl 24) or (b shl 16) or (c shl 8) or d
    }
    fun readI32BE(): Int = readU32BE().toInt()
    fun readU64BE(): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or readU8().toLong()
        return v
    }
    fun readBytes(n: Int): ByteArray {
        val out = data.copyOfRange(pos, pos + n)
        pos += n
        return out
    }
}

private fun Long.toIntChecked(): Int {
    if (this !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
        throw IllegalArgumentException("Length $this exceeds Int")
    }
    return this.toInt()
}
