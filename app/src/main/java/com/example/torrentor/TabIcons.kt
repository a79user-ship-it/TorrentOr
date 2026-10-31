package com.example.torrentor

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.widget.TextView
import androidx.core.graphics.PathParser

// Flat, multi-colour icons for buttons and tabs.
//
// Every icon is a few coloured shapes drawn in a 24x24 box: solid fills for
// the main body and thick rounded strokes for details. The same shapes (and
// colours) are used in the Web UI (see WebUiServer.kt), so the app and the
// browser look alike. The icons keep their own colours on every theme.
//
// Usage:  TabIcons.apply(someButton, "rss")
object TabIcons {

    // One coloured shape: a fill, a stroke, or both.
    private class Layer(
        val d: String,
        val fill: Int?,
        val stroke: Int?,
        val width: Float
    )

    private fun f(d: String, color: String) = Layer(d, Color.parseColor(color), null, 0f)

    private fun s(d: String, color: String, width: Float) =
        Layer(d, null, Color.parseColor(color), width)

    private fun fs(d: String, fillColor: String, strokeColor: String, width: Float) =
        Layer(d, Color.parseColor(fillColor), Color.parseColor(strokeColor), width)

    private val ICONS: Map<String, List<Layer>> by lazy {
        mapOf(
        "general" to listOf(
            f("M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0z", "#42A5F5"),
            s("M12 11v6", "#FFFFFF", 2.6f),
            s("M12 7h.01", "#FFFFFF", 3.4f)
        ),
        "files" to listOf(
            f("M6 2h8l6 6v12a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2z", "#64B5F6"),
            f("M14 2v6h6z", "#1E88E5"),
            s("M8 13h8", "#FFFFFF", 2f),
            s("M8 17h6", "#FFFFFF", 2f)
        ),
        "priority" to listOf(
            s("M5 3v18", "#90A4AE", 2.2f),
            s("M12 3v18", "#90A4AE", 2.2f),
            s("M19 3v18", "#90A4AE", 2.2f),
            f("M1.8 15a3.2 3.2 0 1 0 6.4 0a3.2 3.2 0 1 0 -6.4 0z", "#EF5350"),
            f("M8.8 8a3.2 3.2 0 1 0 6.4 0a3.2 3.2 0 1 0 -6.4 0z", "#FFCA28"),
            f("M15.8 14a3.2 3.2 0 1 0 6.4 0a3.2 3.2 0 1 0 -6.4 0z", "#42A5F5")
        ),
        "peers" to listOf(
            f("M14 7a3 3 0 1 0 6 0a3 3 0 1 0 -6 0z", "#90CAF9"),
            f("M13 20v-1a4 4 0 0 1 4-4h1a4 4 0 0 1 4 4v1z", "#90CAF9"),
            f("M5.5 8a3.5 3.5 0 1 0 7 0a3.5 3.5 0 1 0 -7 0z", "#42A5F5"),
            f("M2 21v-2a5 5 0 0 1 5-5h4a5 5 0 0 1 5 5v2z", "#1E88E5")
        ),
        "trackers" to listOf(
            f("M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0z", "#EF5350"),
            f("M6 12a6 6 0 1 0 12 0a6 6 0 1 0 -12 0z", "#FFFFFF"),
            f("M9 12a3 3 0 1 0 6 0a3 3 0 1 0 -6 0z", "#EF5350")
        ),
        "webseeds" to listOf(
            f("M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0z", "#42A5F5"),
            f("M7 6c2-1 3 1 5 1s1 3-1 3-1 2-3 2-3-2-2-4 1-2 1-2z", "#66BB6A"),
            f("M14 14c2 0 4 1 4 3s-2 3-3 3-2-2-2-3 0-3 1-3z", "#66BB6A")
        ),
        "swarm" to listOf(
            s("M2 12h5l3-8 4 16 3-8h5", "#26A69A", 2.6f)
        ),
        "pieces" to listOf(
            f("M5 3h4a2 2 0 0 1 2 2v4a2 2 0 0 1 -2 2h-4a2 2 0 0 1 -2 -2v-4a2 2 0 0 1 2 -2z", "#EF5350"),
            f("M15 3h4a2 2 0 0 1 2 2v4a2 2 0 0 1 -2 2h-4a2 2 0 0 1 -2 -2v-4a2 2 0 0 1 2 -2z", "#FFCA28"),
            f("M15 13h4a2 2 0 0 1 2 2v4a2 2 0 0 1 -2 2h-4a2 2 0 0 1 -2 -2v-4a2 2 0 0 1 2 -2z", "#66BB6A"),
            f("M5 13h4a2 2 0 0 1 2 2v4a2 2 0 0 1 -2 2h-4a2 2 0 0 1 -2 -2v-4a2 2 0 0 1 2 -2z", "#42A5F5")
        ),
        "statistics" to listOf(
            f("M4.5 11h2a1.5 1.5 0 0 1 1.5 1.5v7a1.5 1.5 0 0 1 -1.5 1.5h-2a1.5 1.5 0 0 1 -1.5 -1.5v-7a1.5 1.5 0 0 1 1.5 -1.5z", "#42A5F5"),
            f("M11 3h2a1.5 1.5 0 0 1 1.5 1.5v15a1.5 1.5 0 0 1 -1.5 1.5h-2a1.5 1.5 0 0 1 -1.5 -1.5v-15a1.5 1.5 0 0 1 1.5 -1.5z", "#FFA726"),
            f("M17.5 8h2a1.5 1.5 0 0 1 1.5 1.5v10a1.5 1.5 0 0 1 -1.5 1.5h-2a1.5 1.5 0 0 1 -1.5 -1.5v-10a1.5 1.5 0 0 1 1.5 -1.5z", "#66BB6A")
        ),
        "comment" to listOf(
            f("M4 3h16a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H10l-5 4.5V17H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z", "#FFB300"),
            s("M7 8h10", "#FFFFFF", 2f),
            s("M7 12h6", "#FFFFFF", 2f)
        ),
        "pause" to listOf(
            f("M6.5 3h2a1.5 1.5 0 0 1 1.5 1.5v15a1.5 1.5 0 0 1 -1.5 1.5h-2a1.5 1.5 0 0 1 -1.5 -1.5v-15a1.5 1.5 0 0 1 1.5 -1.5z", "#FFA726"),
            f("M15.5 3h2a1.5 1.5 0 0 1 1.5 1.5v15a1.5 1.5 0 0 1 -1.5 1.5h-2a1.5 1.5 0 0 1 -1.5 -1.5v-15a1.5 1.5 0 0 1 1.5 -1.5z", "#FFA726")
        ),
        "play" to listOf(
            fs("M7 4.5v15l13-7.5z", "#66BB6A", "#66BB6A", 2.4f)
        ),
        "select" to listOf(
            f("M6 3h12a3 3 0 0 1 3 3v12a3 3 0 0 1 -3 3h-12a3 3 0 0 1 -3 -3v-12a3 3 0 0 1 3 -3z", "#66BB6A"),
            s("M7.5 12.5l3 3 6-7", "#FFFFFF", 2.6f)
        ),
        "trash" to listOf(
            f("M5 8h14l-1 12a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2z", "#EF5350"),
            s("M3 5h18", "#B0BEC5", 2.6f),
            s("M9 5V3.5A1.5 1.5 0 0 1 10.5 2h3A1.5 1.5 0 0 1 15 3.5V5", "#B0BEC5", 2.2f),
            s("M10 11v6", "#FFFFFF", 1.8f),
            s("M14 11v6", "#FFFFFF", 1.8f)
        ),
        "portfwd" to listOf(
            f("M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0z", "#FFA726"),
            s("M7 12h9", "#FFFFFF", 2.4f),
            s("M12.5 8l4 4-4 4", "#FFFFFF", 2.4f)
        ),
        "network" to listOf(
            s("M2.5 9.5a14 14 0 0 1 19 0", "#90CAF9", 2.4f),
            s("M5.5 13a9.5 9.5 0 0 1 13 0", "#42A5F5", 2.4f),
            s("M8.8 16.2a5 5 0 0 1 6.4 0", "#1E88E5", 2.4f),
            f("M10.1 19.8a1.9 1.9 0 1 0 3.8 0a1.9 1.9 0 1 0 -3.8 0z", "#1E88E5")
        ),
        "connection" to listOf(
            s("M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71", "#42A5F5", 2.6f),
            s("M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71", "#FFA726", 2.6f)
        ),
        "speed" to listOf(
            fs("M13 2L4 14h7l-1 8 10-12h-7l1-8z", "#FFCA28", "#FFA000", 1f)
        ),
        "storage" to listOf(
            f("M5 6h14a3 3 0 0 1 3 3v6a3 3 0 0 1 -3 3h-14a3 3 0 0 1 -3 -3v-6a3 3 0 0 1 3 -3z", "#78909C"),
            f("M2 12V9a3 3 0 0 1 3-3h14a3 3 0 0 1 3 3v3z", "#B0BEC5"),
            f("M5.9 15.2a1.1 1.1 0 1 0 2.2 0a1.1 1.1 0 1 0 -2.2 0z", "#66BB6A"),
            s("M10.5 15.2h6", "#CFD8DC", 1.8f)
        ),
        "folder" to listOf(
            f("M2 6a2 2 0 0 1 2-2h5l2 2.5h9a2 2 0 0 1 2 2V18a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2z", "#FFA726"),
            f("M2 10a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2z", "#FFCA28")
        ),
        "rss" to listOf(
            f("M7 2h10a5 5 0 0 1 5 5v10a5 5 0 0 1 -5 5h-10a5 5 0 0 1 -5 -5v-10a5 5 0 0 1 5 -5z", "#FFA726"),
            s("M6.5 11a6.5 6.5 0 0 1 6.5 6.5", "#FFFFFF", 2.2f),
            s("M6.5 6a11.5 11.5 0 0 1 11.5 11.5", "#FFFFFF", 2.2f),
            f("M5.7 17a1.8 1.8 0 1 0 3.6 0a1.8 1.8 0 1 0 -3.6 0z", "#FFFFFF")
        ),
        "log" to listOf(
            f("M5 4h14a3 3 0 0 1 3 3v10a3 3 0 0 1 -3 3h-14a3 3 0 0 1 -3 -3v-10a3 3 0 0 1 3 -3z", "#37474F"),
            s("M6 9l3.5 3L6 15", "#66BB6A", 2.2f),
            s("M12 16h6", "#B0BEC5", 2.2f)
        ),
        "search" to listOf(
            fs("M3 10.5a7.5 7.5 0 1 0 15 0a7.5 7.5 0 1 0 -15 0z", "#E3F2FD", "#42A5F5", 2.6f),
            s("M16 16l5.5 5.5", "#EF5350", 3f)
        ),
        "webui" to listOf(
            f("M4 3h16a2 2 0 0 1 2 2v9a2 2 0 0 1 -2 2h-16a2 2 0 0 1 -2 -2v-9a2 2 0 0 1 2 -2z", "#5C6BC0"),
            f("M5 5h14a1 1 0 0 1 1 1v7a1 1 0 0 1 -1 1h-14a1 1 0 0 1 -1 -1v-7a1 1 0 0 1 1 -1z", "#E8EAF6"),
            s("M12 16v4", "#7986CB", 2.4f),
            s("M8 21h8", "#7986CB", 2.6f)
        ),
        "magnet" to listOf(
            s("M5 5v7a7 7 0 0 0 14 0V5", "#EF5350", 4.6f),
            s("M5 4.5v3.2", "#B0BEC5", 4.6f),
            s("M19 4.5v3.2", "#B0BEC5", 4.6f),
            s("M10.2 1.5l-1.6 3h2l-1.2 3", "#FFCA28", 1.6f),
            s("M14.2 1.5l-1.6 3h2l-1.2 3", "#FFCA28", 1.6f)
        ),
        "recheck" to listOf(
            s("M4 12a8 8 0 0 1 14-5.3", "#42A5F5", 2.6f),
            s("M18.5 3v4.5H14", "#42A5F5", 2.6f),
            s("M20 12a8 8 0 0 1-14 5.3", "#66BB6A", 2.6f),
            s("M5.5 21v-4.5H10", "#66BB6A", 2.6f)
        ),
        "reannounce" to listOf(
            f("M22 2L2 9.5l7.5 3 3 7.5z", "#42A5F5"),
            f("M22 2L9.5 12.5l3 7.5z", "#1E88E5")
        ),
        "sort" to listOf(
            s("M3 6h18", "#EF5350", 3f),
            s("M3 12h13", "#FFA726", 3f),
            s("M3 18h8", "#66BB6A", 3f)
        ),
        "filter" to listOf(
            f("M3 4h18l-7 8.5V20l-4-2v-5.5z", "#AB47BC")
        ),
        "list" to listOf(
            s("M9 6h12", "#90A4AE", 2.4f),
            s("M9 12h12", "#90A4AE", 2.4f),
            s("M9 18h12", "#90A4AE", 2.4f),
            f("M2.8 6a1.7 1.7 0 1 0 3.4 0a1.7 1.7 0 1 0 -3.4 0z", "#EF5350"),
            f("M2.8 12a1.7 1.7 0 1 0 3.4 0a1.7 1.7 0 1 0 -3.4 0z", "#FFCA28"),
            f("M2.8 18a1.7 1.7 0 1 0 3.4 0a1.7 1.7 0 1 0 -3.4 0z", "#66BB6A")
        ),
        "download" to listOf(
            s("M12 3v12", "#42A5F5", 3f),
            s("M6.5 10l5.5 5.5 5.5-5.5", "#42A5F5", 3f),
            s("M4 16v3a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-3", "#78909C", 2.6f)
        ),
        "upload" to listOf(
            s("M12 15V3", "#66BB6A", 3f),
            s("M6.5 8.5L12 3l5.5 5.5", "#66BB6A", 3f),
            s("M4 16v3a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-3", "#78909C", 2.6f)
        ),
        "check" to listOf(
            f("M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0z", "#66BB6A"),
            s("M7 12.5l3.5 3.5 6.5-7", "#FFFFFF", 2.8f)
        ),
        "copy" to listOf(
            f("M5 3h8a2 2 0 0 1 2 2v8a2 2 0 0 1 -2 2h-8a2 2 0 0 1 -2 -2v-8a2 2 0 0 1 2 -2z", "#90CAF9"),
            f("M11 9h8a2 2 0 0 1 2 2v8a2 2 0 0 1 -2 2h-8a2 2 0 0 1 -2 -2v-8a2 2 0 0 1 2 -2z", "#42A5F5")
        ),
        "hash" to listOf(
            s("M4 9h16", "#FFA726", 2.6f),
            s("M4 15h16", "#FFA726", 2.6f),
            s("M10 3L8 21", "#AB47BC", 2.6f),
            s("M16 3l-2 18", "#AB47BC", 2.6f)
        ),
        "sequential" to listOf(
            s("M5 6l6 6-6 6", "#90CAF9", 3f),
            s("M12 6l6 6-6 6", "#1E88E5", 3f)
        ),
        "flag" to listOf(
            s("M5 22V3", "#78909C", 2.6f),
            f("M5 4h14l-3 4.5 3 4.5H5z", "#EF5350")
        ),
        "edit" to listOf(
            f("M15.5 4.5l4 4L8 20l-4-4z", "#FFCA28"),
            f("M15.5 4.5l2-2a2.1 2.1 0 0 1 3 0l1 1a2.1 2.1 0 0 1 0 3l-2 2z", "#EF5350"),
            f("M4 16l4 4-5 1z", "#8D6E63")
        ),
        "save" to listOf(
            f("M5 3h12l4 4v12a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z", "#42A5F5"),
            f("M7 3h8v6H7z", "#E3F2FD"),
            f("M7 14h10v7H7z", "#E3F2FD"),
            s("M12 4.5v3", "#1E88E5", 1.8f)
        ),
        "back" to listOf(
            s("M20 12H5", "#FFA726", 3f),
            s("M11 5l-7 7 7 7", "#FFA726", 3f)
        ),
        "theme" to listOf(
            f("M21 12.8A9 9 0 1 1 11.2 3 7 7 0 0 0 21 12.8z", "#FFCA28"),
            s("M17 2v4", "#FFCA28", 1.6f),
            s("M15 4h4", "#FFCA28", 1.6f)
        ),
        "selectall" to listOf(
            s("M2 13l4 4 8-9", "#66BB6A", 2.8f),
            s("M10 15l2 2 9-11", "#42A5F5", 2.8f)
        ),
        "selectnone" to listOf(
            f("M6 3h12a3 3 0 0 1 3 3v12a3 3 0 0 1 -3 3h-12a3 3 0 0 1 -3 -3v-12a3 3 0 0 1 3 -3z", "#EF5350"),
            s("M9 9l6 6", "#FFFFFF", 2.4f),
            s("M15 9l-6 6", "#FFFFFF", 2.4f)
        ),
        "addfile" to listOf(
            f("M6 2h8l6 6v12a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2z", "#64B5F6"),
            f("M14 2v6h6z", "#1E88E5"),
            f("M11.5 17a5 5 0 1 0 10 0a5 5 0 1 0 -10 0z", "#66BB6A"),
            s("M16.5 14.5v5", "#FFFFFF", 2f),
            s("M14 17h5", "#FFFFFF", 2f)
        )

        )
    }

