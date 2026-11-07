package app.penbridge

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.ImageView
import android.widget.LinearLayout
import kotlin.math.abs

/** One shortcut key on the side panel. Tap buttons fire once; hold buttons stay pressed on the PC. */
@SuppressLint("ViewConstructor")
class ShortcutButtonView(context: Context, val button: ShortcutButton, private val host: Host) : View(context) {

    interface Host {
        fun onShortcut(button: ShortcutButton, down: Boolean)
    }

    private var down = false
    private val rect = RectF()
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.HOLD }
    private val label = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    init {
        contentDescription = button.label
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                down = true
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                host.onShortcut(button, true)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> release()
        }
        return true
    }

    fun release() {
        if (down) {
            down = false
            host.onShortcut(button, false)
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val g = dp(3f)
        rect.set(g, g, width - g, height - g)
        bg.color = when {
            down && button.mode == ButtonMode.HOLD -> Palette.HOLD
            down -> Palette.ACCENT
            else -> Palette.BUTTON
        }
        canvas.drawRoundRect(rect, dp(9f), dp(9f), bg)
        if (button.mode == ButtonMode.HOLD && !down) {
            // A small bar marks "hold" buttons.
            val bw = rect.width() * 0.28f
            val cx = rect.centerX()
            canvas.drawRoundRect(cx - bw / 2, rect.bottom - dp(6f), cx + bw / 2, rect.bottom - dp(3.5f), dp(2f), dp(2f), bar)
        }

        label.color = if (down) 0xFF0E0F12.toInt() else Palette.TEXT
        var size = sp(14f)
        label.textSize = size
        val maxW = rect.width() - dp(8f)
        while (label.measureText(button.label) > maxW && size > sp(9f)) {
            size -= sp(0.5f)
            label.textSize = size
        }
        val text = TextUtils.ellipsize(button.label, label, maxW, TextUtils.TruncateAt.END).toString()
        val y = rect.centerY() - (label.descent() + label.ascent()) / 2
        canvas.drawText(text, rect.centerX(), y, label)
    }
}

/**
 * A scroll-wheel style dial, like the dials on a pen tablet.
 * Drag up or down to step; tap to switch to the next mode.
 */
@SuppressLint("ViewConstructor")
class DialView(context: Context, private val host: Host) : View(context) {

    interface Host {
        fun onDialStep(mode: DialMode, up: Boolean)
        fun onDialModeChanged(index: Int)
    }

    var modes: List<DialMode> = emptyList()
        set(v) {
            field = v
            invalidate()
        }

    var index = 0
        set(v) {
            field = if (modes.isEmpty()) 0 else Math.floorMod(v, modes.size)
            invalidate()
        }

    private val step = dp(18f)
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var lastY = 0f
    private var accum = 0f
    private var travel = 0f
    private var offset = 0f
    private var downAt = 0L
    private var active = false

    private val rect = RectF()
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.SURFACE }
    private val ridge = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = dp(1.5f) }
    private val title = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.TEXT
        textSize = sp(13f)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.MUTED
        textSize = sp(10f)
        textAlign = Paint.Align.CENTER
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // Dragging here must not trigger the system back gesture.
        systemGestureExclusionRects = listOf(Rect(0, 0, right - left, bottom - top))
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                lastY = e.y
                accum = 0f
                travel = 0f
                downAt = e.eventTime
                active = true
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = lastY - e.y
                lastY = e.y
                accum += dy
                travel += abs(dy)
                offset += dy
                val mode = modes.getOrNull(index)
                while (accum >= step) {
                    accum -= step
                    tick(mode, true)
                }
                while (accum <= -step) {
                    accum += step
                    tick(mode, false)
                }
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                active = false
                if (travel < slop && e.eventTime - downAt < 350 && modes.size > 1) {
                    index += 1
                    performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    host.onDialModeChanged(index)
                }
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                active = false
                invalidate()
            }
        }
        return true
    }

    private fun tick(mode: DialMode?, up: Boolean) {
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        if (mode != null) host.onDialStep(mode, up)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val g = dp(3f)
        rect.set(g, g, w - g, h - g)
        canvas.drawRoundRect(rect, dp(12f), dp(12f), bg)

        val labelH = dp(28f)
        val hintH = dp(20f)
        val name = modes.getOrNull(index)?.label ?: "No dial modes"
        val shown = TextUtils.ellipsize(name, title, rect.width() - dp(12f), TextUtils.TruncateAt.END).toString()
        canvas.drawText(shown, w / 2, rect.top + labelH / 2 - (title.descent() + title.ascent()) / 2, title)
        val dots = if (modes.size > 1) "  ·  tap to switch" else ""
        canvas.drawText("drag ↕$dots", w / 2, rect.bottom - hintH / 2 - (hint.descent() + hint.ascent()) / 2, hint)

        // Ridges that roll with your finger.
        val top = rect.top + labelH
        val bottom = rect.bottom - hintH
        val mid = (top + bottom) / 2
        val half = (bottom - top) / 2
        val spacing = dp(8f)
        val shift = ((offset % spacing) + spacing) % spacing
        var y = top - spacing + shift
        while (y <= bottom) {
            if (y >= top) {
                val closeness = 1f - abs(y - mid) / half
                ridge.color = if (active) Palette.ACCENT else Palette.MUTED
                ridge.alpha = (closeness.coerceIn(0f, 1f) * (if (active) 255 else 150)).toInt()
                canvas.drawLine(rect.left + dp(14f), y, rect.right - dp(14f), y, ridge)
            }
            y += spacing
        }
    }
}

