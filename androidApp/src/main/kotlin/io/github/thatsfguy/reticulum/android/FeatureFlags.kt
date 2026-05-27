package io.github.thatsfguy.reticulum.android

/**
 * Compile-time feature gates. Toggle these to ship or hide code that
 * lives in the tree but isn't ready for users (or has been
 * deliberately retired without removing the implementation).
 *
 * Kept as `const val` rather than runtime preferences so users can't
 * stumble into a half-baked feature via Settings — flipping a flag
 * requires a code change and a rebuild.
 */
object FeatureFlags {

    /** reticulum-loramesh BLE transport (custom KISS dialect, Samsung
     *  workarounds, 5-byte DATA_RX metadata header). Implementation
     *  stays compiled so re-enabling is one boolean flip away, but
     *  the Settings entry point and the restore-on-launch branch are
     *  hidden while this is false. */
    const val LORAMESH_ENABLED = false
}
