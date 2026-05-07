package io.github.thatsfguy.reticulum.interop

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Live interop harness against upstream Python RNS / LXMF, ported from
 * `reticulum-forwarding-service/tests/interop/`. Spawns a Python helper
 * subprocess and exchanges newline-delimited JSON ops with it so JVM
 * unit tests can exchange real wire bytes with the canonical
 * implementation.
 *
 * Tests should call [startOrSkip] and use the returned peer in a
 * `peer.use { ... }` block. If `python` isn't on PATH or `rns` /
 * `lxmf` aren't installed, [startOrSkip] returns null and the caller
 * should `assumeTrue(peer != null)` to skip cleanly rather than fail —
 * we don't want CI builds to fail on machines without the upstream
 * Python install.
 *
 * The JSON used between this side and `python_peer.py` is flat — the
 * helper only emits objects with string/number/boolean values, no
 * nested objects, no arrays. We hand-roll the encoder/decoder to keep
 * the test source set free of a serialization library.
 */
class PythonPeer private constructor(
    private val process: Process,
    private val writer: BufferedWriter,
    private val reader: BufferedReader,
    private val tempScript: File,
) : Closeable {

    /**
     * Send one op to the Python helper and wait for its response. Returns
     * the decoded response object minus the `ok` field. Throws if
     * `ok = false` (the Python side caught an exception, message
     * comes back in the `error` field).
     */
    @Synchronized
    fun call(op: String, args: Map<String, Any?> = emptyMap()): Map<String, Any?> {
        val req = LinkedHashMap<String, Any?>().apply {
            put("op", op)
            putAll(args)
        }
        writer.write(encodeJson(req))
        writer.newLine()
        writer.flush()

        val line = reader.readLine()
            ?: error("python peer closed stdout (process alive=${process.isAlive})")
        val resp = decodeJsonObject(line)
        if (resp["ok"] != true) {
            error("python op '$op' failed: ${resp["error"]}")
        }
        return resp
    }

    override fun close() {
        runCatching { writer.close() }
        runCatching { process.waitFor() }
        runCatching { tempScript.delete() }
    }

    companion object {
        /**
         * Try to start a Python interop peer. Returns null if Python
         * isn't on PATH, the helper script can't be located on the test
         * classpath, the subprocess fails to start, or `rns` / `lxmf`
         * aren't importable. Caller should use kotlin.test.assertTrue or
         * JUnit Assume.assumeTrue to skip the test on null.
         */
        fun startOrSkip(): PythonPeer? {
            val pythonExe = findPython() ?: run {
                println("[interop] python not on PATH — skipping")
                return null
            }
            val script = extractScript() ?: run {
                println("[interop] python_peer.py not on test classpath — skipping")
                return null
            }
            val pb = ProcessBuilder(pythonExe, script.absolutePath)
                .redirectErrorStream(false)
            val proc = try {
                pb.start()
            } catch (e: Exception) {
                println("[interop] failed to spawn python: ${e.message}")
                script.delete()
                return null
            }

            val writer = BufferedWriter(OutputStreamWriter(proc.outputStream, StandardCharsets.UTF_8))
            val reader = BufferedReader(InputStreamReader(proc.inputStream, StandardCharsets.UTF_8))
            val peer = PythonPeer(proc, writer, reader, script)

            // Probe with init_rns. If rns/lxmf aren't installed, the
            // helper crashes on import before we get a response — drain
            // stderr so the user sees pip install hints, and skip.
            return try {
                peer.call("init_rns")
                peer
            } catch (e: Exception) {
                val stderr = runCatching {
                    proc.errorStream.bufferedReader().readText().trim()
                }.getOrDefault("")
                println("[interop] python helper failed init_rns: ${e.message}")
                if (stderr.isNotEmpty()) println("[interop] python stderr:\n$stderr")
                println("[interop] hint: pip install rns lxmf")
                peer.close()
                null
            }
        }

        private fun findPython(): String? {
            val candidates = listOf("python3", "python", "py")
            for (exe in candidates) {
                val pb = ProcessBuilder(exe, "--version").redirectErrorStream(true)
                val ok = try {
                    val p = pb.start()
                    p.waitFor() == 0
                } catch (_: Exception) { false }
                if (ok) return exe
            }
            return null
        }

        private fun extractScript(): File? {
            val stream = PythonPeer::class.java.classLoader
                ?.getResourceAsStream("python_peer.py")
                ?: return null
            val temp = Files.createTempFile("rns-interop-", ".py").toFile()
            temp.deleteOnExit()
            stream.use { input ->
                Files.copy(input, temp.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            return temp
        }
    }
}

// --- JSON minimal codec --------------------------------------------------
// Handles the flat object shape python_peer.py uses. Values may be
// String, Long, Double, Boolean, or null. Lists and nested objects are
// not supported on either direction; if we ever need them, swap to
// kotlinx-serialization-json — call sites only see encodeJson /
// decodeJsonObject.

internal fun encodeJson(value: Any?): String = buildString { writeJson(this, value) }

private fun writeJson(out: StringBuilder, v: Any?) {
    when (v) {
        null            -> out.append("null")
        is Boolean      -> out.append(if (v) "true" else "false")
        is Number       -> out.append(v.toString())
        is String       -> writeJsonString(out, v)
        is Map<*, *>    -> {
            out.append('{')
            var first = true
            for ((k, vv) in v) {
                if (!first) out.append(',')
                first = false
                writeJsonString(out, k.toString())
                out.append(':')
                writeJson(out, vv)
            }
            out.append('}')
        }
        else -> throw IllegalArgumentException("Unsupported JSON value: ${v::class.simpleName}")
    }
}

private fun writeJsonString(out: StringBuilder, s: String) {
    out.append('"')
    for (c in s) when (c) {
        '"'  -> out.append("\\\"")
        '\\' -> out.append("\\\\")
        '\n' -> out.append("\\n")
        '\r' -> out.append("\\r")
        '\t' -> out.append("\\t")
        else -> if (c < ' ') out.append("\\u").append(c.code.toString(16).padStart(4, '0')) else out.append(c)
    }
    out.append('"')
}

internal fun decodeJsonObject(text: String): Map<String, Any?> {
    val r = JsonReader(text)
    r.skipWs()
    val v = r.readValue()
    r.skipWs()
    require(r.atEnd()) { "trailing content at ${r.pos}: ${text.substring(r.pos).take(40)}" }
    @Suppress("UNCHECKED_CAST")
    return v as? Map<String, Any?> ?: error("expected object, got ${v?.let { it::class.simpleName }}")
}

private class JsonReader(private val src: String) {
    var pos = 0
    fun atEnd() = pos >= src.length
    fun skipWs() { while (pos < src.length && src[pos].isWhitespace()) pos++ }

    fun readValue(): Any? {
        skipWs()
        if (atEnd()) error("unexpected EOF")
        return when (src[pos]) {
            '{'  -> readObject()
            '['  -> readArray()
            '"'  -> readString()
            't', 'f' -> readBool()
            'n'  -> readNull()
            '-', in '0'..'9' -> readNumber()
            else -> error("unexpected char '${src[pos]}' at $pos")
        }
    }

    private fun readObject(): Map<String, Any?> {
        expect('{')
        val out = LinkedHashMap<String, Any?>()
        skipWs()
        if (peek() == '}') { pos++; return out }
        while (true) {
            skipWs()
            val k = readString()
            skipWs()
            expect(':')
            val v = readValue()
            out[k] = v
            skipWs()
            when (val c = src[pos]) {
                ','  -> { pos++; continue }
                '}'  -> { pos++; return out }
                else -> error("expected ',' or '}' at $pos got '$c'")
            }
        }
    }

    private fun readArray(): List<Any?> {
        expect('[')
        val out = ArrayList<Any?>()
        skipWs()
        if (peek() == ']') { pos++; return out }
        while (true) {
            out.add(readValue())
            skipWs()
            when (val c = src[pos]) {
                ','  -> { pos++; continue }
                ']'  -> { pos++; return out }
                else -> error("expected ',' or ']' at $pos got '$c'")
            }
        }
    }

    private fun readString(): String {
        expect('"')
        val sb = StringBuilder()
        while (pos < src.length) {
            val c = src[pos++]
            if (c == '"') return sb.toString()
            if (c == '\\') {
                val esc = src[pos++]
                when (esc) {
                    '"'  -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/'  -> sb.append('/')
                    'b'  -> sb.append('\b')
                    'f'  -> sb.append('')
                    'n'  -> sb.append('\n')
                    'r'  -> sb.append('\r')
                    't'  -> sb.append('\t')
                    'u'  -> {
                        val hex = src.substring(pos, pos + 4)
                        pos += 4
                        sb.append(hex.toInt(16).toChar())
                    }
                    else -> error("bad escape \\$esc at $pos")
                }
            } else sb.append(c)
        }
        error("unterminated string")
    }

    private fun readBool(): Boolean = when {
        src.startsWith("true", pos)  -> { pos += 4; true }
        src.startsWith("false", pos) -> { pos += 5; false }
        else -> error("expected bool at $pos")
    }

    private fun readNull(): Any? {
        require(src.startsWith("null", pos)) { "expected null at $pos" }
        pos += 4
        return null
    }

    private fun readNumber(): Any {
        val start = pos
        if (src[pos] == '-') pos++
        while (pos < src.length && src[pos] in "0123456789") pos++
        var isFloat = false
        if (pos < src.length && src[pos] == '.') { isFloat = true; pos++; while (pos < src.length && src[pos] in "0123456789") pos++ }
        if (pos < src.length && (src[pos] == 'e' || src[pos] == 'E')) {
            isFloat = true; pos++
            if (pos < src.length && (src[pos] == '+' || src[pos] == '-')) pos++
            while (pos < src.length && src[pos] in "0123456789") pos++
        }
        val s = src.substring(start, pos)
        return if (isFloat) s.toDouble() else s.toLong()
    }

    private fun expect(c: Char) {
        skipWs()
        require(pos < src.length && src[pos] == c) { "expected '$c' at $pos got '${src.getOrNull(pos)}'" }
        pos++
    }

    private fun peek(): Char? = if (pos < src.length) src[pos] else null
}
