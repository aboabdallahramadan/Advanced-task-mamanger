package net.qmindtech.tmap.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF

/** Renders the amber completion ring as a Bitmap (Glance has no Canvas/arc primitive). */
object ProgressRingBitmap {

    /** progress (0f..1f, clamped) → arc sweep in degrees (0..360). Pure + unit-tested. */
    fun sweepDegrees(progress: Float): Float = progress.coerceIn(0f, 1f) * 360f

    fun render(progress: Float, sizePx: Int): Bitmap {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val stroke = sizePx * 0.11f
        val inset = stroke / 2f + sizePx * 0.02f
        val rect = RectF(inset, inset, sizePx - inset, sizePx - inset)

        val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = stroke; color = 0xFF2A2B31.toInt()
        }
        val fg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = stroke; strokeCap = Paint.Cap.ROUND
            color = 0xFFE8A87C.toInt()
        }
        canvas.drawArc(rect, 0f, 360f, false, track)
        canvas.drawArc(rect, -90f, sweepDegrees(progress), false, fg)
        return bmp
    }
}
