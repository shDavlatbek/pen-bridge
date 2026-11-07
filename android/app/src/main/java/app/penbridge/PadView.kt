package app.penbridge

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The drawing area. Reads the S Pen (position, pressure, tilt, hover, side
 * button) and sends it to the PC. Finger and palm touches are ignored.
 * Its size always matches the PC display's shape, so circles stay round.
 */
@SuppressLint("ViewConstructor")
class PadView(context: Context, private val host: Host) : View(context) {

    interface Host {
        fun currentConfig(): Config
        fun sendPen(flags: Int, x: Int, y: Int, pressure: Int, tiltX: Int, tiltY: Int)
        fun onSideButton(pressed: Boolean)
    }

    var connected = false
        set(v) {
            field = v
            invalidate()
        }

    /** Shown in the middle while not connected. */
    var message = ""
        set(v) {
            field = v
            invalidate()
        }

    /** Small label in the corner, e.g. "Display 1 · 2560×1440". */
    var caption = ""
        set(v) {
            field = v
            invalidate()
        }

    private var hovering = false
    private var touching = false
    private var sideDown = false
    private var eraserNow = false
    private var lastX = 0f
    private var lastY = 0f
    private var lastNx = 0
    private var lastNy = 0
    private var lastPressure = 0f
    private val leave = Runnable { penLeft() }

    private class TrailPoint(val x: Float, val y: Float, val p: Float, val t: Long, val stroke: Int)

