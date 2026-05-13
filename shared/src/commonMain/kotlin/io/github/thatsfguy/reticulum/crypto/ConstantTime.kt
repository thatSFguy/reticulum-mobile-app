package io.github.thatsfguy.reticulum.crypto

/**
 * Constant-time byte-array equality. `ByteArray.contentEquals`
 * (and the equivalent `Arrays.equals` / `memcmp` in lower layers)
 * short-circuit on the first mismatched byte, which leaks timing
 * information about WHERE in the buffer the mismatch occurred.
 * For HMAC / signature / passphrase comparisons that observe
 * attacker-controlled input, the leak can let an attacker iteratively
 * guess one byte at a time by measuring per-call latency.
 *
 * This helper does a full-buffer XOR-fold so the time taken is
 * independent of where (or whether) the bytes differ. Lengths are
 * compared up front because that's never sensitive in our threat
 * model — the attacker already knows the expected HMAC length
 * (32 bytes for HMAC-SHA256).
 *
 * Used by:
 *  - [TokenCrypto] for inbound LXMF HMAC verification (§3 spec)
 *  - [IdentityArchive] for `.rmid` archive HMAC verification
 *
 * Audit reference: 2026-05-13 LOW-7. The realistic exploitability
 * over noisy LoRa / BLE / TCP transport is low — radio jitter
 * dominates any single-byte timing signal — but using the safe
 * primitive everywhere is hygiene, and the cost is negligible
 * (a 32-byte XOR loop runs in nanoseconds).
 */
internal fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
    if (a.size != b.size) return false
    var diff = 0
    for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
    return diff == 0
}
