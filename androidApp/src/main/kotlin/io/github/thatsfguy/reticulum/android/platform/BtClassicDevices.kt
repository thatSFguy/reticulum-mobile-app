package io.github.thatsfguy.reticulum.android.platform

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * One paired Bluetooth Classic device the user could connect to.
 *
 * Pure-LE devices are filtered out — they belong in the BLE picker and
 * have no SPP service to RFCOMM-connect to anyway.
 */
data class BondedDevice(
    val name: String?,
    val address: String,
    val type: Int,
)

/**
 * Bonded (paired) Classic-capable Bluetooth devices.
 *
 * Pairing flow lives in Android system Settings — the user pairs the
 * RNode there, then this list shows it. We deliberately don't run
 * discovery here; doing so adds noise from random nearby phones/audio
 * gear and forces a system pair-confirm dialog mid-connect.
 */
object BtClassicDevices {

    @SuppressLint("MissingPermission")
    fun bonded(context: Context): List<BondedDevice> {
        // BLUETOOTH_CONNECT is the runtime gate on Android 12+. Without
        // it `bondedDevices` throws SecurityException; on older OS the
        // legacy BLUETOOTH permission is normal-protection (granted at
        // install) so no runtime check is needed.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_CONNECT,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return emptyList()
        }

        val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = mgr.adapter ?: return emptyList()
        val bonded = adapter.bondedDevices ?: return emptyList()

        return bonded
            .filter { dev ->
                // Skip pure-LE entries — those won't have an SPP service
                // and would just confuse the user. DUAL devices stay in
                // the list because they may speak both.
                dev.type == BluetoothDevice.DEVICE_TYPE_CLASSIC ||
                    dev.type == BluetoothDevice.DEVICE_TYPE_DUAL
            }
            .map { dev ->
                BondedDevice(
                    name = dev.name,
                    address = dev.address,
                    type = dev.type,
                )
            }
            .sortedBy { it.name?.lowercase() ?: "zz_${it.address}" }
    }
}
