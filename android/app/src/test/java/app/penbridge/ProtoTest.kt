package app.penbridge

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.DataInputStream

class ProtoTest {
    @Test
    fun penFrameMatchesPcLayout() {
        // Same bytes the PC's protocol test parses.
        val b = Proto.pen(0x03, 0x1234, 0xFFFF, 0x8000, -10, 5)
        assertArrayEquals(
            byteArrayOf(0, 10, 0x10, 0x03, 0x12, 0x34, 0xFF.toByte(), 0xFF.toByte(), 0x80.toByte(), 0x00, 0xF6.toByte(), 0x05),
            b,
        )
    }

    @Test
    fun keyAndHelloFrames() {
        val k = Proto.key(Proto.KEY_TAP, " ctrl+z ")
        assertArrayEquals(byteArrayOf(0, 10, 0x20, 3, 0, 6) + "ctrl+z".toByteArray(), k)
        val h = Proto.hello("S24", "0.1.0")
        assertEquals(h.size - 2, ((h[0].toInt() and 0xFF) shl 8) or (h[1].toInt() and 0xFF))
        assertEquals(Proto.HELLO, h[2].toInt())
    }

    @Test
    fun parsesWelcome() {
        // u16 version, str host, str os, u8 count, display...
        val body = byteArrayOf(0x81.toByte(), 0, 1, 0, 2) + "pc".toByteArray() + byteArrayOf(0, 7) +
            "windows".toByteArray() + byteArrayOf(1) +
            intBytes(-1920) + intBytes(0) + intBytes(1920) + intBytes(1080) + byteArrayOf(1, 0, 1) + "A".toByteArray()
        val framed = byteArrayOf(0, body.size.toByte()) + body
        val f = Proto.readFrame(DataInputStream(ByteArrayInputStream(framed)))
        val w = Proto.parseWelcome(f)
        assertEquals("pc", w.host)
        assertEquals("windows", w.os)
        assertEquals(RemoteDisplay("A", -1920, 0, 1920, 1080, true), w.displays[0])
        assertEquals(16f / 9f, w.display(0)!!.aspect, 0.001f)
    }

    private fun intBytes(v: Int) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
}
