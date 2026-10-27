package com.yulin.rider.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.yulin.rider.core.designsystem.FreshIcon
import com.yulin.rider.core.designsystem.FreshIconType
import com.yulin.rider.core.designsystem.FreshRadius
import com.yulin.rider.core.designsystem.FreshSpacing
import com.yulin.rider.core.designsystem.MtDivider
import com.yulin.rider.core.designsystem.RiderColors
import com.yulin.rider.core.designsystem.RiderDimens

internal data class RiderDrawerEntry(
    val route: String,
    val icon: FreshIconType,
    val label: String,
)

/**
 * 抽屉入口。
 *
 * 美团骑手端主界面没有底部 Tab，所有非作业页面都挂在左上角唤出的抽屉里，
 * 主界面因此能把整屏留给任务列表。这里沿用同一套结构。
 */
internal val RiderDrawerEntries = listOf(
    RiderDrawerEntry(RiderRoutes.EARNING, FreshIconType.WALLET, "我的账户"),
    RiderDrawerEntry(RiderRoutes.MESSAGE, FreshIconType.MESSAGE, "消息中心"),
    RiderDrawerEntry(RiderRoutes.MAP, FreshIconType.ROUTE, "配送路线"),
    RiderDrawerEntry(RiderRoutes.SETTINGS, FreshIconType.SETTINGS, "设置"),
    RiderDrawerEntry(RiderRoutes.KEEPALIVE_GUIDE, FreshIconType.BATTERY, "后台运行"),
    RiderDrawerEntry(RiderRoutes.ABOUT, FreshIconType.ABOUT, "关于"),
)

/**
 * 侧边抽屉内容。顶部是骑手身份区，点进个人主页；下面是功能入口列表。
 */
@Composable
internal fun RiderDrawerSheet(
    riderName: String,
    riderNo: String?,
    onOpenProfile: () -> Unit,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxHeight()
            .width(300.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState()),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenProfile)
                    .semantics {
                        contentDescription = "查看个人主页"
                        role = Role.Button
                    }
                    .padding(FreshSpacing.Md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FreshSpacing.Sm),
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(RiderColors.PrimaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    FreshIcon(
                        FreshIconType.PROFILE,
                        contentDescription = null,
                        tint = RiderColors.Ink,
                        size = 26.dp,
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        text = riderName,
                        style = MaterialTheme.typography.titleMedium,
                        color = RiderColors.Ink,
                    )
                    if (!riderNo.isNullOrBlank()) {
                        Text(
                            text = riderNo,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                FreshIcon(
                    FreshIconType.CHEVRON_RIGHT,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    size = 18.dp,
                )
            }

            MtDivider()
            Spacer(Modifier.height(FreshSpacing.Xs))

            RiderDrawerEntries.forEach { entry ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(FreshRadius.Control))
                        .clickable { onNavigate(entry.route) }
                        .semantics { role = Role.Button }
                        .padding(horizontal = FreshSpacing.Md, vertical = FreshSpacing.Sm)
                        .height(RiderDimens.TouchTarget),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(FreshSpacing.Sm),
                ) {
                    FreshIcon(
                        entry.icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        size = 20.dp,
                    )
                    Text(
                        text = entry.label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = RiderColors.Ink,
                        modifier = Modifier.weight(1f),
                    )
                    FreshIcon(
                        FreshIconType.CHEVRON_RIGHT,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        size = 16.dp,
                    )
                }
            }
        }
    }
}
