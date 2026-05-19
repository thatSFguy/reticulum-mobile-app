package io.github.thatsfguy.reticulum.android.platform

import com.journeyapps.barcodescanner.CaptureActivity

/**
 * QR-scan capture activity pinned to portrait.
 *
 * zxing-android-embedded's stock [CaptureActivity] follows the sensor:
 * `ScanOptions.setOrientationLocked(true)` only re-locks to whatever
 * orientation the device happened to be in *at launch*, so holding the
 * phone in landscape (or scanning right after a landscape app) opened
 * the scanner sideways. Forcing `screenOrientation="portrait"` on this
 * subclass in the manifest makes the camera preview always upright —
 * the Android counterpart of the iOS QR-scanner portrait lock
 * (docs/REDESIGN.md §10).
 *
 * Pure subclass — no behaviour change beyond the manifest orientation
 * attribute. `ScanOptions.setCaptureActivity(...)` points the scan at
 * it; see `NodesScreen.launchScan()`.
 */
class PortraitCaptureActivity : CaptureActivity()