    // Puts the named icon to the left of the button's text and keeps icon +
    // text together in the middle of the button (a full-width button would
    // otherwise leave the icon stranded at the far left edge).
    // An unknown name does nothing, so a typo can never crash the screen.
    fun apply(view: TextView, name: String, sizeDp: Int = 20, gapDp: Int = 8) {
        val layers = ICONS[name] ?: return
        val density = view.resources.displayMetrics.density
        val size = (sizeDp * density).toInt()
        val gap = (gapDp * density).toInt()

        val drawable = ColorIconDrawable(layers, size)
        view.setCompoundDrawablesRelativeWithIntrinsicBounds(drawable, null, null, null)
        view.compoundDrawablePadding = gap

        val baseLeft = view.paddingLeft
        val baseRight = view.paddingRight

        view.addOnLayoutChangeListener { v, left, _, right, _, _, _, _, _ ->
            val tv = v as TextView
            tv.post {
                val shown: CharSequence =
                    tv.transformationMethod?.getTransformation(tv.text, tv) ?: tv.text
                val textWidth = tv.paint.measureText(shown, 0, shown.length)
                val free = (right - left) - size - gap - textWidth
                val side = (free / 2f).toInt()

                val newLeft = maxOf(baseLeft, side)
                val newRight = maxOf(baseRight, side)

                if (tv.paddingLeft != newLeft || tv.paddingRight != newRight) {
                    tv.setPadding(newLeft, tv.paddingTop, newRight, tv.paddingBottom)
                }
            }
        }
    }

