package io.github.thatsfguy.reticulum.util

/**
 * Short, non-wrapping fingerprint of a destination / identity hash for
 * list rows — first 8 + last 8 hex characters joined by an ellipsis
 * (e.g. `7579c857…d75a3315`). Enough to recognise a peer at a glance
 * without the full 32-char hash wrapping across multiple lines.
 *
 * The full hash stays available — and copyable — in the destination
 * detail sheet. See docs/REDESIGN.md §4.
 */
fun shortHash(hash: String): String =
    if (hash.length <= 17) hash else "${hash.take(8)}…${hash.takeLast(8)}"
