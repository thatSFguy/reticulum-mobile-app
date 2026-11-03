package io.github.thatsfguy.reticulum.transport

/**
 * A TCP client interface as written in a Reticulum config file — the block
 * node operators hand out (often as a QR code) so a client can join:
 *
 * ```
 * [[Some Node]]
 *   type = TCPClientInterface
 *   enabled = yes
 *   target_host = node.example.net
 *   target_port = 4242
 *   network_name = example-net
 *   passphrase = ...
 *   ifac_size = 64
 * ```
 *
 * Key names and units follow upstream `Reticulum._add_interface`:
 * `network_name`/`networkname`, `passphrase`/`pass_phrase`, and `ifac_size`
 * in BITS (upstream takes `ifac_size // 8` when it's at least
 * `IFAC_MIN_SIZE * 8`, and otherwise uses the interface default).
 */
data class TcpInterfaceConfig(
    val name: String?,
    val host: String,
    val port: Int,
    val networkName: String?,
    val passphrase: String?,
    /** IFAC tag size in bytes, or null for the TCP default when IFAC is on. */
    val ifacSizeBytes: Int?,
) {
    val hasIfac: Boolean get() = !networkName.isNullOrEmpty() || !passphrase.isNullOrEmpty()

    /** Tag size to use when [hasIfac]: the configured one, else upstream's
     *  TCPInterface.DEFAULT_IFAC_SIZE (16 bytes). */
    val effectiveIfacSizeBytes: Int get() = ifacSizeBytes ?: DEFAULT_TCP_IFAC_SIZE

    override fun toString(): String =
        "TcpInterfaceConfig(name=$name, host=$host, port=$port, ifac=${if (hasIfac) "set" else "off"})"

    companion object {
        const val DEFAULT_TCP_IFAC_SIZE = 16
    }
}

/**
 * Parse one TCP client interface block. The text is UNTRUSTED (it usually
 * comes from a scanned QR code), so this is strict: exactly one interface,
 * `type = TCPClientInterface`, a sane host and a port in 1..65535, bounded
 * field lengths. Returns null for anything else — including other
 * interface types, which this app can't attach as TCP.
 */
fun parseTcpInterfaceConfig(text: String): TcpInterfaceConfig? {
    if (text.length > MAX_TEXT) return null
    var name: String? = null
    var headers = 0
    val kv = mutableMapOf<String, String>()
    for (rawLine in text.lineSequence()) {
        val line = rawLine.trim()
        if (line.isEmpty() || line.startsWith("#")) continue
        if (line.startsWith("[")) {
            // `[interfaces]` is the parent section in a full config; only
            // `[[...]]` names an interface.
            if (line.startsWith("[[") && line.endsWith("]]")) {
                if (++headers > 1) return null
                name = line.removePrefix("[[").removeSuffix("]]").trim().takeIf { it.isNotEmpty() }
            }
            continue
        }
        val eq = line.indexOf('=')
        if (eq <= 0) continue
        val key = line.substring(0, eq).trim().lowercase()
        val value = line.substring(eq + 1).trim()
        if (value.length > MAX_VALUE) return null
        kv[key] = value
    }

    if (kv["type"]?.lowercase() != "tcpclientinterface") return null
    val host = kv["target_host"]?.takeIf { isPlausibleHost(it) } ?: return null
    val port = kv["target_port"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
    val networkName = (kv["network_name"] ?: kv["networkname"])?.takeIf { it.isNotEmpty() && it != "None" }
    val passphrase = (kv["passphrase"] ?: kv["pass_phrase"])?.takeIf { it.isNotEmpty() && it != "None" }
    val ifacBits = kv["ifac_size"]?.toIntOrNull()
    val ifacBytes = ifacBits?.takeIf { it >= IfacKey.MIN_SIZE * 8 }?.div(8)?.coerceAtMost(IfacKey.MAX_SIZE)

    return TcpInterfaceConfig(
        name = name?.filter { !it.isISOControl() }?.take(64),
        host = host,
        port = port,
        networkName = networkName,
        passphrase = passphrase,
        ifacSizeBytes = ifacBytes,
    )
}

private const val MAX_TEXT = 4096
private const val MAX_VALUE = 512

/** Hostname, IPv4 or bracket-less IPv6 — no spaces, schemes, paths or ports. */
private fun isPlausibleHost(h: String): Boolean =
    h.length in 1..253 && h.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == ':' }
