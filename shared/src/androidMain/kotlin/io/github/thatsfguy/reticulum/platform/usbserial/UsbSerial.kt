package io.github.thatsfguy.reticulum.platform.usbserial

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface

/**
 * Minimal, self-contained USB-serial layer for talking to a USB-attached
 * RNode (issue #41). This is a deliberately small re-derivation of the
 * public USB-serial protocols — NOT a vendored copy of any third-party
 * library — covering the two chip families most RNode boards expose:
 *
 *   - **CDC-ACM** (USB Communications Device Class) — native-USB ESP32-S3 /
 *     ESP32-C3 RNodes and any standards-compliant USB-serial device.
 *   - **CP210x** (Silicon Labs CP2102 / CP2104) — the most common USB-UART
 *     bridge on T-Beam / Heltec-style RNode boards.
 *
 * **EXPERIMENTAL — not yet verified against hardware.** USB endpoint I/O
 * cannot be unit-tested (no device in CI), so per CLAUDE.md RULE #1 this
 * ships behind the `usbEnabled` toggle (default off) and must be confirmed
 * on a real RNode over a USB-OTG cable before it's trusted. CH34x (CH340 /
 * CH9102) and FTDI bridges are intentionally NOT implemented here yet —
 * their init sequences are arcane enough that adding them blind would be
 * more likely to garble than to work; add them in the device-in-hand pass.
 *
 * Constants below are the well-documented public protocol values for each
 * chip (USB CDC spec §6.3; Silicon Labs AN571 for CP210x) — cited inline.
 */

/** RNode firmware speaks 115200 8-N-1 over its USB serial line. */
const val RNODE_USB_BAUD = 115_200

/** A claimed, configured serial port over an open [UsbDeviceConnection]. */
interface UsbSerialPort {
    /** Claim the interface, locate bulk endpoints, and run chip-specific
     *  init (line coding / baud). Returns false if the device shape didn't
     *  match what this driver expects. */
    fun open(): Boolean

    /** Blocking bulk read into [dest]. Returns bytes read (>0), 0 on
     *  timeout (no data), or <0 on error / device gone. */
    fun read(dest: ByteArray, timeoutMs: Int): Int

    /** Blocking bulk write of [src]. Chunks to the endpoint's max packet
     *  size. Throws on a failed transfer. */
    fun write(src: ByteArray, timeoutMs: Int)

    fun close()
}

/**
 * Shared bulk-endpoint plumbing. Subclasses pick the interface + run the
 * chip init in [open]; everything else (read/write/close) is common.
 */
abstract class CommonUsbSerialPort(
    protected val device: UsbDevice,
    protected val connection: UsbDeviceConnection,
) : UsbSerialPort {

    protected var controlInterface: UsbInterface? = null
    protected var dataInterface: UsbInterface? = null
    protected var readEndpoint: UsbEndpoint? = null
    protected var writeEndpoint: UsbEndpoint? = null

    /** Find the first bulk IN and bulk OUT endpoint on [iface]. */
    protected fun findBulkEndpoints(iface: UsbInterface) {
        for (i in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(i)
            if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
            if (ep.direction == UsbConstants.USB_DIR_IN) readEndpoint = ep
            else writeEndpoint = ep
        }
    }

    override fun read(dest: ByteArray, timeoutMs: Int): Int {
        val ep = readEndpoint ?: return -1
        return connection.bulkTransfer(ep, dest, dest.size, timeoutMs)
    }

    override fun write(src: ByteArray, timeoutMs: Int) {
        val ep = writeEndpoint ?: error("USB write endpoint not open")
        val max = ep.maxPacketSize.coerceAtLeast(1)
        var off = 0
        while (off < src.size) {
            val len = minOf(max, src.size - off)
            val chunk = if (off == 0 && len == src.size) src else src.copyOfRange(off, off + len)
            val n = connection.bulkTransfer(ep, chunk, len, timeoutMs)
            if (n < 0) error("USB bulk write failed at offset $off")
            off += if (n == 0) len else n
        }
    }

    override fun close() {
        runCatching { controlInterface?.let { connection.releaseInterface(it) } }
        runCatching { dataInterface?.let { connection.releaseInterface(it) } }
        runCatching { connection.close() }
    }
}

/**
 * CDC-ACM driver. Two interfaces: a Communications-class (0x02) control
 * interface carrying SET_LINE_CODING / SET_CONTROL_LINE_STATE, and a
 * CDC-Data-class (0x0A) interface with the bulk IN/OUT endpoints.
 * Some composite devices collapse both onto one interface; we handle both.
 *
 * Requests per the USB CDC spec (§6.3):
 *   SET_LINE_CODING        = 0x20  (host→device, 7-byte line-coding struct)
 *   SET_CONTROL_LINE_STATE = 0x22  (value bit0 = DTR, bit1 = RTS)
 *   bmRequestType for class/interface OUT = 0x21
 */
