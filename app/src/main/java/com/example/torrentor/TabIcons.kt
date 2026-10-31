package com.example.torrentor

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.widget.TextView
import androidx.core.graphics.PathParser

// Flat, single-colour line icons for buttons and tabs.
//
// Every icon is a handful of SVG path strings drawn in a 24x24 box with a
// 2-unit round stroke (Feather-style). The same shapes are used in the Web UI
// (see WebUiServer.kt), so the app and the browser look alike.
//
// Usage:  TabIcons.apply(someButton, "rss")
// The icon takes the button's own text colour (including the dimmed colour of
// a disabled button), so it follows the light/dark theme automatically.
object TabIcons {

    private val ICONS: Map<String, List<String>> = mapOf(
        // ---- torrent detail tabs
        "general" to listOf(
            "M12 2a10 10 0 1 0 0 20a10 10 0 1 0 0-20Z",
            "M12 16v-4",
            "M12 8h.01"
        ),
        "files" to listOf(
            "M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z",
            "M14 2v6h6",
            "M16 13H8",
            "M16 17H8",
            "M10 9H8"
        ),
        "priority" to listOf(
            "M4 21v-7", "M4 10V3", "M12 21v-9", "M12 8V3", "M20 21v-5", "M20 12V3",
            "M1 14h6", "M9 8h6", "M17 16h6"
        ),
        "peers" to listOf(
            "M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2",
            "M9 3a4 4 0 1 0 0 8a4 4 0 1 0 0-8Z",
            "M23 21v-2a4 4 0 0 0-3-3.87",
            "M16 3.13a4 4 0 0 1 0 7.75"
        ),
        "trackers" to listOf(
            "M12 2a10 10 0 1 0 0 20a10 10 0 1 0 0-20Z",
            "M12 6a6 6 0 1 0 0 12a6 6 0 1 0 0-12Z",
            "M12 10a2 2 0 1 0 0 4a2 2 0 1 0 0-4Z"
        ),
        "webseeds" to listOf(
            "M12 2a10 10 0 1 0 0 20a10 10 0 1 0 0-20Z",
            "M2 12h20",
            "M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z"
        ),
        "swarm" to listOf(
            "M22 12h-4l-3 9L9 3l-3 9H2"
        ),
        "pieces" to listOf(
            "M3 3h7v7H3z", "M14 3h7v7h-7z", "M14 14h7v7h-7z", "M3 14h7v7H3z"
        ),
        "statistics" to listOf(
            "M18 20V10", "M12 20V4", "M6 20v-6"
        ),
        "comment" to listOf(
            "M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"
        ),

        // ---- main screen / Web UI feature buttons
        "pause" to listOf(
            "M6 4h4v16H6z", "M14 4h4v16h-4z"
        ),
        "play" to listOf(
            "M5 3l14 9-14 9z"
        ),
        "select" to listOf(
            "M9 11l3 3L22 4",
            "M21 12v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11"
        ),
        "trash" to listOf(
            "M3 6h18",
            "M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"
        ),
        "portfwd" to listOf(
            "M12 2a10 10 0 1 0 0 20a10 10 0 1 0 0-20Z",
            "M12 16l4-4-4-4",
            "M8 12h8"
        ),
        "network" to listOf(
            "M5 12.55a11 11 0 0 1 14.08 0",
            "M1.42 9a16 16 0 0 1 21.16 0",
            "M8.53 16.11a6 6 0 0 1 6.95 0",
            "M12 20h.01"
        ),
        "connection" to listOf(
            "M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71",
            "M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71"
        ),
        "speed" to listOf(
            "M13 2L3 14h9l-1 8 10-12h-9l1-8z"
        ),
        "storage" to listOf(
            "M22 12H2",
            "M5.45 5.11L2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.45-6.89A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z",
            "M6 16h.01",
            "M10 16h.01"
        ),
        "folder" to listOf(
            "M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"
        ),
        "rss" to listOf(
            "M4 11a9 9 0 0 1 9 9",
            "M4 4a16 16 0 0 1 16 16",
            "M5 18a1 1 0 1 0 0 2a1 1 0 1 0 0-2Z"
        ),
        "log" to listOf(
            "M4 17l6-6-6-6",
            "M12 19h8"
        ),
        "search" to listOf(
            "M11 3a8 8 0 1 0 0 16a8 8 0 1 0 0-16Z",
            "M21 21l-4.35-4.35"
        ),
        "webui" to listOf(
            "M2 3h20v14H2z",
            "M8 21h8",
            "M12 17v4"
        ),
        "magnet" to listOf(
            "M6 15a6 6 0 0 0 12 0V3h-4v12a2 2 0 0 1-4 0V3H6z",
            "M6 8h4",
            "M14 8h4"
        ),
        "recheck" to listOf(
            "M23 4v6h-6",
            "M1 20v-6h6",
            "M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15"
        ),
        "reannounce" to listOf(
            "M22 2L11 13",
            "M22 2l-7 20-4-9-9-4z"
        ),
        "addfile" to listOf(
            "M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z",
            "M14 2v6h6",
            "M12 18v-6",
            "M9 15h6"
        )
    )

    // Puts the named icon to the left of the button's text and keeps icon +
    // text together in the middle of the button (a full-width button would
    // otherwise leave the icon stranded at the far left edge).
    // An unknown name does nothing, so a typo can never crash the screen.
    fun apply(view: TextView, name: String, sizeDp: Int = 20, gapDp: Int = 8) {
        val parts = ICONS[name] ?: return
        val density = view.resources.displayMetrics.density
        val size = (sizeDp * density).toInt()
        val gap = (gapDp * density).toInt()

        val drawable = PathIconDrawable(parts, size) { view.currentTextColor }
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

    // Draws the stroke paths scaled from the 24x24 design box to the bounds.
    private class PathIconDrawable(
        pathData: List<String>,
        private val sizePx: Int,
        private val colorProvider: () -> Int
    ) : Drawable() {

        private val paths: List<Path> = pathData.mapNotNull { data ->
            try {
                PathParser.createPathFromPathData(data)
            } catch (e: Throwable) {
                null
            }
        }

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            if (b.width() <= 0 || b.height() <= 0) return

            paint.color = colorProvider()

            canvas.save()
            canvas.translate(b.left.toFloat(), b.top.toFloat())
            val scale = b.width() / 24f
            canvas.scale(scale, scale)
            for (p in paths) {
                canvas.drawPath(p, paint)
            }
            canvas.restore()
        }

        override fun getIntrinsicWidth(): Int = sizePx
        override fun getIntrinsicHeight(): Int = sizePx

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
