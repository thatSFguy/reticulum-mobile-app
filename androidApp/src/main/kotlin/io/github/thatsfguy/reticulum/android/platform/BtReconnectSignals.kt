package io.github.thatsfguy.reticulum.android.platform

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * OS-signal-driven reconnect helpers. Instead of blindly retrying on a
 * timer, the reconnect supervisor parks here until the saved device is
 * actually reachable again — reconnecting the instant it reappears and
 * using essentially no radio while it's absent.
 *
 * Each function returns `true` if the device signal fired, `false` if the
 * [timeoutMs] fallback elapsed first. Either way the caller should attempt
 * a reconnect; the boolean is just for logging and for widening the
 * fallback cap (the signal short-circuits the wait, so the timer can be
 * long without hurting responsiveness). All scans/receivers are stopped
 * on success, timeout, or coroutine cancellation.
 */
object BtReconnectSignals {

    /**
     * Suspend until the saved BLE [address] is next seen advertising, or
     * [timeoutMs] elapses. Uses a LOW_POWER filtered scan (battery — this
     * is a background wait, not the foreground picker's LOW_LATENCY).
     * Caller must hold BLUETOOTH_SCAN (the supervisor checks before
     * starting).
     */
    @SuppressLint("MissingPermission")
    suspend fun awaitBleAdvertisement(context: Context, address: String, timeoutMs: Long): Boolean {
        val scanner = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager)
            .adapter?.bluetoothLeScanner ?: return false
        var cb: ScanCallback? = null
        return try {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine<Unit> { cont ->
                    val callback = object : ScanCallback() {
                        override fun onScanResult(callbackType: Int, result: ScanResult) {
                            if (cont.isActive) cont.resume(Unit)
                        }
                        override fun onScanFailed(errorCode: Int) {
                            // Can't scan right now (e.g. scan-throttled). Don't
                            // wedge the reconnect — resume so the caller retries;
                            // its growing fallback cap prevents a hot loop.
                            if (cont.isActive) cont.resume(Unit)
                        }
                    }
                    cb = callback
                    val filters = listOf(ScanFilter.Builder().setDeviceAddress(address).build())
                    val settings = ScanSettings.Builder()
                        .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                        .build()
                    val started = runCatching { scanner.startScan(filters, settings, callback) }.isSuccess
                    if (!started && cont.isActive) cont.resume(Unit)
                }
            } != null
        } finally {
            cb?.let { runCatching { scanner.stopScan(it) } }
        }
    }

    /**
     * Suspend until an ACL_CONNECTED or BOND_STATE→BONDED broadcast
     * arrives for the saved Classic [address], or [timeoutMs] elapses.
     * Cheaper than polling — a BroadcastReceiver wakeup costs nothing
     * while the device is absent.
     */
    suspend fun awaitBtClassicAvailable(context: Context, address: String, timeoutMs: Long): Boolean {
        var receiver: BroadcastReceiver? = null
        return try {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine<Unit> { cont ->
                    val r = object : BroadcastReceiver() {
                        override fun onReceive(c: Context, intent: Intent) {
                            @Suppress("DEPRECATION")
                            val dev = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                            if (dev?.address?.equals(address, ignoreCase = true) != true) return
                            val connected = intent.action == BluetoothDevice.ACTION_ACL_CONNECTED
                            val bonded = intent.action == BluetoothDevice.ACTION_BOND_STATE_CHANGED &&
                                intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1) == BluetoothDevice.BOND_BONDED
                            if ((connected || bonded) && cont.isActive) cont.resume(Unit)
                        }
                    }
                    receiver = r
                    val filter = IntentFilter().apply {
                        addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                        addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                    }
                    // RECEIVER_NOT_EXPORTED: we only ever consume protected
                    // system broadcasts (ACL_CONNECTED / BOND_STATE_CHANGED),
                    // never anything from other apps. Explicit + required on
                    // API 34+.
                    val ok = runCatching {
                        ContextCompat.registerReceiver(
                            context, r, filter, ContextCompat.RECEIVER_NOT_EXPORTED,
                        )
                    }.isSuccess
                    if (!ok && cont.isActive) cont.resume(Unit)
                }
            } != null
        } finally {
            receiver?.let { runCatching { context.unregisterReceiver(it) } }
        }
    }
}
