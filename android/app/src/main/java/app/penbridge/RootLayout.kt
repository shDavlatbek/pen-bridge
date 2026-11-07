package app.penbridge

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.view.ViewGroup
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Places the shortcut panel on one side and gives the pad the largest area
 * with the same shape (aspect ratio) as the PC display.
 */
@SuppressLint("ViewConstructor")
class RootLayout(context: Context, private val pad: View, private val panel: View) : ViewGroup(context) {

    var aspect = 16f / 9f
        set(v) {
            val c = v.coerceIn(0.3f, 4f)
            if (c != field) {
                field = c
                requestLayout()
            }
        }

    var panelOnLeft = true
        set(v) {
            if (v != field) {
                field = v
                requestLayout()
            }
        }

    private val minPanel = dpi(150)
    private val maxPanel = dpi(280)
    private var padW = 0
    private var padH = 0
    private var panelW = 0

    init {
        setBackgroundColor(Palette.BG)
        addView(panel)
        addView(pad)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        padW = min((h * aspect).roundToInt(), w - minPanel).coerceAtLeast(1)
        padH = min(h, (padW / aspect).roundToInt()).coerceAtLeast(1)
        panelW = (w - padW).coerceIn(min(minPanel, w), min(maxPanel, w))
        panel.measure(exactly(panelW), exactly(h))
        pad.measure(exactly(padW), exactly(padH))
        setMeasuredDimension(w, h)
    }

    private fun exactly(size: Int) = MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY)

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        val h = b - t
        val free = w - panelW
        val padLeft = (free - padW) / 2
        val top = (h - padH) / 2
        if (panelOnLeft) {
            panel.layout(0, 0, panelW, h)
            val x = panelW + padLeft
            pad.layout(x, top, x + padW, top + padH)
        } else {
            panel.layout(w - panelW, 0, w, h)
            pad.layout(padLeft, top, padLeft + padW, top + padH)
        }
    }
}
