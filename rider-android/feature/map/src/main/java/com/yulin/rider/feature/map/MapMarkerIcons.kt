package com.yulin.rider.feature.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.DisplayMetrics

/**
 * 用 Canvas 现画标注图标,不依赖任何图片资源。
 *
 * 这样做有两个实际好处:编号气泡要随 seqNo 变化,做成静态图就得准备一整套;
 * 而且骑手端目前没有设计交付的图标资源,现画能让地图立刻可用。
 */
internal object MapMarkerIcons {

    /** 待送点:带序号的圆形气泡 + 下方尖角。 */
    fun numberedPin(metrics: DisplayMetrics, seqNo: Int, fillColor: Int): Bitmap {
        val size = dp(metrics, BUBBLE_DP)
        val tail = dp(metrics, TAIL_DP)
        val bitmap = Bitmap.createBitmap(size, size + tail, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val radius = size / 2f

        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fillColor }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = dp(metrics, 2).toFloat()
        }

        canvas.drawPath(
            Path().apply {
                moveTo(radius - tail * 0.45f, size * 0.86f)
                lineTo(radius, (size + tail).toFloat())
                lineTo(radius + tail * 0.45f, size * 0.86f)
                close()
            },
            fill,
        )
        canvas.drawCircle(radius, radius, radius - stroke.strokeWidth, fill)
        canvas.drawCircle(radius, radius, radius - stroke.strokeWidth, stroke)

        val label = if (seqNo in 1..99) seqNo.toString() else "·"
        drawCenteredText(canvas, label, radius, radius, size * TEXT_RATIO, Color.WHITE)
        return bitmap
    }

    /** 门店标记复用同一套气泡。 */
    fun labeledPin(metrics: DisplayMetrics, label: String, fillColor: Int): Bitmap {
        val size = dp(metrics, BUBBLE_DP)
        val tail = dp(metrics, TAIL_DP)
        val bitmap = Bitmap.createBitmap(size, size + tail, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val radius = size / 2f

        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fillColor }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = dp(metrics, 2).toFloat()
        }

        canvas.drawPath(
            Path().apply {
                moveTo(radius - tail * 0.45f, size * 0.86f)
                lineTo(radius, (size + tail).toFloat())
                lineTo(radius + tail * 0.45f, size * 0.86f)
                close()
            },
            fill,
        )
        canvas.drawCircle(radius, radius, radius - stroke.strokeWidth, fill)
        canvas.drawCircle(radius, radius, radius - stroke.strokeWidth, stroke)
        drawCenteredText(canvas, label, radius, radius, size * TEXT_RATIO, Color.WHITE)
        return bitmap
    }

    /** 已送达点用 Canvas 对勾，不依赖系统字符或 emoji 字形。 */
    fun checkedPin(metrics: DisplayMetrics, fillColor: Int): Bitmap {
        val size = dp(metrics, BUBBLE_DP)
        val tail = dp(metrics, TAIL_DP)
        val bitmap = Bitmap.createBitmap(size, size + tail, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val radius = size / 2f
        val borderWidth = dp(metrics, 2).toFloat()
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fillColor }
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = borderWidth
        }
        canvas.drawPath(
            Path().apply {
                moveTo(radius - tail * 0.45f, size * 0.86f)
                lineTo(radius, (size + tail).toFloat())
                lineTo(radius + tail * 0.45f, size * 0.86f)
                close()
            },
            fill,
        )
        canvas.drawCircle(radius, radius, radius - borderWidth, fill)
        canvas.drawCircle(radius, radius, radius - borderWidth, border)

        val check = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = size * 0.11f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        canvas.drawPath(
            Path().apply {
                moveTo(size * 0.28f, size * 0.51f)
                lineTo(size * 0.43f, size * 0.66f)
                lineTo(size * 0.73f, size * 0.34f)
            },
            check,
        )
        return bitmap
    }

    /** 骑手自身:方向箭头。配合 Marker.setFlat(true) + setRotateAngle(bearing) 贴地旋转。 */
    fun riderArrow(metrics: DisplayMetrics, fillColor: Int): Bitmap {
        val size = dp(metrics, ARROW_DP)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val center = size / 2f

        canvas.drawCircle(
            center,
            center,
            center * 0.94f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(56, 0, 0, 0) },
        )
        canvas.drawCircle(
            center,
            center,
            center * 0.86f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE },
        )
        canvas.drawPath(
            Path().apply {
                moveTo(center, size * 0.16f)
                lineTo(size * 0.80f, size * 0.84f)
                lineTo(center, size * 0.66f)
                lineTo(size * 0.20f, size * 0.84f)
                close()
            },
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fillColor },
        )
        return bitmap
    }

    private fun drawCenteredText(
        canvas: Canvas,
        text: String,
        cx: Float,
        cy: Float,
        textSize: Float,
        textColor: Int,
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor
            this.textSize = textSize
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }
        val baseline = cy - (paint.descent() + paint.ascent()) / 2
        canvas.drawText(text, cx, baseline, paint)
    }

    private fun dp(metrics: DisplayMetrics, value: Int): Int =
        (value * metrics.density).toInt().coerceAtLeast(1)

    private const val BUBBLE_DP = 34
    private const val TAIL_DP = 10
    private const val ARROW_DP = 30
    private const val TEXT_RATIO = 0.46f
}