    // Draws the coloured shapes scaled from the 24x24 design box to the bounds.
    private class ColorIconDrawable(
        layers: List<Layer>,
        private val sizePx: Int
    ) : Drawable() {

        private class Shape(val path: Path, val layer: Layer)

        private val shapes: List<Shape> = layers.mapNotNull { layer ->
            try {
                Shape(PathParser.createPathFromPathData(layer.d), layer)
            } catch (e: Throwable) {
                null
            }
        }

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var iconAlpha = 255

        override fun draw(canvas: Canvas) {
            val b = bounds
            if (b.width() <= 0 || b.height() <= 0) return

            canvas.save()
            canvas.translate(b.left.toFloat(), b.top.toFloat())
            val scale = b.width() / 24f
            canvas.scale(scale, scale)

            for (shape in shapes) {
                val layer = shape.layer

                if (layer.fill != null) {
                    paint.style = Paint.Style.FILL
                    paint.color = layer.fill
                    paint.alpha = iconAlpha * Color.alpha(layer.fill) / 255
                    canvas.drawPath(shape.path, paint)
                }

                if (layer.stroke != null) {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = layer.width
                    paint.strokeCap = Paint.Cap.ROUND
                    paint.strokeJoin = Paint.Join.ROUND
                    paint.color = layer.stroke
                    paint.alpha = iconAlpha * Color.alpha(layer.stroke) / 255
                    canvas.drawPath(shape.path, paint)
                }
            }

            canvas.restore()
        }

        override fun getIntrinsicWidth(): Int = sizePx
        override fun getIntrinsicHeight(): Int = sizePx

        override fun setAlpha(alpha: Int) {
            iconAlpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
