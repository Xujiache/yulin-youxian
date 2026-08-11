package com.yulin.rider.core.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class FreshIconType {
    BACK,
    CHEVRON_RIGHT,
    CHECK,
    CLOSE,
    WARNING,
    ERROR,
    INFO,
    OFFLINE,
    COLD,
    CAMERA,
    PHONE,
    MAP,
    ROUTE,
    TASK,
    PICKUP,
    DELIVERY,
    MESSAGE,
    PROFILE,
    STATS,
    SETTINGS,
    ABOUT,
    BATTERY,
    LOCK,
    LOCATION,
    STORE,
    RIDER,
    PACKAGE,
    CLOCK,
    REFRESH,
    HOME,
}

/**
 * 不依赖 emoji 字形或图标字体的 Canvas 图标。
 *
 * [contentDescription] 为空时作为装饰图；操作入口必须传入可朗读的中文动作名。
 */
@Composable
fun FreshIcon(
    type: FreshIconType,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
    size: Dp = 24.dp,
) {
    val resolvedTint = if (tint == Color.Unspecified) LocalContentColor.current else tint
    Canvas(
        modifier = modifier
            .size(size)
            .then(
                if (contentDescription != null) {
                    Modifier.semantics { this.contentDescription = contentDescription }
                } else {
                    Modifier
                }
            ),
    ) {
        drawFreshIcon(type, resolvedTint)
    }
}

