package com.bgdeveloper.ridelirium.share

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import com.bgdeveloper.ridelirium.R

data class ShareCardData(
    val placeName: String,
    val date: String,
    val distance: String,
    val distanceUnit: String,
    val maxSpeed: String,
)

/** The two card sizes: 9:16 for Stories, 4:5 for the feed (the tallest ratio feeds show uncropped). */
enum class ShareCardFormat(val width: Int, val height: Int, internal val scale: Float) {
    Story(1080, 1920, 1f),
    Feed(1080, 1350, 0.8f);

    /** The map takes the top 52%, as on the website mockup; the red panel the rest. */
    val mapHeight: Int get() = (height * 0.52f).toInt()
}

/** Draws a share card: route map on top, ride stats on a red panel below. */
object ShareCardRenderer {
    private const val RED = 0xFFED213D.toInt()
    private const val MAP_BACKGROUND = 0xFF141B24.toInt()

    /** [map] is a snapshot of the stats map (dark style, runs red, lifts blue, stops grey), framed for [format]. */
    fun render(
        context: Context,
        map: Bitmap?,
        data: ShareCardData,
        format: ShareCardFormat = ShareCardFormat.Story,
    ): Bitmap {
        val width = format.width
        val height = format.height
        val mapHeight = format.mapHeight
        val s = format.scale
        val padding = 72f * s

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        canvas.drawColor(MAP_BACKGROUND)
        map?.let { drawCentered(canvas, it, RectF(0f, 0f, width.toFloat(), mapHeight.toFloat())) }

        canvas.drawRect(0f, mapHeight.toFloat(), width.toFloat(), height.toFloat(), Paint().apply { color = RED })

        val bold = font(context, R.font.opensans_condensed_bold)
        val extraBold = font(context, R.font.opensans_condensed_extrabold)
        val maxTextWidth = width - 2 * padding

        var y = mapHeight + padding
        val kicker = text(bold, 48f * s, 0.04f)
        val kickerText = "${data.placeName} · ${data.date}".uppercase()
        while (kicker.measureText(kickerText) > maxTextWidth && kicker.textSize > 24f * s) kicker.textSize -= 2f
        y += 48f * s
        canvas.drawText(kickerText, padding, y, kicker)

        val distance = text(extraBold, 288f * s, -0.02f)
        y += 290f * s
        canvas.drawText(data.distance, padding, y, distance)
        val unit = text(bold, 72f * s, 0f)
        canvas.drawText(data.distanceUnit.uppercase(), padding + distance.measureText(data.distance) + 24f * s, y, unit)

        y += 110f * s
        canvas.drawText("${data.maxSpeed} MAX".uppercase(), padding, y, text(bold, 72f * s, 0f))

        val brand = text(bold, 42f * s, 0.2f).apply { alpha = (255 * 0.7f).toInt() }
        canvas.drawText("RIDELIRIUM", padding, height - padding, brand)
        return bitmap
    }

    /** Fills [area], cropping the overflow like `scaledToFill`. */
    private fun drawCentered(canvas: Canvas, map: Bitmap, area: RectF) {
        val scale = maxOf(area.width() / map.width, area.height() / map.height)
        val w = map.width * scale
        val h = map.height * scale
        val left = area.left + (area.width() - w) / 2
        val top = area.top + (area.height() - h) / 2
        canvas.save()
        canvas.clipRect(area)
        canvas.drawBitmap(map, Rect(0, 0, map.width, map.height), RectF(left, top, left + w, top + h), Paint(Paint.FILTER_BITMAP_FLAG))
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
