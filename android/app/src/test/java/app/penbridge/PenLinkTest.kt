package app.penbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.DataInputStream
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class PenLinkTest {
    private class Recorder : PenLink.Listener {
        val connected = CountDownLatch(1)
        val latency = CountDownLatch(1)
        val notices = mutableListOf<String>()
        var welcome: Welcome? = null
        var type: LinkType? = null
        override fun onSearching(found: List<FoundPc>, needsChoice: Boolean) {}
        override fun onConnected(type: LinkType, welcome: Welcome) {
            this.type = type; this.welcome = welcome; connected.countDown()
        }
        override fun onDisconnected() {}
        override fun onLatency(ms: Int) { latency.countDown() }
        override fun onNotice(text: String) { synchronized(notices) { notices += text } }
    }

    /** A tiny fake PC: answers HELLO with WELCOME, echoes pings, records pen frames. */
    @Test
    fun handshakeSendAndPing() {
        val server = ServerSocket(0)
        val gotPen = CountDownLatch(1)
        thread(isDaemon = true) {
            server.accept().use { s ->
                val input = DataInputStream(s.getInputStream())
                val out = s.getOutputStream()
                val hello = Proto.readFrame(input)
                assertEquals(Proto.HELLO, Proto.type(hello))
                val body = byteArrayOf(0x81.toByte(), 0, 1, 0, 2) + "pc".toByteArray() + byteArrayOf(0, 7) +
                    "windows".toByteArray() + byteArrayOf(0)
                out.write(byteArrayOf(0, body.size.toByte()) + body); out.flush()
                while (true) {
                    val f = try { Proto.readFrame(input) } catch (e: Exception) { break }
                    when (Proto.type(f)) {
                        Proto.PEN -> gotPen.countDown()
                        Proto.PING -> { out.write(byteArrayOf(0, 5, 0xB0.toByte()) + f.copyOfRange(1, 5)); out.flush() }
                    }
                }
            }
        }
        val rec = Recorder()
        val link = PenLink("test", "0", rec) { it.run() }
        link.manualAddress = "127.0.0.1:${server.localPort}"
        link.start()
        assertTrue("connects", rec.connected.await(5, TimeUnit.SECONDS))
        assertEquals("pc", rec.welcome!!.host)
        assertEquals(LinkType.USB, rec.type)
        link.send(Proto.pen(Proto.FLAG_IN_RANGE, 100, 200, 0, 0, 0))
        assertTrue("pen frame arrives", gotPen.await(3, TimeUnit.SECONDS))
        assertTrue("ping answered", rec.latency.await(3, TimeUnit.SECONDS))
        link.stop()
        server.close()
    }

    /** Talks to the real PC program when PENBRIDGE_SERVER (host:port) is set. */
    @Test
    fun realServer() {
        val addr = System.getenv("PENBRIDGE_SERVER")
        assumeTrue(addr != null)
        val rec = Recorder()
        val link = PenLink("JVM test", "0", rec) { it.run() }
        link.manualAddress = addr
        link.start()
        assertTrue(rec.connected.await(5, TimeUnit.SECONDS))
        println("connected to ${rec.welcome}")
        link.send(Proto.selectDisplay(0))
        link.send(Proto.pen(Proto.FLAG_IN_RANGE, 1000, 1000, 0, 0, 0))
        link.send(Proto.pen(Proto.FLAG_IN_RANGE or Proto.FLAG_TIP, 1000, 1000, 30000, 12, -7))
        link.send(Proto.pen(Proto.FLAG_IN_RANGE or Proto.FLAG_TIP, 30000, 20000, 50000, 12, -7))
        link.send(Proto.pen(Proto.FLAG_IN_RANGE, 30000, 20000, 0, 0, 0))
        link.send(Proto.pen(0, 30000, 20000, 0, 0, 0))
        link.send(Proto.key(Proto.KEY_DOWN, "shift+space"))
        link.send(Proto.key(Proto.KEY_UP, "shift+space"))
        link.send(Proto.key(Proto.KEY_TAP, "ctrl+plus"))
        assertTrue(rec.latency.await(3, TimeUnit.SECONDS))
        Thread.sleep(300)
        println("notices: ${rec.notices}")
        link.stop()
    }
}
