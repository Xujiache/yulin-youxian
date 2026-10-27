package com.yulin.rider.core.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import kotlinx.coroutines.delay
import kotlin.math.abs

private const val MILLIS_PER_SECOND = 1000L
private const val WARNING_SECONDS = 10 * 60L
private const val DANGER_SECONDS = 3 * 60L

/**
 * 送达倒计时。超时后继续往上走并换红色 —— 骑手需要知道超了多久,而不是停在 00:00。
 * 颜色随剩余时间在正常/橙/红之间切换,对应 06 §3.5 的风险色。
 */
@Composable
fun CountdownText(
    targetEpochMillis: Long,
    modifier: Modifier = Modifier,
    prefix: String = "",
    overtimePrefix: String = "已超时 ",
) {
    val now by produceState(initialValue = System.currentTimeMillis(), targetEpochMillis) {
        while (true) {
            val current = System.currentTimeMillis()
            value = current
            delay(MILLIS_PER_SECOND - current % MILLIS_PER_SECOND)
        }
    }

    val remainingSeconds = (targetEpochMillis - now) / MILLIS_PER_SECOND
    val overtime = remainingSeconds < 0
    val label = (if (overtime) overtimePrefix else prefix) + formatDuration(abs(remainingSeconds))

    val color = when {
        overtime || remainingSeconds <= DANGER_SECONDS -> RiderColors.Danger
        remainingSeconds <= WARNING_SECONDS -> RiderColors.Warning
        else -> MaterialTheme.colorScheme.onSurface
    }

    Text(
        text = label,
        style = MaterialTheme.typography.titleMedium,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.semantics {
            contentDescription = if (overtime) "已超时，${formatDuration(abs(remainingSeconds))}" else
                "剩余，${formatDuration(abs(remainingSeconds))}"
        },
    )
}

/** 一小时以内用 mm:ss,超过则补出小时位。 */
internal fun formatDuration(totalSeconds: Long): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

@Preview(name = "CountdownText", showBackground = true)
@Composable
private fun CountdownTextPreview() {
    RiderTheme {
        CountdownText(targetEpochMillis = System.currentTimeMillis() + 12 * 60 * 1000)
    }
}
