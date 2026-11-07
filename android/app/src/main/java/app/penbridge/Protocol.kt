package app.penbridge

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/**
 * PenBridge wire protocol, version 1 (see docs/PROTOCOL.md).
 * Every frame is `u16 length` (big-endian) + `u8 type` + payload.
 */
object Proto {
    const val VERSION = 1
    const val TCP_PORT = 47800
    const val DISCOVERY_PORT = 47801
    const val DISCOVERY_QUERY = "PENBRIDGE?1"
    const val DISCOVERY_REPLY = "PENBRIDGE!1"

    // phone -> PC
    const val HELLO = 0x01
    const val SELECT_DISPLAY = 0x02
    const val PEN = 0x10
    const val KEY = 0x20
    const val PING = 0x30
    const val BYE = 0x7F

    // PC -> phone
    const val WELCOME = 0x81
    const val NOTICE = 0x83
    const val PONG = 0xB0

    const val FLAG_IN_RANGE = 0x01
    const val FLAG_TIP = 0x02
    const val FLAG_BARREL = 0x04
    const val FLAG_ERASER = 0x08

    const val KEY_DOWN = 1
    const val KEY_UP = 2
    const val KEY_TAP = 3

    /** Display index meaning "map the pad to all displays". */
    const val ALL_DISPLAYS = 0xFF

    private const val MAX_FRAME = 8 * 1024

    private inline fun frame(type: Int, body: DataOutputStream.() -> Unit): ByteArray {
        val bytes = ByteArrayOutputStream(32)
        DataOutputStream(bytes).apply {
            writeByte(type)
            body()
            flush()
        }
        val payload = bytes.toByteArray()
        val out = ByteArray(payload.size + 2)
        out[0] = (payload.size ushr 8).toByte()
        out[1] = payload.size.toByte()
        System.arraycopy(payload, 0, out, 2, payload.size)
        return out
    }

    private fun DataOutputStream.str(s: String) {
        val b = s.take(300).toByteArray(Charsets.UTF_8)
        writeShort(b.size)
        write(b)
    }

    fun hello(device: String, appVersion: String) = frame(HELLO) {
        writeShort(VERSION)
        str(device)
        str(appVersion)
    }

    fun selectDisplay(index: Int) = frame(SELECT_DISPLAY) { writeByte(index) }

    /** Hot path: built by hand to avoid stream overhead. 12 bytes. */
    fun pen(flags: Int, x: Int, y: Int, pressure: Int, tiltX: Int, tiltY: Int): ByteArray = byteArrayOf(
        0, 10, PEN.toByte(), flags.toByte(),
        (x ushr 8).toByte(), x.toByte(),
        (y ushr 8).toByte(), y.toByte(),
        (pressure ushr 8).toByte(), pressure.toByte(),
        tiltX.coerceIn(-90, 90).toByte(), tiltY.coerceIn(-90, 90).toByte(),
    )

    fun key(action: Int, combo: String) = frame(KEY) {
        writeByte(action)
        str(combo.trim())
    }

    fun ping(id: Int) = frame(PING) { writeInt(id) }

    fun bye() = frame(BYE) {}

    /** Reads one frame body (type byte first). */
    fun readFrame(input: DataInputStream): ByteArray {
        val len = input.readUnsignedShort()
        if (len == 0 || len > MAX_FRAME) throw IOException("bad frame length $len")
        val b = ByteArray(len)
        input.readFully(b)
        return b
    }

    fun type(frame: ByteArray): Int = frame[0].toInt() and 0xFF

    private fun body(frame: ByteArray) = DataInputStream(ByteArrayInputStream(frame, 1, frame.size - 1))

    private fun DataInputStream.str(): String {
        val n = readUnsignedShort()
        val b = ByteArray(n)
        readFully(b)
        return String(b, Charsets.UTF_8)
    }

    fun parseWelcome(frame: ByteArray): Welcome {
        val d = body(frame)
        val version = d.readUnsignedShort()
        val host = d.str()
        val os = d.str()
        val count = d.readUnsignedByte()
        val displays = List(count) {
            val x = d.readInt()
            val y = d.readInt()
            val w = d.readInt()
            val h = d.readInt()
            val primary = d.readUnsignedByte() != 0
            RemoteDisplay(d.str(), x, y, w, h, primary)
        }
        return Welcome(version, host, os, displays)
    }

    fun parseNotice(frame: ByteArray): String = body(frame).str()

    fun parsePong(frame: ByteArray): Int = body(frame).readInt()
}

data class RemoteDisplay(
    val name: String,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val primary: Boolean,
) {
    val aspect: Float get() = if (width > 0 && height > 0) width.toFloat() / height else 16f / 9f
}

data class Welcome(val version: Int, val host: String, val os: String, val displays: List<RemoteDisplay>) {
    fun primaryIndex(): Int = displays.indexOfFirst { it.primary }.coerceAtLeast(0)

    fun display(index: Int): RemoteDisplay? =
        if (index == Proto.ALL_DISPLAYS) allDisplays() else displays.getOrNull(index)

    private fun allDisplays(): RemoteDisplay? {
        if (displays.isEmpty()) return null
        val l = displays.minOf { it.x }
        val t = displays.minOf { it.y }
        val r = displays.maxOf { it.x + it.width }
        val b = displays.maxOf { it.y + it.height }
        return RemoteDisplay("All displays", l, t, r - l, b - t, false)
    }
}