    private val trail = ArrayDeque<TrailPoint>()
    private var strokeId = 0

    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Palette.BORDER
        strokeWidth = dp(1f)
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.BORDER }
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Palette.ACCENT
    }
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.TEXT
        textSize = sp(17f)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.MUTED
        textSize = sp(13f)
        textAlign = Paint.Align.CENTER
    }
    private val captionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF4A4E58.toInt()
        textSize = sp(11f)
    }
    private val frameRect = RectF()

    init {
        setBackgroundColor(Palette.PAD)
        // Hide Android's hover pointer: we draw our own cursor.
        pointerIcon = PointerIcon.getSystemIcon(context, PointerIcon.TYPE_NULL)
    }

    override fun onResolvePointerIcon(event: MotionEvent, pointerIndex: Int): PointerIcon =
        PointerIcon.getSystemIcon(context, PointerIcon.TYPE_NULL)

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // Keep the system back gesture from stealing strokes that start near the screen edges.
        val w = right - left
        val h = bottom - top
        val band = dpi(200).coerceAtMost(h)
        val top0 = (h - band) / 2
        val edge = dpi(32)
        systemGestureExclusionRects = listOf(Rect(0, top0, edge, top0 + band), Rect(w - edge, top0, w, top0 + band))
    }

    private fun penIndex(e: MotionEvent): Int {
        for (i in 0 until e.pointerCount) {
            val t = e.getToolType(i)
            if (t == MotionEvent.TOOL_TYPE_STYLUS || t == MotionEvent.TOOL_TYPE_ERASER) return i
        }
        return -1
    }

    override fun onHoverEvent(e: MotionEvent): Boolean {
        val i = penIndex(e)
        if (i < 0) return super.onHoverEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                removeCallbacks(leave)
                if (!touching) {
                    hovering = true
                    updateSideButton(e, false)
                    emitAll(e, i, Proto.FLAG_IN_RANGE, history = true)
                }
            }
            // Android sends HOVER_EXIT right before the tip touches, so wait a moment
            // before telling the PC the pen has left.
            MotionEvent.ACTION_HOVER_EXIT -> {
                removeCallbacks(leave)
                postDelayed(leave, LEAVE_DELAY_MS)
            }
        }
        return true
    }

    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        val a = e.actionMasked
        if ((a == MotionEvent.ACTION_BUTTON_PRESS || a == MotionEvent.ACTION_BUTTON_RELEASE) && penIndex(e) >= 0) {
            updateSideButton(e, false)
            return true
        }
        return super.onGenericMotionEvent(e)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        // Older Samsung firmware reports "touch with S Pen button held" as actions 211-213.
        var action = e.actionMasked
        var legacyButton = false
        when (e.action) {
            211 -> { action = MotionEvent.ACTION_DOWN; legacyButton = true }
            212 -> { action = MotionEvent.ACTION_UP; legacyButton = true }
            213 -> { action = MotionEvent.ACTION_MOVE; legacyButton = true }
        }
        val i = penIndex(e)
        if (i < 0) return true // fingers and palms are ignored
        val penChanged = e.actionIndex == i || action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_UP

        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> if (penChanged && !touching) {
                removeCallbacks(leave)
                if (Build.VERSION.SDK_INT >= 30) requestUnbufferedDispatch(e)
                touching = true
                hovering = true
                strokeId++
                updateSideButton(e, legacyButton)
                emitAll(e, i, Proto.FLAG_IN_RANGE or Proto.FLAG_TIP, history = false)
            }
            MotionEvent.ACTION_MOVE -> if (touching) {
                updateSideButton(e, legacyButton)
                emitAll(e, i, Proto.FLAG_IN_RANGE or Proto.FLAG_TIP, history = true)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> if (penChanged && touching) {
                touching = false
                emit(e.getX(i), e.getY(i), 0f, 0f, 0f, Proto.FLAG_IN_RANGE, e.getToolType(i))
                // If no hover follows (pen lifted far away), the pen has left.
                removeCallbacks(leave)
                postDelayed(leave, AFTER_UP_LEAVE_MS)
            }
            MotionEvent.ACTION_CANCEL -> release()
        }
        return true
    }

    private fun updateSideButton(e: MotionEvent, legacy: Boolean) {
        val mask = MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_STYLUS_SECONDARY or MotionEvent.BUTTON_SECONDARY
        val pressed = legacy || (e.buttonState and mask) != 0
        if (pressed != sideDown) {
            sideDown = pressed
            host.onSideButton(pressed)
            invalidate()
        }
    }

    private fun emitAll(e: MotionEvent, i: Int, flags: Int, history: Boolean) {
        val tool = e.getToolType(i)
        if (history) {
            for (h in 0 until e.historySize) {
                emit(
                    e.getHistoricalX(i, h), e.getHistoricalY(i, h), e.getHistoricalPressure(i, h),
                    e.getHistoricalAxisValue(MotionEvent.AXIS_TILT, i, h),
                    e.getHistoricalAxisValue(MotionEvent.AXIS_ORIENTATION, i, h),
                    flags, tool,
                )
            }
        }
        emit(
            e.getX(i), e.getY(i), e.getPressure(i),
            e.getAxisValue(MotionEvent.AXIS_TILT, i), e.getAxisValue(MotionEvent.AXIS_ORIENTATION, i),
            flags, tool,
        )
    }

    private fun emit(x: Float, y: Float, rawPressure: Float, tilt: Float, orientation: Float, baseFlags: Int, tool: Int) {
        val cfg = host.currentConfig()
        val w = width.coerceAtLeast(1).toFloat()
        val h = height.coerceAtLeast(1).toFloat()
        val nx = ((x / w).coerceIn(0f, 1f) * 65535f).roundToInt()
        val ny = ((y / h).coerceIn(0f, 1f) * 65535f).roundToInt()

        var flags = baseFlags
        eraserNow = tool == MotionEvent.TOOL_TYPE_ERASER || (sideDown && cfg.sideMode == SideButton.ERASER)
        if (eraserNow) flags = flags or Proto.FLAG_ERASER
        if (sideDown && cfg.sideMode == SideButton.BARREL) flags = flags or Proto.FLAG_BARREL

        val tip = flags and Proto.FLAG_TIP != 0
        val p = if (tip) applyCurve(rawPressure, cfg.pressure.gamma) else 0f
        var tiltX = 0
        var tiltY = 0
        if (tilt > 0.01f) {
            // Android gives tilt (from vertical) + direction; PCs want tilt along X and Y.
            val r = sin(tilt.toDouble())
            val z = cos(tilt.toDouble())
            tiltX = Math.toDegrees(atan2(sin(orientation.toDouble()) * r, z)).roundToInt()
            tiltY = Math.toDegrees(atan2(-cos(orientation.toDouble()) * r, z)).roundToInt()
        }

        host.sendPen(flags, nx, ny, (p * 65535f).roundToInt(), tiltX, tiltY)

        lastX = x
        lastY = y
        lastNx = nx
        lastNy = ny
        lastPressure = p
        if (tip && cfg.showTrail) trail.addLast(TrailPoint(x, y, p, SystemClock.uptimeMillis(), strokeId))
        postInvalidateOnAnimation()
    }

    private fun applyCurve(p: Float, gamma: Float): Float {
        val c = p.coerceIn(0f, 1f)
        return if (gamma == 1f) c else c.pow(gamma)
    }

    private fun penLeft() {
        removeCallbacks(leave)
        if (touching) return
        if (hovering) {
            hovering = false
            host.sendPen(0, lastNx, lastNy, 0, 0, 0)
        }
        if (sideDown) {
            sideDown = false
            host.onSideButton(false)
        }
        invalidate()
    }

    /** Lifts and removes the pen, e.g. when the app goes to the background. */
    fun release() {
        if (touching) {
            touching = false
            host.sendPen(Proto.FLAG_IN_RANGE, lastNx, lastNy, 0, 0, 0)
        }
        penLeft()
        trail.clear()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val inset = dp(0.5f)
        frameRect.set(inset, inset, w - inset, h - inset)
        canvas.drawRoundRect(frameRect, dp(10f), dp(10f), framePaint)

        // Corner marks, like the dots on a pen tablet.
        val m = dp(14f)
        val r = dp(2f)
        canvas.drawCircle(m, m, r, dotPaint)
        canvas.drawCircle(w - m, m, r, dotPaint)
        canvas.drawCircle(m, h - m, r, dotPaint)
        canvas.drawCircle(w - m, h - m, r, dotPaint)

        if (!connected && message.isNotEmpty()) {
            val lines = message.split('\n')
            var y = h / 2 - (lines.size - 1) * sp(10f)
            lines.forEachIndexed { idx, line ->
                canvas.drawText(line, w / 2, y, if (idx == 0) titlePaint else bodyPaint)
                y += if (idx == 0) sp(28f) else sp(20f)
            }
        }
        if (connected && caption.isNotEmpty()) {
            canvas.drawText(caption, dp(28f), h - dp(10f), captionPaint)
        }

        // Fading ink trail, just as feedback; the real ink appears on the PC.
        val now = SystemClock.uptimeMillis()
        while (trail.isNotEmpty() && now - trail.first().t > TRAIL_MS) trail.removeFirst()
        var prev: TrailPoint? = null
        for (pt in trail) {
            val pp = prev
            if (pp != null && pp.stroke == pt.stroke) {
                val fade = 1f - (now - pt.t).toFloat() / TRAIL_MS
                trailPaint.alpha = (fade * 220).toInt().coerceIn(0, 255)
                trailPaint.strokeWidth = dp(1.2f) + pt.p * dp(3.5f)
                canvas.drawLine(pp.x, pp.y, pt.x, pt.y, trailPaint)
            }
            prev = pt
        }

        if (touching) {
            fillPaint.color = if (eraserNow) Palette.WARN else Palette.ACCENT
            canvas.drawCircle(lastX, lastY, dp(2.5f) + lastPressure * dp(4f), fillPaint)
        } else if (hovering) {
            cursorPaint.color = when {
                eraserNow -> Palette.WARN
                sideDown -> Palette.HOLD
                else -> Palette.MUTED
            }
            canvas.drawCircle(lastX, lastY, dp(7f), cursorPaint)
        }

        if (trail.isNotEmpty()) postInvalidateOnAnimation()
    }

    companion object {
        private const val LEAVE_DELAY_MS = 80L
        private const val AFTER_UP_LEAVE_MS = 150L
        private const val TRAIL_MS = 450L
    }
}
