package com.frodrigues.obdmqtt.gps

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface

/** The USB-serial chips found in GPS receivers. */
enum class UsbChip { CDC, CP210X, CH34X }

/**
 * A read-only serial line over USB, enough for a GPS receiver: no flow control,
 * 8 data bits, no parity, 1 stop bit, at [open]'s baud rate.
 *
 * - CDC (u-blox and most receivers, also Prolific PL2303 — the way UsbGps4Droid
 *   talks to them): the standard « set line coding » request.
 * - Silicon Labs CP210x: its own vendor requests (enable, baud, 8N1).
 * - WCH CH340/CH341: its own init, divisor and line control (as Linux' ch341 driver).
 */
class UsbSerialPort(private val device: UsbDevice, private val conn: UsbDeviceConnection) {

    val chip: UsbChip = chipOf(device)
    private var dataInterface: UsbInterface? = null
    private var controlIndex = 0
    private var inEndpoint: UsbEndpoint? = null

    /** Claims the interface holding a bulk IN endpoint and sets the line. False when none. */
    fun open(baud: Int): Boolean {
        if (inEndpoint == null) {
            for (i in 0 until device.interfaceCount) {
                val itf = device.getInterface(i)
                if (itf.interfaceClass == UsbConstants.USB_CLASS_COMM) controlIndex = itf.id
                for (e in 0 until itf.endpointCount) {
                    val ep = itf.getEndpoint(e)
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK && ep.direction == UsbConstants.USB_DIR_IN) {
                        if (inEndpoint == null) { inEndpoint = ep; dataInterface = itf }
                    }
                }
            }
            val itf = dataInterface ?: return false
            if (!conn.claimInterface(itf, true)) return false
            // A CDC device also wants its control interface for the line requests
            if (chip == UsbChip.CDC && controlIndex != itf.id) {
                (0 until device.interfaceCount).map { device.getInterface(it) }
                    .firstOrNull { it.id == controlIndex }?.let { conn.claimInterface(it, true) }
            }
        }
        setBaud(baud)
        return true
    }

    fun setBaud(baud: Int) {
        when (chip) {
            UsbChip.CDC -> {
                val coding = byteArrayOf(
                    (baud and 0xFF).toByte(), (baud shr 8 and 0xFF).toByte(),
                    (baud shr 16 and 0xFF).toByte(), (baud shr 24 and 0xFF).toByte(),
                    0, 0, 8  // 1 stop bit, no parity, 8 data bits
                )
                conn.controlTransfer(0x21, 0x22, 0x03, controlIndex, null, 0, TIMEOUT)  // DTR + RTS
                conn.controlTransfer(0x21, 0x20, 0, controlIndex, coding, coding.size, TIMEOUT)
            }
            UsbChip.CP210X -> {
                conn.controlTransfer(0x41, 0x00, 0x0001, 0, null, 0, TIMEOUT)  // IFC_ENABLE
                val b = byteArrayOf((baud and 0xFF).toByte(), (baud shr 8 and 0xFF).toByte(),
                    (baud shr 16 and 0xFF).toByte(), (baud shr 24 and 0xFF).toByte())
                conn.controlTransfer(0x41, 0x1E, 0, 0, b, 4, TIMEOUT)          // SET_BAUDRATE
                conn.controlTransfer(0x41, 0x03, 0x0800, 0, null, 0, TIMEOUT)  // SET_LINE_CTL 8N1
            }
            UsbChip.CH34X -> {
                conn.controlTransfer(0x40, 0xA1, 0, 0, null, 0, TIMEOUT)       // serial init
                val divisor = ch34xDivisor(baud) ?: ch34xDivisor(9600)!!
                conn.controlTransfer(0x40, 0x9A, 0x1312, divisor or 0x80, null, 0, TIMEOUT)  // prescaler/divisor
                conn.controlTransfer(0x40, 0x9A, 0x2518, 0xC3, null, 0, TIMEOUT)  // RX + TX on, 8 bits
                conn.controlTransfer(0x40, 0xA4, 0xFFFF xor 0x60, 0, null, 0, TIMEOUT)  // DTR + RTS (active low)
            }
        }
    }

    /** Bytes received within [timeoutMs] (0: nothing yet, -1: the device is gone). */
    fun read(buffer: ByteArray, timeoutMs: Int): Int {
        val ep = inEndpoint ?: return -1
        return conn.bulkTransfer(ep, buffer, buffer.size, timeoutMs)
    }

    fun close() {
        runCatching { dataInterface?.let { conn.releaseInterface(it) } }
        runCatching { conn.close() }
    }

    companion object {
        private const val TIMEOUT = 1000
        const val VID_CP210X = 0x10C4
        const val VID_CH34X = 0x1A86
        const val VID_UBLOX = 0x1546
        const val VID_PROLIFIC = 0x067B

        fun chipOf(device: UsbDevice): UsbChip = when (device.vendorId) {
            VID_CP210X -> UsbChip.CP210X
            VID_CH34X -> UsbChip.CH34X
            else -> UsbChip.CDC
        }

        /** A device that may be a GPS receiver: a known chip, or one with a CDC interface. */
        fun looksLikeSerial(device: UsbDevice): Boolean =
            device.vendorId in setOf(VID_CP210X, VID_CH34X, VID_UBLOX, VID_PROLIFIC) ||
                device.deviceClass == UsbConstants.USB_CLASS_COMM ||
                (0 until device.interfaceCount).any {
                    device.getInterface(it).interfaceClass in setOf(UsbConstants.USB_CLASS_COMM, UsbConstants.USB_CLASS_CDC_DATA)
                }

        /**
         * CH341 divisor word for a baud rate (Linux ch341_get_divisor): prescaler in
         * the low bits, 256 − divisor in the high byte. Null when out of range.
         */
        fun ch34xDivisor(baud: Int): Int? {
            val clk = 48_000_000L
            fun clkDiv(ps: Int, fact: Int) = 1L shl (12 - 3 * ps - fact)
            fun minRate(ps: Int) = clk / (clkDiv(ps, 1) * 512)
            var fact = 1
            var ps = 3
            while (ps > 0 && baud <= minRate(ps)) ps--
            if (ps == 0 && baud <= minRate(0)) return null
            var cd = clkDiv(ps, fact)
            var div = clk / (cd * baud)
            if (div < 9 || div > 255) { div /= 2; cd *= 2; fact = 0 }
            if (div < 2) return null
            if (16 * clk / (cd * div) - 16L * baud >= 16L * baud - 16 * clk / (cd * (div + 1))) div++
            if (fact == 1 && div % 2 == 0L) { div /= 2; fact = 0 }
            return ((0x100 - div.toInt()) shl 8) or (fact shl 2) or ps
        }
    }
}
