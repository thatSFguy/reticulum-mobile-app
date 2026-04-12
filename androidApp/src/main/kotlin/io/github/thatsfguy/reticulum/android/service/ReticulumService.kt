package io.github.thatsfguy.reticulum.android.service

/**
 * Foreground service that maintains the BLE connection to an RNode
 * modem independently of the Activity lifecycle. This is the primary
 * reason for the native rewrite — the Capacitor WebView suspends JS
 * when backgrounded, so no packets are received while the app is not
 * in the foreground.
 *
 * Responsibilities:
 *   1. Own the BluetoothGatt connection to the RNode.
 *   2. Run the KISS parser on incoming BLE notifications.
 *   3. Check each Reticulum packet's destination_hash against the
 *      user's destination hash (a simple 16-byte comparison — no
 *      crypto needed at this layer).
 *   4. When a match is found, fire an Android notification with
 *      sound + vibration and a tap-action that brings the Activity
 *      to the foreground.
 *   5. Buffer matched packets. When the Activity binds to the
 *      service, drain the buffer into the UI layer for decryption
 *      and display.
 *   6. Handle BLE reconnection if the RNode drops (scan + auto-
 *      reconnect with exponential backoff).
 *   7. Survive Doze mode: request the user to exempt the app from
 *      battery optimization, or use setExactAlarm for periodic
 *      reconnection checks.
 *
 * Lifecycle:
 *   - Started by the Activity when the user clicks Connect.
 *   - Runs as a foreground service with a persistent notification
 *     ("Reticulum — listening for messages").
 *   - Activity binds/unbinds as it comes and goes.
 *   - Stopped explicitly by the user clicking Disconnect, or when
 *     the BLE connection is lost and all reconnection attempts are
 *     exhausted.
 *
 * Notification channel:
 *   - Channel ID: "reticulum_service" (persistent service notification)
 *   - Channel ID: "reticulum_messages" (incoming message alerts)
 *
 * TODO: Implement. Key Android APIs:
 *   - android.app.Service + startForeground()
 *   - android.bluetooth.BluetoothGatt + BluetoothGattCallback
 *   - android.app.NotificationManager + NotificationChannel
 *   - android.content.ServiceConnection (for Activity ↔ Service binding)
 *   - io.github.thatsfguy.reticulum.transport.KissParser
 *   - io.github.thatsfguy.reticulum.protocol.parsePacket()
 */
// class ReticulumService : android.app.Service() { ... }
