package dev.southstar21.vectra.android.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.ui.unit.Dp
import androidx.glance.unit.ColorProvider
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.createBitmap
import kotlin.math.ceil

// What Glance can't do on its own: a determinate ring (drawn into a bitmap) and the layout budget
// (Glance has no "fits" check, so the widgets add up their rows' heights before choosing them).

/** The countdown ring as a bitmap: a track and an arc from twelve o'clock. */
internal fun ringBitmap(context: Context, sizeDp: Dp, strokeDp: Dp, fraction: Float, arc: ColorProvider, track: ColorProvider): Bitmap {
    val density = context.resources.displayMetrics.density
    val px = (sizeDp.value * density).toInt().coerceAtLeast(1)
    val stroke = strokeDp.value * density
    val bitmap = createBitmap(px, px)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        strokeCap = Paint.Cap.ROUND
    }
    val box = RectF(stroke / 2, stroke / 2, px - stroke / 2, px - stroke / 2)
    paint.color = track.getColor(context).toArgb()
    canvas.drawArc(box, 0f, 360f, false, paint)
    if (fraction > 0f) {
        paint.color = arc.getColor(context).toArgb()
        canvas.drawArc(box, -90f, 360f * fraction.coerceAtMost(1f), false, paint)
    }
    return bitmap
}

/**
 * Rough text metrics for the budget (Roboto): a line is 1.18 em tall, an average glyph about 0.5 em
 * wide (0.55 bold), both scaled by the user's font size.
 */
internal class Budget(context: Context) {
    private val scale = context.resources.configuration.fontScale

    fun line(sp: Float): Float = sp * 1.18f * scale

    /** Lines [text] takes at [sp] in [widthDp], at most [max]. */
    fun lines(text: String, sp: Float, widthDp: Float, bold: Boolean = true, max: Int = 3): Int {
        val perLine = (widthDp / (sp * scale * if (bold) 0.55f else 0.5f)).coerceAtLeast(1f)
        return ceil(text.length / perLine).toInt().coerceIn(1, max)
    }

    fun height(text: String, sp: Float, widthDp: Float, bold: Boolean = true, max: Int = 3): Float = line(sp) * lines(text, sp, widthDp, bold, max)
}
