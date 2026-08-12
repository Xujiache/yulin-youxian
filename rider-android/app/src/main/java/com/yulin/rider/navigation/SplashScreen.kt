package com.yulin.rider.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.yulin.rider.core.datastore.RiderSettingsStore
import com.yulin.rider.core.datastore.RiderTokenStore
import com.yulin.rider.core.designsystem.FreshIcon
import com.yulin.rider.core.designsystem.FreshIconType
import com.yulin.rider.core.designsystem.FreshRadius
import com.yulin.rider.core.designsystem.FreshSpacing
import com.yulin.rider.core.designsystem.RiderColors

/** 启动后应当落到哪一屏。顺序即业务前置条件的顺序,不能调换。 */
enum class StartDestination(val route: String) {
    LOGIN(RiderRoutes.LOGIN),
    CHANGE_PASSWORD(RiderRoutes.CHANGE_PASSWORD),
    LOCATION_CONSENT(RiderRoutes.LOCATION_CONSENT),
    PERMISSION_GUIDE(RiderRoutes.PERMISSION_GUIDE),
    HOME(RiderRoutes.HOME),
}

/**
 * 启动页。只做一件事:判断该去哪。
 *
 * 令牌过期不在这里刷新 —— 刷新交给 OkHttp 的 Authenticator,
 * 这里放行到首页,由首页的第一个请求触发刷新,刷不动会自动回登录。
 */
@Composable
fun SplashScreen(
    tokenStore: RiderTokenStore,
    settingsStore: RiderSettingsStore,
    onResolved: (StartDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(Unit) {
        val session = tokenStore.current()
        val guide = settingsStore.currentGuide()
        val destination = when {
            session == null -> StartDestination.LOGIN
            session.mustChangePassword -> StartDestination.CHANGE_PASSWORD
            !session.hasLocationConsent -> StartDestination.LOCATION_CONSENT
            !guide.permissionGuideDone -> StartDestination.PERMISSION_GUIDE
            else -> StartDestination.HOME
        }
        onResolved(destination)
    }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier.fillMaxSize(),
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
            CircularProgressIndicator(
                color = RiderColors.Primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                strokeWidth = 3.dp,
            )
        }
    }
}
