package app.penbridge

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

enum class LinkType { USB, WIFI }

data class FoundPc(val address: String, val port: Int, val name: String, val os: String)

/**
 * Keeps a connection to the PenBridge PC program.
 *
 * Order of attempts: a fixed address from settings if set; otherwise USB
 * (127.0.0.1, forwarded to the PC by `adb reverse`), then Wi-Fi discovery.
 * Pure java.net, so it also runs in plain JVM tests.
 */
class PenLink(
    private val deviceName: String,
    private val appVersion: String,
    private val listener: Listener,
    /** Delivers listener calls, e.g. onto the Android main thread. */
    private val post: (Runnable) -> Unit,
) {
    interface Listener {
        fun onSearching(found: List<FoundPc>, needsChoice: Boolean)
        fun onConnected(type: LinkType, welcome: Welcome)
        fun onDisconnected()
        fun onLatency(ms: Int)
        fun onNotice(text: String)
    }

    /** "192.168.1.20" or "192.168.1.20:47800"; null/blank = automatic. */
    @Volatile var manualAddress: String? = null

    /** Name of the PC to prefer when several answer on Wi-Fi. */
    @Volatile var preferredPc: String? = null

    @Volatile var isConnected = false
        private set

    @Volatile private var running = false
    @Volatile private var generation = 0
    @Volatile private var chosen: FoundPc? = null
    @Volatile private var socket: Socket? = null
    private val queue = LinkedBlockingQueue<ByteArray>(8192)
    private val lock = Object()

    fun start() {
        if (running) return
        running = true
        val gen = ++generation
        Thread({ loop(gen) }, "PenBridge-link").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        generation++
        closeSocket()
        wake()
    }

    /** Queues bytes for the PC. Dropped while disconnected. */
    fun send(bytes: ByteArray) {
        if (isConnected) queue.offer(bytes)
    }

    fun choose(pc: FoundPc) {
        chosen = pc
        closeSocket()
        wake()
    }

    fun reconnect() {
        closeSocket()
        wake()
    }

    private fun alive(gen: Int) = running && gen == generation

    private fun closeSocket() {
        try {
            socket?.close()
        } catch (_: IOException) {
        }
    }

    private fun wake() = synchronized(lock) { lock.notifyAll() }

    private fun pause(ms: Long) = synchronized(lock) {
        try {
            lock.wait(ms)
        } catch (_: InterruptedException) {
        }
    }

    private fun loop(gen: Int) {
        while (alive(gen)) {
            val manual = manualAddress?.trim()?.takeIf { it.isNotEmpty() }
            val picked = chosen
            when {
                manual != null -> {
                    val (host, port) = splitAddress(manual)
                    post { listener.onSearching(emptyList(), false) }
                    open(host, port, 1500)?.let { session(it, linkTypeFor(host), gen) }
                }
                picked != null -> {
                    chosen = null
                    open(picked.address, picked.port, 1500)?.let { session(it, LinkType.WIFI, gen) }
                }
                else -> {
                    val usb = open("127.0.0.1", Proto.TCP_PORT, 300)
                    if (usb != null && session(usb, LinkType.USB, gen)) continue
                    if (!alive(gen)) break
                    val found = discover(900)
                    if (!alive(gen)) break
                    val pick = found.firstOrNull { it.name == preferredPc } ?: found.singleOrNull()
                    post { listener.onSearching(found, pick == null && found.size > 1) }
                    if (pick != null) {
                        open(pick.address, pick.port, 1500)?.let { session(it, LinkType.WIFI, gen) }
                    }
                }
            }
            if (alive(gen) && chosen == null) pause(1200)
        }
    }

    private fun linkTypeFor(host: String) =
        if (host == "127.0.0.1" || host == "localhost") LinkType.USB else LinkType.WIFI

    private fun splitAddress(text: String): Pair<String, Int> {
        val i = text.lastIndexOf(':')
        if (i > 0) {
            val port = text.substring(i + 1).toIntOrNull()
            if (port != null) return text.substring(0, i) to port
        }
        return text to Proto.TCP_PORT
    }

    private fun open(host: String, port: Int, timeoutMs: Int): Socket? = try {
        Socket().apply {
            tcpNoDelay = true
            connect(InetSocketAddress(host, port), timeoutMs)
        }
    } catch (_: IOException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    /** Runs one connection until it drops. Returns true if the handshake worked. */
    private fun session(s: Socket, type: LinkType, gen: Int): Boolean {
        var handshook = false
        var writer: Thread? = null
        try {
            socket = s
            if (!alive(gen)) return false
            s.soTimeout = 3000
            val out = BufferedOutputStream(s.getOutputStream(), 4096)
            val input = DataInputStream(BufferedInputStream(s.getInputStream(), 4096))
            out.write(Proto.hello(deviceName, appVersion))
            out.flush()

            var welcome: Welcome? = null
            while (welcome == null) {
                val f = Proto.readFrame(input)
                when (Proto.type(f)) {
                    Proto.WELCOME -> welcome = Proto.parseWelcome(f)
                    Proto.NOTICE -> notice(Proto.parseNotice(f))
                }
            }
            val w: Welcome = welcome
            handshook = true
            // The PC answers our ping every second, so 5 s of silence means it's gone.
            s.soTimeout = 5000
            queue.clear()
            isConnected = true
            post { listener.onConnected(type, w) }

            writer = Thread({ writeLoop(s, out) }, "PenBridge-writer").apply {
                isDaemon = true
                start()
            }
            while (alive(gen)) {
                val f = Proto.readFrame(input)
                when (Proto.type(f)) {
                    Proto.PONG -> {
                        val rtt = (nowMs().toInt() - Proto.parsePong(f)).coerceIn(0, 9999)
                        post { listener.onLatency(rtt) }
                    }
                    Proto.NOTICE -> notice(Proto.parseNotice(f))
                }
            }
        } catch (_: IOException) {
        } finally {
            writer?.interrupt()
            val wasConnected = isConnected
            isConnected = false
            if (socket === s) socket = null
            try {
                s.close()
            } catch (_: IOException) {
            }
            if (wasConnected) post { listener.onDisconnected() }
        }
        return handshook
    }

    private fun notice(text: String) = post { listener.onNotice(text) }

    private fun writeLoop(s: Socket, out: OutputStream) {
        var lastPing = 0L
        try {
            while (!Thread.currentThread().isInterrupted) {
                var wrote = false
                val first = queue.poll(250, TimeUnit.MILLISECONDS)
                if (first != null) {
                    out.write(first)
                    wrote = true
                    while (true) out.write(queue.poll() ?: break)
                }
                val now = nowMs()
                if (now - lastPing >= 1000) {
                    out.write(Proto.ping(now.toInt()))
                    lastPing = now
                    wrote = true
                }
                if (wrote) out.flush()
            }
        } catch (_: InterruptedException) {
        } catch (_: IOException) {
            try {
                s.close()
            } catch (_: IOException) {
            }
        }
    }

    /** Broadcasts on the local network and collects PenBridge PCs that answer. */
    fun discover(waitMs: Int): List<FoundPc> {
        val found = LinkedHashMap<String, FoundPc>()
        try {
            DatagramSocket().use { ds ->
                ds.broadcast = true
                ds.soTimeout = 150
                val query = Proto.DISCOVERY_QUERY.toByteArray()
                for (addr in broadcastAddresses()) {
                    try {
                        ds.send(DatagramPacket(query, query.size, addr, Proto.DISCOVERY_PORT))
                    } catch (_: IOException) {
                    }
                }
                val buf = ByteArray(512)
                val end = nowMs() + waitMs
                while (nowMs() < end) {
                    val p = DatagramPacket(buf, buf.size)
                    try {
                        ds.receive(p)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    val parts = String(p.data, 0, p.length, Charsets.UTF_8).split('|')
                    if (parts.size >= 4 && parts[0] == Proto.DISCOVERY_REPLY) {
                        val addr = p.address?.hostAddress ?: continue
                        val port = parts[1].toIntOrNull() ?: Proto.TCP_PORT
                        found[addr] = FoundPc(addr, port, parts[2], parts[3])
                    }
                }
            }
        } catch (_: IOException) {
        }
        return found.values.toList()
    }

    private fun broadcastAddresses(): List<InetAddress> {
        val list = mutableListOf<InetAddress>()
        try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().forEach { ni ->
                if (ni.isUp && !ni.isLoopback) {
                    ni.interfaceAddresses.forEach { ia -> ia.broadcast?.let(list::add) }
                }
            }
        } catch (_: Exception) {
        }
        try {
            list.add(InetAddress.getByName("255.255.255.255"))
        } catch (_: Exception) {
        }
        return list.distinct()
    }

    private fun nowMs() = System.nanoTime() / 1_000_000
}