class CdcAcmSerialPort(
    device: UsbDevice,
    connection: UsbDeviceConnection,
    private val baud: Int,
) : CommonUsbSerialPort(device, connection) {

    override fun open(): Boolean {
        var control: UsbInterface? = null
        var data: UsbInterface? = null
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            when (iface.interfaceClass) {
                UsbConstants.USB_CLASS_COMM -> control = control ?: iface
                UsbConstants.USB_CLASS_CDC_DATA -> data = data ?: iface
            }
        }
        // Composite/merged fallback: a single interface that carries bulk
        // endpoints (some cheap CDC clones report everything on iface 0).
        if (data == null && device.interfaceCount > 0) {
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                var hasBulk = false
                for (e in 0 until iface.endpointCount) {
                    if (iface.getEndpoint(e).type == UsbConstants.USB_ENDPOINT_XFER_BULK) hasBulk = true
                }
                if (hasBulk) { data = iface; break }
            }
        }
        val dataIface = data ?: return false
        control?.let { if (!connection.claimInterface(it, true)) return false }
        if (!connection.claimInterface(dataIface, true)) return false
        controlInterface = control
        dataInterface = dataIface
        findBulkEndpoints(dataIface)
        if (readEndpoint == null || writeEndpoint == null) return false

        val controlIndex = control?.id ?: 0
        setLineCoding(baud, controlIndex)
        // DTR=1, RTS=1 — many USB-serial RNode bridges hold the MCU in
        // reset until DTR/RTS assert.
        connection.controlTransfer(0x21, 0x22, 0x03, controlIndex, null, 0, CONTROL_TIMEOUT_MS)
        return true
    }

    private fun setLineCoding(baudRate: Int, controlIndex: Int) {
        // 7-byte line coding: dwDTERate(4 LE) | bCharFormat(0=1 stop) |
        // bParityType(0=none) | bDataBits(8).
        val msg = byteArrayOf(
            (baudRate and 0xFF).toByte(),
            ((baudRate shr 8) and 0xFF).toByte(),
            ((baudRate shr 16) and 0xFF).toByte(),
            ((baudRate shr 24) and 0xFF).toByte(),
            0, 0, 8,
        )
        connection.controlTransfer(0x21, 0x20, 0, controlIndex, msg, msg.size, CONTROL_TIMEOUT_MS)
    }

    companion object { private const val CONTROL_TIMEOUT_MS = 2000 }
}

/**
 * Silicon Labs CP210x driver (CP2102 / CP2104, VID 0x10C4). Single vendor
 * interface; all config is vendor control transfers (bmRequestType 0x41),
 * per Silicon Labs AN571:
 *   IFC_ENABLE   = 0x00  (wValue 0x0001 enables the UART)
 *   SET_BAUDRATE = 0x1E  (4-byte LE baud in the data stage)
 *   SET_MHS      = 0x07  (wValue 0x0303 = DTR=1|RTS=1 with write-enable mask)
 */
class Cp21xxSerialPort(
    device: UsbDevice,
    connection: UsbDeviceConnection,
    private val baud: Int,
) : CommonUsbSerialPort(device, connection) {

    override fun open(): Boolean {
        if (device.interfaceCount < 1) return false
        val iface = device.getInterface(0)
        if (!connection.claimInterface(iface, true)) return false
        dataInterface = iface
        findBulkEndpoints(iface)
        if (readEndpoint == null || writeEndpoint == null) return false

        // IFC_ENABLE
        connection.controlTransfer(0x41, 0x00, 0x0001, 0, null, 0, CONTROL_TIMEOUT_MS)
        // SET_BAUDRATE (literal baud, 4-byte LE)
        val b = byteArrayOf(
            (baud and 0xFF).toByte(),
            ((baud shr 8) and 0xFF).toByte(),
            ((baud shr 16) and 0xFF).toByte(),
            ((baud shr 24) and 0xFF).toByte(),
        )
        connection.controlTransfer(0x41, 0x1E, 0, 0, b, b.size, CONTROL_TIMEOUT_MS)
        // SET_MHS: assert DTR + RTS (low byte = states, high byte = mask).
        connection.controlTransfer(0x41, 0x07, 0x0303, 0, null, 0, CONTROL_TIMEOUT_MS)
        return true
    }

    companion object { private const val CONTROL_TIMEOUT_MS = 2000 }
}

/**
 * Picks a driver for a [UsbDevice]. Extend the `when` as more chips are
 * verified on hardware (CH34x = VID 0x1A86, FTDI = VID 0x0403 are the next
 * candidates — left out until a device-in-hand pass can confirm their init
 * sequences). Returns null if no supported driver matches.
 */
object UsbSerialProber {

    /** VID/PIDs this build can drive, for the Add-node device filter. */
    fun isSupported(device: UsbDevice): Boolean = driverName(device) != null

    fun driverName(device: UsbDevice): String? = when {
        device.vendorId == VID_SILICON_LABS -> "CP210x"
        hasCdcInterface(device) -> "CDC-ACM"
        else -> null
    }

    fun open(device: UsbDevice, connection: UsbDeviceConnection, baud: Int = RNODE_USB_BAUD): UsbSerialPort? {
        val port: CommonUsbSerialPort = when {
            device.vendorId == VID_SILICON_LABS -> Cp21xxSerialPort(device, connection, baud)
            hasCdcInterface(device) -> CdcAcmSerialPort(device, connection, baud)
            else -> return null
        }
        return if (port.open()) port else { port.close(); null }
    }

    private fun hasCdcInterface(device: UsbDevice): Boolean {
        for (i in 0 until device.interfaceCount) {
            val c = device.getInterface(i).interfaceClass
            if (c == UsbConstants.USB_CLASS_COMM || c == UsbConstants.USB_CLASS_CDC_DATA) return true
        }
        return false
    }

    private const val VID_SILICON_LABS = 0x10C4
}
