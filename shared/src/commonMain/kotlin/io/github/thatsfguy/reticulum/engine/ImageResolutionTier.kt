package io.github.thatsfguy.reticulum.engine

/**
 * Quality tier the user picks in the compose-row "+" → Photo flow
 * before an image attachment is sent.
 *
 * Each tier names a byte budget the platform compressor
 * (`ImageCompress` on Android / iOS) must land the JPEG within by
 * walking a dimension + quality ladder. [MICRO] is the historical
 * single-tier behavior — LoRa-safe airtime; [FULL] is a high-quality
 * JPEG hard-capped at the [INBOUND_ATTACHMENT_MAX_BYTES] receive
 * ceiling (a larger payload our own receiver would drop). "Full" is
 * therefore not the untouched original file — to send that verbatim,
 * the user attaches it as a File instead.
 *
 * The budgets live here in `commonMain` so Android and iOS agree on
 * one source of truth and the Swift side gets them auto-bridged. The
 * per-tier pixel-dimension / quality ladder stays platform-side
 * because it touches the platform bitmap APIs.
 */
enum class ImageResolutionTier(val byteBudget: Int, val label: String) {
    /** Highest quality, capped at the 4 MB receive ceiling. */
    FULL(INBOUND_ATTACHMENT_MAX_BYTES, "Full"),

    /** ≤ 500 KB — good detail; reasonable over a TCP-attached mesh. */
    MEDIUM(500 * 1024, "Medium"),

    /** ≤ 100 KB — legible, modest airtime. */
    SMALL(100 * 1024, "Small"),

    /** ≤ 20 KB — the original LoRa-safe tier; today's default ladder. */
    MICRO(20 * 1024, "Micro"),
}
