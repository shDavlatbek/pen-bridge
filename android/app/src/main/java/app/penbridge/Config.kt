package app.penbridge

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

enum class ButtonMode { TAP, HOLD }

class ShortcutButton(var label: String, var keys: String, var mode: ButtonMode)

/** A dial mode: dragging up sends [up], dragging down sends [down]. */
class DialMode(var label: String, var up: String, var down: String)

enum class SideButton {
    /** Holds [Config.sideKeys] on the PC while the S Pen button is pressed. */
    HOLD_KEYS,
    /** Acts as the pen's barrel button (usually right-click). */
    BARREL,
    /** Turns the pen into a Windows Ink eraser while pressed. */
    ERASER,
    NONE,
}

enum class PressureFeel(val title: String, val gamma: Float) {
    SOFT("Soft: light strokes come out darker", 0.6f),
    NORMAL("Normal", 1.0f),
    FIRM("Firm: press harder for dark lines", 1.6f),
}

class Config {
    var panelOnLeft = true
    var showTrail = true
    var pressure = PressureFeel.NORMAL
    var sideMode = SideButton.HOLD_KEYS
    var sideKeys = "e"
    var manualAddress = ""
    /** -1 = the PC's main display; 0xFF = all displays. */
    var displayIndex = -1
    var lastPcName = ""
    var lastAspect = 16f / 9f
    var dialModeIndex = 0
    val buttons = mutableListOf<ShortcutButton>()
    val dialModes = mutableListOf<DialMode>()

    /** Clip Studio Paint defaults. */
    fun resetShortcuts() {
        buttons.clear()
        buttons += listOf(
            ShortcutButton("Undo", "ctrl+z", ButtonMode.TAP),
            ShortcutButton("Redo", "ctrl+y", ButtonMode.TAP),
            ShortcutButton("Pen", "p", ButtonMode.TAP),
            ShortcutButton("Eraser", "e", ButtonMode.TAP),
            ShortcutButton("Brush", "b", ButtonMode.TAP),
            ShortcutButton("Fill", "g", ButtonMode.TAP),
            ShortcutButton("Pick color", "alt", ButtonMode.HOLD),
            ShortcutButton("Pan", "space", ButtonMode.HOLD),
            ShortcutButton("Rotate", "shift+space", ButtonMode.HOLD),
            ShortcutButton("Transform", "ctrl+t", ButtonMode.TAP),
        )
        dialModes.clear()
        dialModes += listOf(
            DialMode("Brush size", "]", "["),
            DialMode("Zoom", "ctrl+plus", "ctrl+minus"),
            DialMode("Undo / Redo", "ctrl+y", "ctrl+z"),
            DialMode("Layer", "alt+]", "alt+["),
        )
        dialModeIndex = 0
        sideMode = SideButton.HOLD_KEYS
        sideKeys = "e"
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("version", 1)
        put("panelOnLeft", panelOnLeft)
        put("showTrail", showTrail)
        put("pressure", pressure.name)
        put("sideMode", sideMode.name)
        put("sideKeys", sideKeys)
        put("manualAddress", manualAddress)
        put("displayIndex", displayIndex)
        put("lastPcName", lastPcName)
        put("lastAspect", lastAspect.toDouble())
        put("dialModeIndex", dialModeIndex)
        put("buttons", JSONArray().apply {
            buttons.forEach { put(JSONObject().put("label", it.label).put("keys", it.keys).put("mode", it.mode.name)) }
        })
        put("dial", JSONArray().apply {
            dialModes.forEach { put(JSONObject().put("label", it.label).put("up", it.up).put("down", it.down)) }
        })
    }

    fun save(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, toJson().toString()).apply()
    }

    companion object {
        private const val PREFS = "penbridge"
        private const val KEY = "config"

        fun defaults() = Config().apply { resetShortcuts() }

        fun load(context: Context): Config {
            val text = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
                ?: return defaults()
            return try {
                fromJson(JSONObject(text))
            } catch (_: Exception) {
                defaults()
            }
        }

        private inline fun <reified T : Enum<T>> enumOr(name: String?, fallback: T): T =
            enumValues<T>().firstOrNull { it.name == name } ?: fallback

        fun fromJson(o: JSONObject) = Config().apply {
            panelOnLeft = o.optBoolean("panelOnLeft", true)
            showTrail = o.optBoolean("showTrail", true)
            pressure = enumOr(o.optString("pressure"), PressureFeel.NORMAL)
            sideMode = enumOr(o.optString("sideMode"), SideButton.HOLD_KEYS)
            sideKeys = o.optString("sideKeys", "e")
            manualAddress = o.optString("manualAddress", "")
            displayIndex = o.optInt("displayIndex", -1)
            lastPcName = o.optString("lastPcName", "")
            lastAspect = o.optDouble("lastAspect", 16.0 / 9.0).toFloat()
            dialModeIndex = o.optInt("dialModeIndex", 0)
            o.optJSONArray("buttons")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val b = arr.getJSONObject(i)
                    buttons += ShortcutButton(
                        b.optString("label"),
                        b.optString("keys"),
                        enumOr(b.optString("mode"), ButtonMode.TAP),
                    )
                }
            }
            o.optJSONArray("dial")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val d = arr.getJSONObject(i)
                    dialModes += DialMode(d.optString("label"), d.optString("up"), d.optString("down"))
                }
            }
        }
    }
}
