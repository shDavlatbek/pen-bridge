package app.penbridge

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Toast

class MainActivity : Activity(), PenLink.Listener, PadView.Host, PanelView.Host {

    private lateinit var config: Config
    private lateinit var link: PenLink
    private lateinit var pad: PadView
    private lateinit var panel: PanelView
    private lateinit var root: RootLayout

    private var welcome: Welcome? = null
    private var linkType: LinkType? = null
    private var latency = -1
    private var found: List<FoundPc> = emptyList()
    private var needsChoice = false
    private var wifiLock: WifiManager.WifiLock? = null
    private var lastBack = 0L
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = Config.load(this)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        pad = PadView(this, this)
        panel = PanelView(this, this)
        root = RootLayout(this, pad, panel)
        setContentView(root)

        link = PenLink(deviceName(), BuildConfig.VERSION_NAME, this) { r -> main.post(r) }
        applyConfig()
        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        config = Config.load(this)
        applyConfig()
        link.manualAddress = config.manualAddress.ifBlank { null }
        link.preferredPc = config.lastPcName.ifBlank { null }
        link.start()
        acquireWifiLock()
        hideSystemBars()
        updateStatus()
    }

    override fun onPause() {
        super.onPause()
        pad.release()
        panel.releaseAll()
        link.stop()
        welcome = null
        linkType = null
        pad.connected = false
        wifiLock?.release()
        config.save(this)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onBackPressed() {
        // A stray back gesture shouldn't close the pad mid-drawing.
        val now = SystemClock.uptimeMillis()
        if (now - lastBack < 2000) {
            super.onBackPressed()
        } else {
            lastBack = now
            Toast.makeText(this, "Press back again to exit", Toast.LENGTH_SHORT).show()
        }
    }

    private fun applyConfig() {
        root.panelOnLeft = config.panelOnLeft
        val shown = welcome?.display(validDisplayIndex())
        root.aspect = shown?.aspect ?: config.lastAspect
        panel.bind(config)
        pad.invalidate()
    }

    private fun deviceName(): String =
        Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() }
            ?: Build.MODEL

    private fun acquireWifiLock() {
        if (wifiLock == null) {
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as? WifiManager ?: return
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "PenBridge").apply {
                setReferenceCounted(false)
            }
        }
        wifiLock?.acquire()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.apply {
                hide(WindowInsets.Type.systemBars())
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        }
    }

    // ---- Connection ----

    private fun validDisplayIndex(): Int {
        val w = welcome ?: return 0
        val i = config.displayIndex
        return when {
            i == Proto.ALL_DISPLAYS && w.displays.size > 1 -> i
            i in w.displays.indices -> i
            else -> w.primaryIndex()
        }
    }

    private fun selectDisplay(index: Int, remember: Boolean) {
        val w = welcome ?: return
        val d = w.display(index) ?: return
        link.send(Proto.selectDisplay(index))
        root.aspect = d.aspect
        config.lastAspect = d.aspect
        if (remember) config.displayIndex = index
        config.save(this)
        val name = if (index == Proto.ALL_DISPLAYS) "All displays" else "Display ${index + 1}"
        pad.caption = "$name · ${d.width}×${d.height}"
    }

    override fun onConnected(type: LinkType, welcome: Welcome) {
        this.welcome = welcome
        linkType = type
        latency = -1
        if (config.lastPcName != welcome.host) config.lastPcName = welcome.host
        link.preferredPc = welcome.host
        selectDisplay(validDisplayIndex(), remember = false)
        pad.connected = true
        updateStatus()
        val via = if (type == LinkType.USB) "USB" else "Wi-Fi"
        Toast.makeText(this, "Connected to ${welcome.host} over $via", Toast.LENGTH_SHORT).show()
    }

    override fun onDisconnected() {
        welcome = null
        linkType = null
        pad.release()
        panel.releaseAll()
        pad.connected = false
        updateStatus()
    }

    override fun onSearching(found: List<FoundPc>, needsChoice: Boolean) {
        this.found = found
        this.needsChoice = needsChoice
        if (welcome == null) updateStatus()
    }

    override fun onLatency(ms: Int) {
        latency = ms
        updateStatus()
    }

    override fun onNotice(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

    private fun updateStatus() {
        val w = welcome
        when {
            w != null -> {
                val via = if (linkType == LinkType.USB) "USB" else "Wi-Fi"
                val ms = if (latency >= 0) " · $latency ms" else ""
                panel.status.set("$via$ms", Palette.OK)
                pad.message = ""
            }
            needsChoice -> {
                panel.status.set("Pick your PC", Palette.WARN)
                pad.message = "Several PCs found\nTap the status at the top of the panel to choose one."
            }
            else -> {
                panel.status.set("Searching…", Palette.WARN)
                pad.message = "Waiting for your PC\n" +
                    "Start PenBridge on the computer, then connect a USB cable\n" +
                    "or join the same Wi-Fi. Tap the status for help."
            }
        }
    }

    private fun showConnectionDialog() {
        val w = welcome
        val b = AlertDialog.Builder(this)
        when {
            w != null -> {
                val via = if (linkType == LinkType.USB) "USB" else "Wi-Fi"
                b.setTitle("Connected to ${w.host} ($via)")
                val names = w.displays.mapIndexed { i, d ->
                    "Display ${i + 1} · ${d.width}×${d.height}" + if (d.primary) " (main)" else ""
                }.toMutableList()
                val indices = w.displays.indices.toMutableList()
                if (w.displays.size > 1) {
                    names += "All displays"
                    indices += Proto.ALL_DISPLAYS
                }
                b.setSingleChoiceItems(names.toTypedArray(), indices.indexOf(validDisplayIndex())) { d, which ->
                    selectDisplay(indices[which], remember = true)
                    d.dismiss()
                }
                b.setNeutralButton("Reconnect") { _, _ -> link.reconnect() }
                b.setPositiveButton("Close", null)
            }
            found.isNotEmpty() -> {
                b.setTitle("Choose your PC")
                b.setItems(found.map { "${it.name}  (${it.address})" }.toTypedArray()) { _, which ->
                    link.choose(found[which])
                }
                b.setNegativeButton("Cancel", null)
            }
            else -> {
                b.setTitle("Not connected")
                b.setMessage(HELP_TEXT)
                b.setPositiveButton("OK", null)
                b.setNeutralButton("Settings") { _, _ -> onSettingsTapped() }
            }
        }
        b.create().apply {
            setOnDismissListener { hideSystemBars() }
            show()
        }
    }

    // ---- PadView.Host ----

    override fun currentConfig(): Config = config

    override fun sendPen(flags: Int, x: Int, y: Int, pressure: Int, tiltX: Int, tiltY: Int) {
        link.send(Proto.pen(flags, x, y, pressure, tiltX, tiltY))
    }

    override fun onSideButton(pressed: Boolean) {
        if (config.sideMode == SideButton.HOLD_KEYS && config.sideKeys.isNotBlank()) {
            link.send(Proto.key(if (pressed) Proto.KEY_DOWN else Proto.KEY_UP, config.sideKeys))
        }
    }

    // ---- PanelView.Host ----

    override fun onShortcut(button: ShortcutButton, down: Boolean) {
        if (button.keys.isBlank()) return
        when (button.mode) {
            ButtonMode.TAP -> if (down) link.send(Proto.key(Proto.KEY_TAP, button.keys))
            ButtonMode.HOLD -> link.send(Proto.key(if (down) Proto.KEY_DOWN else Proto.KEY_UP, button.keys))
        }
    }

    override fun onDialStep(mode: DialMode, up: Boolean) {
        val keys = if (up) mode.up else mode.down
        if (keys.isNotBlank()) link.send(Proto.key(Proto.KEY_TAP, keys))
    }

    override fun onDialModeChanged(index: Int) {
        config.dialModeIndex = index
    }

    override fun onStatusTapped() = showConnectionDialog()

    override fun onSettingsTapped() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    companion object {
        const val HELP_TEXT = "1. Start PenBridge on your Windows PC.\n\n" +
            "USB cable: turn on USB debugging in Developer options, plug in the cable, " +
            "and tap Allow on the phone. PenBridge on the PC sets up the rest.\n\n" +
            "Wi-Fi: keep the phone and the PC on the same Wi-Fi network. If the PC isn't found, " +
            "type its address (shown in the PC window) in Settings > Connect to."
    }
}
