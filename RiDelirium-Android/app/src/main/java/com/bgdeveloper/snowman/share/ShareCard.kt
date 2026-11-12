package com.bgdeveloper.snowman.share

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import com.bgdeveloper.snowman.R

data class ShareCardData(
    val placeName: String,
    val date: String,
    val distance: String,
    val distanceUnit: String,
    val maxSpeed: String,
)

/** Draws the 9:16 Story card: route map on top, ride stats on a red panel below. 1080 × 1920 px. */
object ShareCardRenderer {
    private const val WIDTH = 1080
    private const val HEIGHT = 1920
    private const val MAP_HEIGHT = 1080
    private const val PADDING = 72f
    private const val RED = 0xFFED213D.toInt()
    private const val MAP_BACKGROUND = 0xFF141B24.toInt()

    fun render(context: Context, map: Bitmap?, data: ShareCardData): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        canvas.drawColor(MAP_BACKGROUND)
        map?.let { drawCentered(canvas, it) }

        canvas.drawRect(0f, MAP_HEIGHT.toFloat(), WIDTH.toFloat(), HEIGHT.toFloat(), Paint().apply { color = RED })

        val bold = font(context, R.font.opensans_condensed_bold)
        val extraBold = font(context, R.font.opensans_condensed_extrabold)
        val maxTextWidth = WIDTH - 2 * PADDING

        var y = MAP_HEIGHT + PADDING
        val kicker = text(bold, 48f, 0.04f)
        val kickerText = "${data.placeName} · ${data.date}".uppercase()
        while (kicker.measureText(kickerText) > maxTextWidth && kicker.textSize > 24f) kicker.textSize -= 2f
        y += 48f
        canvas.drawText(kickerText, PADDING, y, kicker)

        val distance = text(extraBold, 288f, -0.02f)
        y += 290f
        canvas.drawText(data.distance, PADDING, y, distance)
        val unit = text(bold, 72f, 0f)
        canvas.drawText(data.distanceUnit.uppercase(), PADDING + distance.measureText(data.distance) + 24f, y, unit)

        y += 110f
        canvas.drawText("${data.maxSpeed} MAX", PADDING, y, text(bold, 72f, 0f))

        val brand = text(bold, 42f, 0.2f).apply { alpha = (255 * 0.7f).toInt() }
        canvas.drawText("SNOWBOARDER", PADDING, HEIGHT - PADDING, brand)
        return bitmap
    }

    /** Fills the map area, cropping the overflow like `scaledToFill`. */
    private fun drawCentered(canvas: Canvas, map: Bitmap) {
        val scale = maxOf(WIDTH / map.width.toFloat(), MAP_HEIGHT / map.height.toFloat())
        val w = map.width * scale
        val h = map.height * scale
        val left = (WIDTH - w) / 2
        val top = (MAP_HEIGHT - h) / 2
        canvas.save()
        canvas.clipRect(0, 0, WIDTH, MAP_HEIGHT)
        canvas.drawBitmap(
            map, Rect(0, 0, map.width, map.height),
            android.graphics.RectF(left, top, left + w, top + h), Paint(Paint.FILTER_BITMAP_FLAG),
        )
        canvas.restore()
    }

    private fun font(context: Context, id: Int): Typeface =
        ResourcesCompat.getFont(context, id) ?: Typeface.DEFAULT_BOLD

    private fun text(face: Typeface, size: Float, spacing: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = face
        textSize = size
        letterSpacing = spacing
        color = Color.WHITE
    }
}
