package com.example.torrentor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

// A small, modern line-area chart for the main screen's download/upload
// speed history - replaces the old block-character sparkline (the
// "▁▂▃▄▅▆▇█" text that used to sit inside speedGraphText) with a real
// drawn graph: a rounded dark panel, a light grid, and two smooth gradient-
// filled lines (download/upload). Pure Canvas drawing, no chart library
// and no XML, consistent with the rest of this app's fully-programmatic UI.
//
// Usage: add to a layout like any other View (give it a fixed height - it
// has no intrinsic content size), then call setData(download, upload)
// every time MainActivity's addSpeedSample() gets a new sample. Both lists
// are read left-to-right as oldest-to-newest, same order as the history
// lists MainActivity already keeps.
class SpeedGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var downloadValues: List<Int> = emptyList()
    private var uploadValues: List<Int> = emptyList()

    private val downloadColor = Color.parseColor("#8BC34A") // lime green
    private val uploadColor = Color.parseColor("#42A5F5")   // blue

    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2D2D2D") // qBittorrent graph background
        style = Paint.Style.FILL
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#585858")
        strokeWidth = 2f
        style = Paint.Style.STROKE
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f, 8f), 0f)
    }

    private val downloadLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = downloadColor
        style = Paint.Style.STROKE
        strokeWidth = 5f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private val uploadLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = uploadColor
        style = Paint.Style.STROKE
        strokeWidth = 5f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private val downloadFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val uploadFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val panelRect = RectF()
    private val cornerRadius = 24f

    fun setData(download: List<Int>, upload: List<Int>) {
        downloadValues = download
        uploadValues = upload
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        panelRect.set(0f, 0f, w, h)
        canvas.drawRoundRect(panelRect, cornerRadius, cornerRadius, panelPaint)

        // qBittorrent-style dashed grid: 4 horizontal lines and 7 vertical
        // "columns", all in the same light grey dashes.
        for (i in 0..3) {
            val y = h * (0.06f + 0.235f * i)
            canvas.drawLine(0f, y, w, y, gridPaint)
        }
        for (i in 0..6) {
            val x = w * (0.068f + 0.155f * i)
            canvas.drawLine(x, 0f, x, h, gridPaint)
        }

        if (downloadValues.size < 2 && uploadValues.size < 2) {
            return
        }

        val maxValue = ((downloadValues + uploadValues).maxOrNull() ?: 0).coerceAtLeast(1)

        downloadFillPaint.shader = verticalFade(downloadColor, h)
        uploadFillPaint.shader = verticalFade(uploadColor, h)

        // Upload drawn first so the (usually larger) download line/fill
        // sits on top, matching which number people care about most.
        drawSeries(canvas, uploadValues, maxValue, w, h, uploadLinePaint, uploadFillPaint)
        drawSeries(canvas, downloadValues, maxValue, w, h, downloadLinePaint, downloadFillPaint)
    }

    private fun verticalFade(color: Int, h: Float): LinearGradient {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)

        return LinearGradient(
            0f, 0f, 0f, h,
            Color.argb(130, r, g, b),
            Color.argb(0, r, g, b),
            Shader.TileMode.CLAMP
        )
    }

    private fun drawSeries(
        canvas: Canvas,
        values: List<Int>,
        maxValue: Int,
        w: Float,
        h: Float,
        linePaint: Paint,
        fillPaint: Paint
    ) {
        if (values.size < 2) return

        // Leave a little headroom at the top so a peak never touches the
        // panel's rounded corner.
        val topPadding = h * 0.12f
        val usableHeight = h - topPadding
        val stepX = w / (values.size - 1).coerceAtLeast(1)

        fun yFor(value: Int): Float {
            val ratio = value.toFloat() / maxValue.toFloat()
            return h - (ratio * usableHeight)
        }

        val linePath = Path()
        val fillPath = Path()

        values.forEachIndexed { index, value ->
            val x = index * stepX
            val y = yFor(value)

            if (index == 0) {
                linePath.moveTo(x, y)
                fillPath.moveTo(x, h)
                fillPath.lineTo(x, y)
            } else {
                linePath.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }

        fillPath.lineTo((values.size - 1) * stepX, h)
        fillPath.close()

        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(linePath, linePaint)
    }
}
