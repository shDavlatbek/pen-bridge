package app.penbridge

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** All settings on one scrolling page. Changes are saved when you leave. */
class SettingsActivity : Activity() {

    private lateinit var config: Config
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = Config.load(this)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Palette.BG)
            isFillViewport = true
        }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)
        val pad = dpi(16)
        list.setPadding(pad, pad, pad, pad)
        scroll.setOnApplyWindowInsetsListener { _, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                list.setPadding(bars.left + pad, bars.top + pad, bars.right + pad, bars.bottom + pad)
            } else {
                @Suppress("DEPRECATION")
                list.setPadding(
                    insets.systemWindowInsetLeft + pad, insets.systemWindowInsetTop + pad,
                    insets.systemWindowInsetRight + pad, insets.systemWindowInsetBottom + pad,
                )
            }
            insets
        }
        setContentView(scroll)
        render()
    }

    override fun onPause() {
        super.onPause()
        config.save(this)
    }

    private fun render() {
        list.removeAllViews()

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(text("‹  Back", 16f, Palette.ACCENT).apply {
            setPadding(0, dpi(8), dpi(16), dpi(8))
            setOnClickListener { finish() }
        })
        top.addView(text("PenBridge settings", 20f, Palette.TEXT, bold = true))
        list.addView(top)

        section("Connection")
        row(
            "Connect to",
            if (config.manualAddress.isBlank()) "Automatic: USB cable first, then Wi-Fi"
            else "Only ${config.manualAddress}",
        ) { editAddress() }

        section("Pen")
        row("Pressure feel", config.pressure.title) { choosePressure() }
        row("S Pen side button", sideSummary()) { chooseSideButton() }

        section("Layout")
        row(
            "Shortcut panel",
            if (config.panelOnLeft) "Left side (for right-handed)" else "Right side (for left-handed)",
        ) {
            config.panelOnLeft = !config.panelOnLeft
            render()
        }
        switchRow("Show a short ink trail on the phone", config.showTrail) { config.showTrail = it }

        section("Shortcut buttons")
        config.buttons.forEachIndexed { i, b ->
            row(b.label, "${b.keys}  ·  ${if (b.mode == ButtonMode.HOLD) "hold while pressed" else "tap"}") {
                editButton(i)
            }
        }
        if (config.buttons.size < PanelView.MAX_BUTTONS) action("+  Add button") { editButton(-1) }

        section("Dial modes")
        config.dialModes.forEachIndexed { i, d ->
            row(d.label, "drag up: ${d.up}   ·   drag down: ${d.down}") { editDial(i) }
        }
        action("+  Add dial mode") { editDial(-1) }

        section("More")
        action("Reset buttons and dial to Clip Studio Paint defaults") {
            AlertDialog.Builder(this)
                .setTitle("Reset shortcuts?")
                .setMessage("Your buttons, dial modes and side button setting will go back to the defaults.")
                .setPositiveButton("Reset") { _, _ ->
                    config.resetShortcuts()
                    render()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        list.addView(text(KEY_HELP, 13f, Palette.MUTED).apply { setPadding(0, dpi(16), 0, 0) })
        list.addView(text("PenBridge ${BuildConfig.VERSION_NAME}", 12f, Palette.MUTED).apply {
            setPadding(0, dpi(16), 0, 0)
        })
    }

    // ---- Dialogs ----

    private fun editAddress() {
        val input = field(config.manualAddress, "e.g. 192.168.1.20").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        AlertDialog.Builder(this)
            .setTitle("PC address")
            .setMessage(
                "Leave empty to connect automatically (USB first, then Wi-Fi). " +
                    "Type the address shown in the PenBridge window on your PC if it isn't found.",
            )
            .setView(wrap(input))
            .setPositiveButton("Save") { _, _ ->
                config.manualAddress = input.text.toString().trim()
                render()
            }
            .setNeutralButton("Automatic") { _, _ ->
                config.manualAddress = ""
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun choosePressure() {
        val options = PressureFeel.values()
        AlertDialog.Builder(this)
            .setTitle("Pressure feel")
            .setSingleChoiceItems(options.map { it.title }.toTypedArray(), options.indexOf(config.pressure)) { d, which ->
                config.pressure = options[which]
                d.dismiss()
                render()
            }
            .show()
    }

    private class SidePreset(val title: String, val mode: SideButton, val keys: String?)

    private val sidePresets = listOf(
        SidePreset("Eraser while held (holds E)", SideButton.HOLD_KEYS, "e"),
        SidePreset("Eyedropper while held (holds Alt)", SideButton.HOLD_KEYS, "alt"),
        SidePreset("Right-click", SideButton.BARREL, null),
        SidePreset("Pen eraser (Windows Ink)", SideButton.ERASER, null),
        SidePreset("Custom shortcut while held…", SideButton.HOLD_KEYS, null),
        SidePreset("Nothing", SideButton.NONE, null),
    )

    private fun sideSummary(): String = when (config.sideMode) {
        SideButton.HOLD_KEYS -> when (config.sideKeys) {
            "e" -> "Eraser while held (holds E)"
            "alt" -> "Eyedropper while held (holds Alt)"
            else -> "Holds ${config.sideKeys}"
        }
        SideButton.BARREL -> "Right-click"
        SideButton.ERASER -> "Pen eraser (Windows Ink)"
        SideButton.NONE -> "Nothing"
    }

    private fun chooseSideButton() {
        val current = sidePresets.indexOfFirst {
            it.mode == config.sideMode && (it.keys == null || it.keys == config.sideKeys)
        }.let { if (it < 0) 4 else it }
        AlertDialog.Builder(this)
            .setTitle("S Pen side button")
            .setSingleChoiceItems(sidePresets.map { it.title }.toTypedArray(), current) { d, which ->
                d.dismiss()
                val p = sidePresets[which]
                if (p.mode == SideButton.HOLD_KEYS && p.keys == null) {
                    editSideKeys()
                } else {
                    config.sideMode = p.mode
                    p.keys?.let { config.sideKeys = it }
                    render()
                }
            }
            .show()
    }

    private fun editSideKeys() {
        val input = field(config.sideKeys, "e.g. alt, e, ctrl+z")
        AlertDialog.Builder(this)
            .setTitle("Hold this shortcut")
            .setView(wrap(input))
            .setPositiveButton("Save") { _, _ ->
                val keys = input.text.toString().trim()
                if (keys.isNotEmpty()) {
                    config.sideMode = SideButton.HOLD_KEYS
                    config.sideKeys = keys
                }
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun editButton(index: Int) {
        val existing = config.buttons.getOrNull(index)
        val label = field(existing?.label ?: "", "Name, e.g. Undo")
        val keys = field(existing?.keys ?: "", "Keys, e.g. ctrl+z")
        val tap = RadioButton(this).apply { text = "Tap: press once"; id = View.generateViewId() }
        val hold = RadioButton(this).apply { text = "Hold: keep pressed while my finger is down"; id = View.generateViewId() }
        val modes = RadioGroup(this).apply {
            addView(tap)
            addView(hold)
            check(if (existing?.mode == ButtonMode.HOLD) hold.id else tap.id)
        }
        val b = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "New button" else "Edit button")
            .setView(wrap(label, keys, modes))
            .setPositiveButton("Save") { _, _ ->
                val l = label.text.toString().trim()
                val k = keys.text.toString().trim()
                if (l.isEmpty() || k.isEmpty()) {
                    toast("Both a name and keys are needed.")
                    return@setPositiveButton
                }
                val mode = if (modes.checkedRadioButtonId == hold.id) ButtonMode.HOLD else ButtonMode.TAP
                if (existing == null) {
                    config.buttons += ShortcutButton(l, k, mode)
                } else {
                    existing.label = l
                    existing.keys = k
                    existing.mode = mode
                }
                render()
            }
            .setNegativeButton("Cancel", null)
        if (existing != null) {
            b.setNeutralButton("Delete") { _, _ ->
                config.buttons.removeAt(index)
                render()
            }
        }
        b.show()
    }

    private fun editDial(index: Int) {
        val existing = config.dialModes.getOrNull(index)
        val label = field(existing?.label ?: "", "Name, e.g. Brush size")
        val up = field(existing?.up ?: "", "Drag up sends, e.g. ]")
        val down = field(existing?.down ?: "", "Drag down sends, e.g. [")
        val b = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "New dial mode" else "Edit dial mode")
            .setView(wrap(label, up, down))
            .setPositiveButton("Save") { _, _ ->
                val l = label.text.toString().trim()
                if (l.isEmpty()) {
                    toast("The dial mode needs a name.")
                    return@setPositiveButton
                }
                if (existing == null) {
                    config.dialModes += DialMode(l, up.text.toString().trim(), down.text.toString().trim())
                } else {
                    existing.label = l
                    existing.up = up.text.toString().trim()
                    existing.down = down.text.toString().trim()
                }
                render()
            }
            .setNegativeButton("Cancel", null)
        if (existing != null) {
            b.setNeutralButton("Delete") { _, _ ->
                config.dialModes.removeAt(index)
                if (config.dialModeIndex >= config.dialModes.size) config.dialModeIndex = 0
                render()
            }
        }
        b.show()
    }

    // ---- Small view helpers ----

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun text(s: String, sizeSp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun section(title: String) {
        list.addView(text(title.uppercase(), 12f, Palette.ACCENT, bold = true).apply {
            letterSpacing = 0.08f
            setPadding(dpi(4), dpi(24), 0, dpi(8))
        })
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(Palette.SURFACE)
            cornerRadius = dp(12f)
        }
        setPadding(dpi(16), dpi(12), dpi(16), dpi(12))
        val tv = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
        if (tv.resourceId != 0) foreground = getDrawable(tv.resourceId)
        isClickable = true
    }

    private fun addCard(view: View) {
        list.addView(view, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dpi(6) })
    }

    private fun row(title: String, subtitle: String, onClick: () -> Unit) {
        addCard(card().apply {
            addView(text(title, 16f, Palette.TEXT))
            addView(text(subtitle, 13f, Palette.MUTED).apply { setPadding(0, dpi(2), 0, 0) })
            setOnClickListener { onClick() }
        })
    }

    private fun action(title: String, onClick: () -> Unit) {
        addCard(card().apply {
            addView(text(title, 15f, Palette.ACCENT))
            setOnClickListener { onClick() }
        })
    }

    @Suppress("DEPRECATION")
    private fun switchRow(title: String, value: Boolean, onChange: (Boolean) -> Unit) {
        val sw = Switch(this).apply {
            text = title
            setTextColor(Palette.TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            isChecked = value
            setOnCheckedChangeListener { _, checked -> onChange(checked) }
        }
        addCard(card().apply {
            addView(sw)
            setOnClickListener { sw.toggle() }
        })
    }

    private fun field(value: String, hint: String) = EditText(this).apply {
        setText(value)
        this.hint = hint
        setSingleLine()
    }

    private fun wrap(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dpi(20), dpi(8), dpi(20), 0)
        views.forEach { addView(it) }
    }

    companion object {
        const val KEY_HELP = "How to write shortcuts: join keys with +, for example ctrl+z, " +
            "ctrl+shift+z, shift+space, alt, ], [.\n\n" +
            "Modifiers: ctrl, shift, alt, win.\n" +
            "Special keys: space, tab, enter, esc, backspace, delete, home, end, pageup, pagedown, " +
            "up, down, left, right, f1 to f24, plus, minus, num0 to num9.\n" +
            "Mouse wheel: wheelup, wheeldown (e.g. ctrl+wheelup)."
    }
}