private fun DrawScope.drawFreshIcon(type: FreshIconType, color: Color) {
    val w = size.width
    val h = size.height
    val s = size.minDimension
    val stroke = (s * 0.085f).coerceAtLeast(1.5f)
    val outline = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)

    fun line(x1: Float, y1: Float, x2: Float, y2: Float) {
        drawLine(
            color = color,
            start = Offset(w * x1, h * y1),
            end = Offset(w * x2, h * y2),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }

    fun circle(cx: Float, cy: Float, radius: Float, fill: Boolean = false) {
        drawCircle(
            color = color,
            radius = s * radius,
            center = Offset(w * cx, h * cy),
            style = if (fill) androidx.compose.ui.graphics.drawscope.Fill else outline,
        )
    }

    when (type) {
        FreshIconType.BACK -> {
            line(.72f, .18f, .34f, .50f)
            line(.34f, .50f, .72f, .82f)
        }

        FreshIconType.CHEVRON_RIGHT -> {
            line(.34f, .18f, .68f, .50f)
            line(.68f, .50f, .34f, .82f)
        }

        FreshIconType.CHECK -> {
            line(.14f, .54f, .39f, .79f)
            line(.39f, .79f, .87f, .23f)
        }

        FreshIconType.CLOSE -> {
            line(.22f, .22f, .78f, .78f)
            line(.78f, .22f, .22f, .78f)
        }

        FreshIconType.WARNING -> {
            val path = Path().apply {
                moveTo(w * .50f, h * .10f)
                lineTo(w * .91f, h * .84f)
                lineTo(w * .09f, h * .84f)
                close()
            }
            drawPath(path, color, style = outline)
            line(.50f, .34f, .50f, .59f)
            circle(.50f, .72f, .035f, fill = true)
        }

        FreshIconType.ERROR -> {
            circle(.50f, .50f, .40f)
            line(.35f, .35f, .65f, .65f)
            line(.65f, .35f, .35f, .65f)
        }

        FreshIconType.INFO, FreshIconType.ABOUT -> {
            circle(.50f, .50f, .40f)
            line(.50f, .44f, .50f, .72f)
            circle(.50f, .29f, .04f, fill = true)
        }

        FreshIconType.OFFLINE -> {
            val cloud = Path().apply {
                moveTo(w * .19f, h * .65f)
                cubicTo(w * .07f, h * .54f, w * .15f, h * .36f, w * .31f, h * .38f)
                cubicTo(w * .38f, h * .17f, w * .68f, h * .17f, w * .75f, h * .41f)
                cubicTo(w * .95f, h * .42f, w * .96f, h * .69f, w * .78f, h * .72f)
                lineTo(w * .33f, h * .72f)
            }
            drawPath(cloud, color, style = outline)
            line(.16f, .16f, .84f, .84f)
        }

        FreshIconType.COLD -> {
            line(.50f, .10f, .50f, .90f)
            line(.15f, .30f, .85f, .70f)
            line(.15f, .70f, .85f, .30f)
            line(.50f, .10f, .42f, .22f)
            line(.50f, .10f, .58f, .22f)
            line(.50f, .90f, .42f, .78f)
            line(.50f, .90f, .58f, .78f)
        }

        FreshIconType.CAMERA -> {
            drawRoundRect(
                color = color,
                topLeft = Offset(w * .08f, h * .27f),
                size = Size(w * .84f, h * .59f),
                cornerRadius = CornerRadius(s * .10f),
                style = outline,
            )
            val hump = Path().apply {
                moveTo(w * .29f, h * .27f)
                lineTo(w * .38f, h * .14f)
                lineTo(w * .62f, h * .14f)
                lineTo(w * .71f, h * .27f)
            }
            drawPath(hump, color, style = outline)
            circle(.50f, .56f, .18f)
        }

        FreshIconType.PHONE -> {
            val phone = Path().apply {
                moveTo(w * .24f, h * .13f)
                cubicTo(w * .12f, h * .18f, w * .17f, h * .47f, w * .43f, h * .72f)
                cubicTo(w * .68f, h * .96f, w * .89f, h * .88f, w * .88f, h * .75f)
                lineTo(w * .72f, h * .60f)
                cubicTo(w * .67f, h * .56f, w * .59f, h * .68f, w * .51f, h * .62f)
                lineTo(w * .38f, h * .49f)
                cubicTo(w * .31f, h * .41f, w * .43f, h * .34f, w * .38f, h * .28f)
                close()
            }
            drawPath(phone, color, style = outline)
        }

        FreshIconType.MAP, FreshIconType.LOCATION -> {
            val pin = Path().apply {
                moveTo(w * .50f, h * .91f)
                cubicTo(w * .40f, h * .77f, w * .18f, h * .57f, w * .18f, h * .37f)
                cubicTo(w * .18f, h * .12f, w * .39f, h * .05f, w * .50f, h * .05f)
                cubicTo(w * .71f, h * .05f, w * .82f, h * .20f, w * .82f, h * .37f)
                cubicTo(w * .82f, h * .57f, w * .60f, h * .77f, w * .50f, h * .91f)
                close()
            }
            drawPath(pin, color, style = outline)
            circle(.50f, .36f, .12f)
        }

        FreshIconType.ROUTE -> {
            circle(.22f, .76f, .10f)
            circle(.78f, .22f, .10f)
            val route = Path().apply {
                moveTo(w * .31f, h * .73f)
                cubicTo(w * .61f, h * .72f, w * .31f, h * .27f, w * .68f, h * .25f)
            }
            drawPath(route, color, style = outline)
        }

        FreshIconType.TASK -> {
            drawRoundRect(
                color = color,
                topLeft = Offset(w * .18f, h * .14f),
                size = Size(w * .64f, h * .76f),
                cornerRadius = CornerRadius(s * .08f),
                style = outline,
            )
            line(.36f, .14f, .36f, .08f)
            line(.64f, .14f, .64f, .08f)
            line(.32f, .38f, .42f, .48f)
            line(.42f, .48f, .58f, .30f)
            line(.32f, .68f, .68f, .68f)
        }

        FreshIconType.PICKUP, FreshIconType.PACKAGE, FreshIconType.DELIVERY -> {
            val box = Path().apply {
                moveTo(w * .14f, h * .31f)
                lineTo(w * .50f, h * .12f)
                lineTo(w * .86f, h * .31f)
                lineTo(w * .86f, h * .72f)
                lineTo(w * .50f, h * .91f)
                lineTo(w * .14f, h * .72f)
                close()
            }
            drawPath(box, color, style = outline)
            line(.14f, .31f, .50f, .50f)
            line(.86f, .31f, .50f, .50f)
            line(.50f, .50f, .50f, .91f)
            if (type == FreshIconType.PICKUP) {
                line(.67f, .74f, .67f, .51f)
                line(.59f, .59f, .67f, .51f)
                line(.67f, .51f, .75f, .59f)
            } else if (type == FreshIconType.DELIVERY) {
                line(.57f, .68f, .65f, .76f)
                line(.65f, .76f, .80f, .58f)
            }
        }

        FreshIconType.MESSAGE -> {
            val bubble = Path().apply {
                moveTo(w * .13f, h * .18f)
                lineTo(w * .87f, h * .18f)
                lineTo(w * .87f, h * .69f)
                lineTo(w * .53f, h * .69f)
                lineTo(w * .32f, h * .87f)
                lineTo(w * .34f, h * .69f)
                lineTo(w * .13f, h * .69f)
                close()
            }
            drawPath(bubble, color, style = outline)
            line(.31f, .38f, .69f, .38f)
            line(.31f, .52f, .58f, .52f)
        }

        FreshIconType.PROFILE -> {
            circle(.50f, .32f, .19f)
            drawArc(
                color = color,
                startAngle = 200f,
                sweepAngle = 140f,
                useCenter = false,
                topLeft = Offset(w * .15f, h * .51f),
                size = Size(w * .70f, h * .55f),
                style = outline,
            )
        }

        FreshIconType.STATS -> {
            drawRoundRect(
                color,
                Offset(w * .13f, h * .55f),
                Size(w * .16f, h * .31f),
                CornerRadius(s * .04f),
                style = outline,
            )
            drawRoundRect(
                color,
                Offset(w * .42f, h * .34f),
                Size(w * .16f, h * .52f),
                CornerRadius(s * .04f),
                style = outline,
            )
            drawRoundRect(
                color,
                Offset(w * .71f, h * .13f),
                Size(w * .16f, h * .73f),
                CornerRadius(s * .04f),
                style = outline,
            )
        }

        FreshIconType.SETTINGS -> {
            circle(.50f, .50f, .19f)
            repeat(8) { index ->
                val angle = Math.toRadians((index * 45.0))
                val inner = Offset(
                    w * .5f + kotlin.math.cos(angle).toFloat() * s * .30f,
                    h * .5f + kotlin.math.sin(angle).toFloat() * s * .30f,
                )
                val outer = Offset(
                    w * .5f + kotlin.math.cos(angle).toFloat() * s * .42f,
                    h * .5f + kotlin.math.sin(angle).toFloat() * s * .42f,
                )
                drawLine(color, inner, outer, stroke, StrokeCap.Round)
            }
        }

        FreshIconType.BATTERY -> {
            drawRoundRect(
                color,
                Offset(w * .08f, h * .25f),
                Size(w * .77f, h * .50f),
                CornerRadius(s * .07f),
                style = outline,
            )
            line(.91f, .40f, .91f, .60f)
            val bolt = Path().apply {
                moveTo(w * .53f, h * .31f)
                lineTo(w * .35f, h * .55f)
                lineTo(w * .50f, h * .55f)
                lineTo(w * .43f, h * .70f)
                lineTo(w * .66f, h * .45f)
                lineTo(w * .51f, h * .45f)
                close()
            }
            drawPath(bolt, color)
        }

        FreshIconType.LOCK -> {
            drawRoundRect(
                color,
                Offset(w * .18f, h * .42f),
                Size(w * .64f, h * .47f),
                CornerRadius(s * .08f),
                style = outline,
            )
            drawArc(
                color,
                180f,
                180f,
                false,
                Offset(w * .30f, h * .10f),
                Size(w * .40f, h * .56f),
                style = outline,
            )
            line(.50f, .60f, .50f, .72f)
        }

        FreshIconType.STORE -> {
            drawRect(
                color = color,
                topLeft = Offset(w * .17f, h * .42f),
                size = Size(w * .66f, h * .45f),
                style = outline,
            )
            val awning = Path().apply {
                moveTo(w * .10f, h * .39f)
                lineTo(w * .19f, h * .15f)
                lineTo(w * .81f, h * .15f)
                lineTo(w * .90f, h * .39f)
            }
            drawPath(awning, color, style = outline)
            line(.32f, .16f, .29f, .39f)
            line(.50f, .16f, .50f, .39f)
            line(.68f, .16f, .71f, .39f)
            line(.43f, .87f, .43f, .61f)
            line(.43f, .61f, .66f, .61f)
            line(.66f, .61f, .66f, .87f)
        }

        FreshIconType.RIDER -> {
            val arrow = Path().apply {
                moveTo(w * .50f, h * .07f)
                lineTo(w * .84f, h * .87f)
                lineTo(w * .50f, h * .69f)
                lineTo(w * .16f, h * .87f)
                close()
            }
            drawPath(arrow, color, style = outline)
        }

        FreshIconType.CLOCK -> {
            circle(.50f, .50f, .40f)
            line(.50f, .27f, .50f, .52f)
            line(.50f, .52f, .67f, .63f)
        }

        FreshIconType.REFRESH -> {
            drawArc(
                color,
                startAngle = 205f,
                sweepAngle = 270f,
                useCenter = false,
                topLeft = Offset(w * .14f, h * .14f),
                size = Size(w * .72f, h * .72f),
                style = outline,
            )
            line(.16f, .22f, .16f, .45f)
            line(.16f, .22f, .39f, .22f)
        }

        FreshIconType.HOME -> {
            val home = Path().apply {
                moveTo(w * .10f, h * .45f)
                lineTo(w * .50f, h * .12f)
                lineTo(w * .90f, h * .45f)
                moveTo(w * .20f, h * .39f)
                lineTo(w * .20f, h * .88f)
                lineTo(w * .80f, h * .88f)
                lineTo(w * .80f, h * .39f)
                moveTo(w * .42f, h * .88f)
                lineTo(w * .42f, h * .62f)
                lineTo(w * .61f, h * .62f)
                lineTo(w * .61f, h * .88f)
            }
            drawPath(home, color, style = outline)
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun FreshIconPreview() {
    RiderTheme {
        Column(
            modifier = Modifier.padding(FreshSpacing.Md),
            verticalArrangement = Arrangement.spacedBy(FreshSpacing.Md),
        ) {
            FreshIconType.entries.chunked(6).forEach { icons ->
                Row(horizontalArrangement = Arrangement.spacedBy(FreshSpacing.Md)) {
                    icons.forEach { FreshIcon(it, contentDescription = it.name) }
                }
            }
        }
    }
}
