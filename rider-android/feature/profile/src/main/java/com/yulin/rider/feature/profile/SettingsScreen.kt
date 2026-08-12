package com.yulin.rider.feature.profile

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import com.yulin.rider.core.datastore.RiderSettings
import com.yulin.rider.core.datastore.RiderSettingsStore
import com.yulin.rider.core.common.RiderResult
import com.yulin.rider.core.designsystem.FreshIcon
import com.yulin.rider.core.designsystem.FreshIconType
import com.yulin.rider.core.designsystem.FreshRadius
import com.yulin.rider.core.designsystem.FreshSpacing
import com.yulin.rider.core.designsystem.MtAction
import com.yulin.rider.core.designsystem.MtCard
import com.yulin.rider.core.designsystem.MtDivider
import com.yulin.rider.core.designsystem.MtInfoBar
import com.yulin.rider.core.designsystem.MtPrimaryButton
import com.yulin.rider.core.designsystem.MtScaffold
import com.yulin.rider.core.designsystem.MtSectionTitle
import com.yulin.rider.core.designsystem.MtTag
import com.yulin.rider.core.designsystem.RiderColors
import com.yulin.rider.core.designsystem.RiderDimens
import com.yulin.rider.core.designsystem.RiderTheme
import com.yulin.rider.core.designsystem.StatusTone
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class FontSizeLevel(val label: String, val scale: Float) {
    STANDARD("标准", 1.0f),
    LARGE("大", 1.15f),
    EXTRA_LARGE("超大", 1.3f),
    ;

    companion object {
        fun of(scale: Float) = entries.minBy { kotlin.math.abs(it.scale - scale) }
    }
}

class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val store = RiderSettingsStore(app)
    val settings: StateFlow<RiderSettings> = store.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RiderSettings())
    private val _logoutState = MutableStateFlow<RiderResult<Unit>?>(null)
    val logoutState: StateFlow<RiderResult<Unit>?> = _logoutState.asStateFlow()

    fun setVoice(enabled: Boolean) = viewModelScope.launch {
        store.setVoiceEnabled(enabled)
        store.setSoundEnabled(enabled)
    }

    fun setFontSize(level: FontSizeLevel) = viewModelScope.launch { store.setFontScale(level.scale) }
    fun setKeepScreenOn(enabled: Boolean) = viewModelScope.launch { store.setKeepScreenOn(enabled) }

    /** A8 只需把退出按钮绑定到这里，并在 Success 时导航登录页。 */
    fun logout() = viewModelScope.launch {
        _logoutState.value = RiderResult.Loading
        _logoutState.value = ProfileFeature.depsOrNull()?.logout()
            ?: RiderResult.Failure(-2, "退出登录能力尚未装配")
    }

    fun consumeLogoutState() {
        _logoutState.value = null
    }
}

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(),
    onBack: () -> Unit = {},
    onOpenKeepAliveGuide: () -> Unit = {},
    onLoggedOut: () -> Unit = {},
) {
    val settings by viewModel.settings.collectAsState()
    val logoutState by viewModel.logoutState.collectAsState()
    LaunchedEffect(logoutState) {
        if (logoutState is RiderResult.Success) {
            viewModel.consumeLogoutState()
            onLoggedOut()
        }
    }
    SettingsContent(
        settings = settings,
        logoutState = logoutState,
        modifier = modifier,
        onBack = onBack,
        onVoiceChange = viewModel::setVoice,
        onScreenOnChange = viewModel::setKeepScreenOn,
        onFontChange = viewModel::setFontSize,
        onOpenKeepAliveGuide = onOpenKeepAliveGuide,
        onLogout = viewModel::logout,
    )
}

