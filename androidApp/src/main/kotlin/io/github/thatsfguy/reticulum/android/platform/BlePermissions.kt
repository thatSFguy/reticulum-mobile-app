package io.github.thatsfguy.reticulum.android.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Tracks the permissions needed to run a BLE-attached transport plus the
 * incoming-message notification channel.
 *
 * Android 12+ split BLUETOOTH_ADMIN/BLUETOOTH into BLUETOOTH_SCAN and
 * BLUETOOTH_CONNECT, and Android 13+ requires POST_NOTIFICATIONS for
 * notifications.
 */
object BlePermissions {

    /** Runtime permissions to request before connecting BLE on the active OS. */
    fun required(): Array<String> {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms += Manifest.permission.BLUETOOTH_SCAN
            perms += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms += Manifest.permission.POST_NOTIFICATIONS
        }
        return perms.toTypedArray()
    }

    fun allGranted(context: Context): Boolean = required().all { p ->
        ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    }

    fun missing(context: Context): List<String> = required().filter { p ->
        ContextCompat.checkSelfPermission(context, p) != PackageManager.PERMISSION_GRANTED
    }
}
