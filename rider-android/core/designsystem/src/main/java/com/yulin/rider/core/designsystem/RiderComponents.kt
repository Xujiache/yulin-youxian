package com.yulin.rider.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * 主行动按钮。高度 ≥ 64 dp(约 12 mm),并随系统字号同比放大。
 * 点击即震动:骑手不看屏幕也知道点到了。
 */
@Composable
fun BigActionButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    disabledReason: String? = null,
    tone: StatusTone = StatusTone.NORMAL,
    icon: FreshIconType? = null,
    onClick: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val solid = tone.solidColor()
    Button(
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onClick()
        },
        enabled = enabled,
        shape = RoundedCornerShape(RiderDimens.ControlCorner),
        colors = ButtonDefaults.buttonColors(
            containerColor = solid,
            contentColor = Color.White,
            disabledContainerColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.18f),
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.60f),
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = riderControlHeight(RiderDimens.BigButtonHeight))
            .semantics {
                role = Role.Button
                stateDescription = if (enabled) "可用" else disabledReason ?: "不可用"
                if (!enabled) disabled()
            },
    ) {
        if (icon != null) {
            FreshIcon(type = icon, contentDescription = null, tint = Color.White)
            Spacer(Modifier.width(FreshSpacing.Xs))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 状态标签。色彩之外附一个圆点并始终带文字,不让色弱骑手只能靠颜色判断(06 §3.5 色彩语义)。
 */
@Composable
fun StatusChip(
    text: String,
    tone: StatusTone = StatusTone.NORMAL,
    modifier: Modifier = Modifier,
) {
    FreshStatusBadge(text = text, tone = tone, modifier = modifier)
}

/** 空态。 */
@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier) {
    FreshEmpty(title = text, modifier = modifier)
}

/** 加载中。 */
@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    FreshLoading(modifier = modifier)
}

/** 错误重试。重试按钮 ≥ 48 dp,断网是常态,这个按钮会被按很多次。 */
@Composable
fun ErrorRetry(
    message: String,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit,
) {
    FreshError(message = message, modifier = modifier, onRetry = onRetry)
}

/** 分区卡片。任务信息层级靠卡片切分(06 §3.5 任务卡片信息层级)。 */
@Composable
fun SectionCard(
    title: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    FreshPanel(title = title, modifier = modifier, content = content)
}

@Preview(name = "BigActionButton", showBackground = true)
@Composable
private fun BigActionButtonPreview() {
    RiderTheme {
        BigActionButton(
            text = "开始配送",
            icon = FreshIconType.ROUTE,
            modifier = Modifier.padding(FreshSpacing.Md),
            onClick = {},
        )
    }
}
