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

/** BitFlow corporate-flat speed chart. */
class SpeedGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var downloadValues: List<Int> = emptyList()
    private var uploadValues: List<Int> = emptyList()

    private val downloadColor = Color.parseColor("#007BFF")
    private val uploadColor = Color.parseColor("#17A2B8")

    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E1E5EA")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#EEF1F4")
        strokeWidth = 2f
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

    private val downloadFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val uploadFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val panelRect = RectF()
    private val cornerRadius = 4f

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
        canvas.drawRoundRect(panelRect, cornerRadius, cornerRadius, borderPaint)

        for (i in 0..4) {
            val y = h * i / 4f
            canvas.drawLine(0f, y, w, y, gridPaint)
        }

        if (downloadValues.size < 2 && uploadValues.size < 2) return

        val maxValue = ((downloadValues + uploadValues).maxOrNull() ?: 0).coerceAtLeast(1)
        downloadFillPaint.shader = verticalFade(downloadColor, h)
        uploadFillPaint.shader = verticalFade(uploadColor, h)

        drawSeries(canvas, uploadValues, maxValue, w, h, uploadLinePaint, uploadFillPaint)
        drawSeries(canvas, downloadValues, maxValue, w, h, downloadLinePaint, downloadFillPaint)
    }

    private fun verticalFade(color: Int, h: Float): LinearGradient {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        return LinearGradient(
            0f, 0f, 0f, h,
            Color.argb(70, r, g, b),
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
