package io.github.thatsfguy.reticulum.protocol

// Packet types
const val PACKET_DATA      = 0x00
const val PACKET_ANNOUNCE  = 0x01
const val PACKET_LINKREQ   = 0x02
const val PACKET_PROOF     = 0x03

// Header types
const val HEADER_1 = 0x00  // normal
const val HEADER_2 = 0x01  // transport-forwarded

// Destination types
const val DEST_SINGLE = 0x00
const val DEST_GROUP  = 0x01
const val DEST_PLAIN  = 0x02
const val DEST_LINK   = 0x03

// Transport types
const val TRANSPORT_BROADCAST = 0x00
const val TRANSPORT_TRANSPORT = 0x01

// Sizes
const val TRUNCATED_HASHLENGTH = 16  // 128 bits
const val NAME_HASH_LENGTH     = 10  // 80 bits
const val KEYSIZE              = 64  // 32 X25519 + 32 Ed25519
const val SIGLENGTH            = 64  // Ed25519 signature
const val MTU                  = 500
const val HEADER_MINSIZE       = 19  // flags(1) + hops(1) + dest(16) + context(1)
const val TOKEN_OVERHEAD       = 48  // 16 IV + 32 HMAC

// Link contexts
const val CTX_NONE         = 0x00
const val CTX_RESOURCE     = 0x01
const val CTX_RESOURCE_ADV = 0x02
const val CTX_RESOURCE_REQ = 0x03
const val CTX_RESOURCE_HMU = 0x04
const val CTX_RESOURCE_PRF = 0x05
const val CTX_REQUEST      = 0x09
const val CTX_RESPONSE     = 0x0A

/** Set on the OUTER packet of an announce that's a reply to a `path?`
 *  request, NOT on regular periodic re-announces. Per upstream
 *  `RNS/Destination.py::announce(path_response=True)`:
 *
 *    `if path_response: announce_context = RNS.Packet.PATH_RESPONSE`
 *
 *  This is the only wire-byte difference between a path response and
 *  a regular re-announce. Receivers (`RNS/Transport.py:1632-1639`)
 *  bypass ingress rate-limiting for packets carrying this context
 *  because the dest_hash is in `Transport.path_requests` from the
 *  earlier outbound `path?`. Without it, a re-announce sent in
 *  response to a flood of path? requests can be rate-limited and
 *  silently dropped at transit nodes — even though the path table
 *  is what the requester actually needs updated.
 *
 *  Verified by reticulum-specifications/flows/path-discovery.md §6
 *  against RNS 1.2.0. */
const val CTX_PATH_RESPONSE = 0x0B
const val CTX_KEEPALIVE    = 0xFA
const val CTX_LINKIDENTIFY = 0xFB
const val CTX_LINKCLOSE    = 0xFC
const val CTX_LRRTT        = 0xFE
const val CTX_LRPROOF      = 0xFF

// Message retry
const val MSG_MAX_ATTEMPTS = 3
val MSG_BACKOFF_MS = longArrayOf(5_000, 15_000, 60_000)
const val MSG_RETRY_TICK_MS = 5_000L

// Link establishment retry — match LXMF/Sideband's
// MAX_DELIVERY_ATTEMPTS=5 / DELIVERY_RETRY_WAIT=10s. Without this loop
// a single TX/RX collision on the LINKREQUEST or LRPROOF burns the
// whole attempt and the message immediately falls back to opportunistic.
// LoRa half-duplex regularly produces such collisions on a busy mesh.
const val LINK_MAX_ATTEMPTS = 5
const val LINK_RETRY_INTERVAL_MS: Long = 10_000L

// Path-pin staleness window — within this window of last hearing the
// best (lowest-hop) path, ignore worse paths' overwrites. After the
// window passes, any fresh announce is accepted (mobility). Sized to
// the standard ~5 min announce re-broadcast cadence so an active
// peer's good path always refreshes inside the window.
const val PATH_STALE_MS: Long = 5 * 60_000L

val PACKET_TYPE_NAMES = arrayOf("DATA", "ANNOUNCE", "LINKREQ", "PROOF")
val DEST_TYPE_NAMES   = arrayOf("SINGLE", "GROUP", "PLAIN", "LINK")
