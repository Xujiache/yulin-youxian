package com.yulin.rider.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yulin.rider.core.datastore.RiderSettingsStore
import com.yulin.rider.core.datastore.RiderTokenStore
import com.yulin.rider.core.designsystem.FreshIcon
import com.yulin.rider.core.designsystem.FreshIconType
import com.yulin.rider.core.designsystem.FreshRadius
import com.yulin.rider.core.designsystem.FreshSpacing
import com.yulin.rider.core.designsystem.MtAction
import com.yulin.rider.core.designsystem.MtPrimaryButton
import com.yulin.rider.core.designsystem.RiderColors
import com.yulin.rider.core.update.UpdateController
import kotlinx.coroutines.withTimeoutOrNull

/** 启动后应当落到哪一屏。顺序即业务前置条件的顺序,不能调换。 */
enum class StartDestination(val route: String) {
    LOGIN(RiderRoutes.LOGIN),
    CHANGE_PASSWORD(RiderRoutes.CHANGE_PASSWORD),
    LOCATION_CONSENT(RiderRoutes.LOCATION_CONSENT),
    PERMISSION_GUIDE(RiderRoutes.PERMISSION_GUIDE),
    HOME(RiderRoutes.HOME),
}

/**
 * 会话读取的上限。DataStore 读盘加两次 Keystore 解密，正常在百毫秒级；
 * 超过这个数说明底层卡住了，必须给骑手一条出路，不能让加载圈一直转。
 */
private const val RESOLVE_TIMEOUT_MILLIS = 6_000L
private const val UPDATE_CHECK_TIMEOUT_MILLIS = 8_000L

/**
 * 启动页。只做一件事:判断该去哪。
 *
 * 令牌过期不在这里刷新 —— 刷新交给 OkHttp 的 Authenticator,
 * 这里放行到首页,由首页的第一个请求触发刷新,刷不动会自动回登录。
 *
 * [onResolved] 的第二个参数是要恢复的业务页路由，仅当落点是首页时才可能非空。
 */
@Composable
fun SplashScreen(
    tokenStore: RiderTokenStore,
    settingsStore: RiderSettingsStore,
    updateController: UpdateController,
    onResolved: (StartDestination, String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var attempt by remember { mutableIntStateOf(0) }
    var stuck by remember { mutableStateOf(false) }

    LaunchedEffect(attempt) {
        stuck = false
        // 整段包超时 + 兜异常：之前这里任何一步挂住都会永远停在加载圈上，
        // 模拟器上相机页退出后 Keystore 偶发不返回，表现就是「App 再也进不去」。
        val resolved = withTimeoutOrNull(RESOLVE_TIMEOUT_MILLIS) {
            runCatching {
                val session = tokenStore.current()
                val guide = settingsStore.currentGuide()
                val destination = when {
                    session == null -> StartDestination.LOGIN
                    session.mustChangePassword -> StartDestination.CHANGE_PASSWORD
                    !session.hasLocationConsent -> StartDestination.LOCATION_CONSENT
                    !guide.permissionGuideDone -> StartDestination.PERMISSION_GUIDE
                    else -> StartDestination.HOME
                }
                val resume = if (destination == StartDestination.HOME) {
                    settingsStore.currentLastRoute()
                } else {
                    null
                }
                destination to resume
            }.getOrNull()
        }
        if (resolved == null) {
            stuck = true
        } else {
            withTimeoutOrNull(UPDATE_CHECK_TIMEOUT_MILLIS) {
                updateController.checkOnLaunch()
            }
            if (!updateController.blocksBusiness()) {
                onResolved(resolved.first, resolved.second)
            }
        }
    }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(FreshSpacing.Xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(FreshSpacing.Lg, Alignment.CenterVertically),
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(FreshRadius.Hero))
                    .background(RiderColors.Primary),
                contentAlignment = Alignment.Center,
            ) {
                FreshIcon(
                    FreshIconType.STORE,
                    contentDescription = null,
                    tint = RiderColors.OnPrimary,
                    size = 34.dp,
                )
            }
            Text(
                text = "禹邻优鲜骑手",
                style = MaterialTheme.typography.titleLarge,
                color = RiderColors.Ink,
            )
            if (stuck) {
                Text(
                    text = "启动检查超时了。可以重试一次，或者直接重新登录。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                MtPrimaryButton(
                    text = "重试",
                    action = MtAction.ACCEPT,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { attempt++ },
                )
                MtPrimaryButton(
                    text = "重新登录",
                    action = MtAction.SECONDARY,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onResolved(StartDestination.LOGIN, null) },
                )
            } else {
                CircularProgressIndicator(
                    color = RiderColors.Primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    strokeWidth = 3.dp,
                )
            }
        }
    }
}