@Composable
private fun SettingsContent(
    settings: RiderSettings,
    logoutState: RiderResult<Unit>? = null,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onVoiceChange: (Boolean) -> Unit = {},
    onScreenOnChange: (Boolean) -> Unit = {},
    onFontChange: (FontSizeLevel) -> Unit = {},
    onOpenKeepAliveGuide: () -> Unit = {},
    onLogout: () -> Unit = {},
) {
    MtScaffold(
        title = "设置",
        subtitle = "骑行读屏、播报与后台在线",
        modifier = modifier,
        onBack = onBack,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    start = FreshSpacing.Sm,
                    end = FreshSpacing.Sm,
                    top = FreshSpacing.Xs,
                    bottom = FreshSpacing.Md,
                ),
            verticalArrangement = Arrangement.spacedBy(FreshSpacing.Xs),
        ) {
            MtCard {
                MtSectionTitle("播报与显示")
                MtDivider()
                SwitchRow(
                    label = "语音播报",
                    hint = "新单和超时预警自动读出，骑车时不用看屏幕",
                    icon = FreshIconType.MESSAGE,
                    checked = settings.voiceEnabled,
                    onCheckedChange = onVoiceChange,
                )
                MtDivider()
                SwitchRow(
                    label = "骑行常亮",
                    hint = "配送页面保持屏幕不熄灭",
                    icon = FreshIconType.ROUTE,
                    checked = settings.keepScreenOn,
                    onCheckedChange = onScreenOnChange,
                )
            }

            MtCard {
                MtSectionTitle("字号")
                MtDivider()
                Column(Modifier.padding(FreshSpacing.Sm)) {
                    Text(
                        text = "支持最高 1.3 倍，按钮与卡片会随文字一起增高。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val current = FontSizeLevel.of(settings.fontScale)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = FreshSpacing.Xs),
                        horizontalArrangement = Arrangement.spacedBy(FreshSpacing.Xs),
                    ) {
                        FontSizeLevel.entries.forEach { level ->
                            FilterChip(
                                selected = level == current,
                                onClick = { onFontChange(level) },
                                label = {
                                    Text(level.label, style = MaterialTheme.typography.labelLarge)
                                },
                                shape = RoundedCornerShape(FreshRadius.Control),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = RiderColors.PrimaryContainer,
                                    selectedLabelColor = RiderColors.Ink,
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = RiderDimens.TouchTarget),
                            )
                        }
                    }
                }
            }

            MtCard {
                MtSectionTitle("后台运行", trailing = { MtTag("国产手机重点检查", tone = StatusTone.WARNING) })
                MtDivider()
                MtInfoBar(
                    text = "省电策略可能在锁屏后终止定位。完成保活向导，配送中才不会掉线。",
                    tone = StatusTone.WARNING,
                    icon = FreshIconType.BATTERY,
                )
                NavRow(
                    text = "保活设置向导",
                    icon = FreshIconType.BATTERY,
                    onClick = onOpenKeepAliveGuide,
                )
            }

            MtCard {
                MtSectionTitle("账号")
                MtDivider()
                (logoutState as? RiderResult.Failure)?.let { failure ->
                    MtInfoBar(
                        text = failure.message,
                        tone = StatusTone.DANGER,
                        icon = FreshIconType.ERROR,
                    )
                }
                Box(Modifier.padding(FreshSpacing.Sm)) {
                    MtPrimaryButton(
                        text = if (logoutState is RiderResult.Loading) "正在退出…" else "退出登录",
                        action = MtAction.SECONDARY,
                        enabled = logoutState !is RiderResult.Loading,
                        disabledReason = "正在退出登录",
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onLogout,
                    )
                }
            }
        }
    }
}

/** 关于页。 */
@Composable
fun AboutScreen(modifier: Modifier = Modifier, onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val deps = ProfileFeature.depsOrNull()
    AboutContent(
        versionName = deps?.appVersionName,
        versionCode = deps?.appVersionCode,
        filing = deps?.icpFilingNumber,
        modifier = modifier,
        onBack = onBack,
        onOpenFiling = {
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://beian.miit.gov.cn"))
                )
            }
        },
    )
}

@Composable
private fun AboutContent(
    versionName: String?,
    versionCode: Long?,
    filing: String?,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onOpenFiling: () -> Unit = {},
) {
    MtScaffold(title = "关于", modifier = modifier, onBack = onBack) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    start = FreshSpacing.Sm,
                    end = FreshSpacing.Sm,
                    top = FreshSpacing.Lg,
                    bottom = FreshSpacing.Md,
                ),
            verticalArrangement = Arrangement.spacedBy(FreshSpacing.Xs),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(FreshRadius.Hero))
                    .background(RiderColors.PrimaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                FreshIcon(
                    FreshIconType.STORE,
                    contentDescription = null,
                    tint = RiderColors.Ink,
                    size = 32.dp,
                )
            }
            Text(
                text = "禹邻优鲜骑手端",
                style = MaterialTheme.typography.titleLarge,
                color = RiderColors.Ink,
                modifier = Modifier.padding(top = FreshSpacing.Xs),
            )
            Text(
                text = "家庭生鲜配送运营中枢",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = FreshSpacing.Sm),
            )

            MtCard {
                EntryRow("版本号", versionName ?: "—")
                MtDivider()
                EntryRow("构建号", versionCode?.toString() ?: "—")
            }

            MtCard {
                MtSectionTitle("APP 备案号")
                MtDivider()
                if (filing.isNullOrBlank()) {
                    MtInfoBar(text = "备案办理中", icon = FreshIconType.INFO)
                } else {
                    NavRow(text = filing, icon = FreshIconType.ABOUT, onClick = onOpenFiling)
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(
    label: String,
    hint: String,
    icon: FreshIconType,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Switch) { onCheckedChange(!checked) }
            .semantics { role = Role.Switch }
            .padding(horizontal = FreshSpacing.Sm, vertical = FreshSpacing.Xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FreshSpacing.Sm),
    ) {
        FreshIcon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            size = 20.dp,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = RiderColors.Ink)
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = RiderColors.OnPrimary,
                checkedTrackColor = RiderColors.Primary,
                checkedBorderColor = RiderColors.Primary,
            ),
        )
    }
}

@Preview(name = "设置 · 标准", showBackground = true)
@Composable
private fun SettingsPreview() {
    RiderTheme {
        SettingsContent(
            RiderSettings(
                voiceEnabled = true,
                keepScreenOn = true,
                fontScale = 1.15f,
            )
        )
    }
}

@Preview(name = "关于", showBackground = true)
@Composable
private fun AboutPreview() {
    RiderTheme {
        AboutContent(
            versionName = "1.0.0",
            versionCode = 100L,
            filing = "蜀ICP备202600001号-2A",
        )
    }
}
