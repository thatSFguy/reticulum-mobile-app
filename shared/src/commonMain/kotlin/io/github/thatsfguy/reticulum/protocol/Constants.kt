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
const val CTX_NONE      = 0x00
const val CTX_KEEPALIVE = 0xFA
const val CTX_LINKCLOSE = 0xFC
const val CTX_LRRTT     = 0xFE
const val CTX_LRPROOF   = 0xFF

// Message retry
const val MSG_MAX_ATTEMPTS = 3
val MSG_BACKOFF_MS = longArrayOf(5_000, 15_000, 60_000)
const val MSG_RETRY_TICK_MS = 5_000L

val PACKET_TYPE_NAMES = arrayOf("DATA", "ANNOUNCE", "LINKREQ", "PROOF")
val DEST_TYPE_NAMES   = arrayOf("SINGLE", "GROUP", "PLAIN", "LINK")
