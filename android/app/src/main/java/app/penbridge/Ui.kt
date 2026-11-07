package app.penbridge

import android.content.Context
import android.util.TypedValue
import android.view.View

object Palette {
    const val BG = 0xFF0E0F12.toInt()
    const val PAD = 0xFF121317.toInt()
    const val PANEL = 0xFF17181C.toInt()
    const val SURFACE = 0xFF202227.toInt()
    const val BUTTON = 0xFF25272D.toInt()
    const val BORDER = 0xFF2C2F37.toInt()
    const val TEXT = 0xFFE7E8EB.toInt()
    const val MUTED = 0xFF8D929C.toInt()
    const val ACCENT = 0xFF5B8CFF.toInt()
    const val HOLD = 0xFFA78BFA.toInt()
    const val OK = 0xFF3DD68C.toInt()
    const val WARN = 0xFFF2B33D.toInt()
}

fun Context.dp(v: Float): Float = v * resources.displayMetrics.density
fun Context.dpi(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun Context.sp(v: Float): Float =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)
fun View.dp(v: Float): Float = context.dp(v)
fun View.dpi(v: Int): Int = context.dpi(v)
fun View.sp(v: Float): Float = context.sp(v)