/** Small pill showing the connection state. */
class StatusChip(context: Context) : View(context) {
    private var text = "Starting…"
    private var dotColor = Palette.MUTED
    private val rect = RectF()
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.SURFACE }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.TEXT
        textSize = sp(12f)
    }

    init {
        isClickable = true
        contentDescription = text
    }

    fun set(text: String, color: Int) {
        if (text == this.text && color == dotColor) return
        this.text = text
        dotColor = color
        contentDescription = text
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val g = dp(3f)
        rect.set(g, g, width - g, height - g)
        val r = rect.height() / 2
        bg.alpha = if (isPressed) 170 else 255
        canvas.drawRoundRect(rect, r, r, bg)
        dot.color = dotColor
        val cx = rect.left + dp(12f)
        canvas.drawCircle(cx, rect.centerY(), dp(4f), dot)
        val x = cx + dp(10f)
        val shown = TextUtils.ellipsize(text, label, rect.right - x - dp(8f), TextUtils.TruncateAt.END).toString()
        canvas.drawText(shown, x, rect.centerY() - (label.descent() + label.ascent()) / 2, label)
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }
}

/** The side panel: connection status, shortcut buttons and the dial. */
@SuppressLint("ViewConstructor")
class PanelView(context: Context, private val host: Host) : LinearLayout(context) {

    interface Host : ShortcutButtonView.Host, DialView.Host {
        fun onStatusTapped()
        fun onSettingsTapped()
    }

    val status = StatusChip(context).apply { setOnClickListener { host.onStatusTapped() } }
    val dial = DialView(context, host)
    private val grid = LinearLayout(context).apply { orientation = VERTICAL }
    private val buttonViews = mutableListOf<ShortcutButtonView>()

    init {
        orientation = VERTICAL
        setBackgroundColor(Palette.PANEL)
        val p = dpi(6)
        setPadding(p, p, p, p)

        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(status, LayoutParams(0, dpi(36), 1f))
        val gear = ImageView(context).apply {
            setImageResource(R.drawable.ic_settings)
            imageTintList = ColorStateList.valueOf(Palette.MUTED)
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = "Settings"
            val tv = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
            if (tv.resourceId != 0) setBackgroundResource(tv.resourceId)
            setOnClickListener { host.onSettingsTapped() }
        }
        header.addView(gear, LayoutParams(dpi(38), dpi(36)))
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = dpi(4)
            bottomMargin = dpi(4)
        })
        addView(dial, LayoutParams(LayoutParams.MATCH_PARENT, dpi(118)))
    }

    fun bind(config: Config) {
        releaseAll()
        grid.removeAllViews()
        buttonViews.clear()
        config.buttons.take(MAX_BUTTONS).chunked(2).forEach { pair ->
            val row = LinearLayout(context).apply { orientation = HORIZONTAL }
            pair.forEach { b ->
                val v = ShortcutButtonView(context, b, host)
                buttonViews += v
                row.addView(v, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
            }
            if (pair.size == 1) row.addView(View(context), LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
            grid.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        }
        dial.modes = config.dialModes.toList()
        dial.index = config.dialModeIndex
    }

    /** Lets go of any held buttons (e.g. when the app is paused). */
    fun releaseAll() = buttonViews.forEach { it.release() }

    companion object {
        const val MAX_BUTTONS = 16
    }
}
