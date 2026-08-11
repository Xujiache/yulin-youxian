package com.yulin.rider.feature.profile

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yulin.rider.core.common.RiderResult
import com.yulin.rider.core.designsystem.LoadingBox
import com.yulin.rider.core.designsystem.FreshBanner
import com.yulin.rider.core.designsystem.FreshIcon
import com.yulin.rider.core.designsystem.FreshIconType
import com.yulin.rider.core.designsystem.FreshLoading
import com.yulin.rider.core.designsystem.FreshPageHeader
import com.yulin.rider.core.designsystem.FreshPanel
import com.yulin.rider.core.designsystem.FreshShell
import com.yulin.rider.core.designsystem.FreshSpacing
import com.yulin.rider.core.designsystem.FreshStatusBadge
import com.yulin.rider.core.designsystem.RiderDimens
import com.yulin.rider.core.designsystem.RiderTheme
import com.yulin.rider.core.designsystem.SectionCard
import com.yulin.rider.core.designsystem.StatusChip
import com.yulin.rider.core.designsystem.StatusTone
import com.yulin.rider.core.model.RiderProfile
import com.yulin.rider.core.network.RiderApis
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 关于页需要的应用元信息。网络走 `RiderApis.of(context)`,这里只装配 feature 读不到的东西:
 * 版本号在 app 的 BuildConfig 里,备案号是运营信息。
 */
interface ProfileFeatureDependencies {
    val appVersionName: String
    val appVersionCode: Long

    /** APP 备案号必须在关于页显著展示且可点击跳转工信部系统(06 §6)。 */
    val icpFilingNumber: String?
        get() = null

    /** A8 设置页退出按钮的 app 层接线；负责服务端撤销、后台停机和本地数据清理。 */
    suspend fun logout(): RiderResult<Unit> =
        RiderResult.Failure(-2, "退出登录能力尚未装配")
}

object ProfileFeature {

    @Volatile
    private var dependencies: ProfileFeatureDependencies? = null

    fun install(dependencies: ProfileFeatureDependencies) {
        this.dependencies = dependencies
    }

    internal fun depsOrNull(): ProfileFeatureDependencies? = dependencies
}

data class ProfileUiState(
    val profile: RiderProfile? = null,
    val loading: Boolean = true,
    val error: String? = null,
)

class ProfileViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(ProfileUiState())
    val state: StateFlow<ProfileUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            val apis = RiderApis.of(getApplication())
            _state.value = when (val result = apis.caller.result { apis.shift.getProfile() }) {
                is RiderResult.Success -> ProfileUiState(profile = result.data, loading = false)
                is RiderResult.Failure -> ProfileUiState(loading = false, error = result.message)
                RiderResult.Loading -> _state.value
            }
        }
    }
}

/** 个人中心(路由 profile)。 */
@Composable
fun ProfileScreen(
    modifier: Modifier = Modifier,
    viewModel: ProfileViewModel = viewModel(),
    onOpenSettings: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
    onOpenMessages: () -> Unit = {},
    onOpenStats: () -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    ProfileContent(
        state = state,
        modifier = modifier,
        onOpenSettings = onOpenSettings,
        onOpenAbout = onOpenAbout,
        onOpenMessages = onOpenMessages,
        onOpenStats = onOpenStats,
    )
}

@Composable
private fun ProfileContent(
    state: ProfileUiState,
    modifier: Modifier = Modifier,
    onOpenSettings: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
    onOpenMessages: () -> Unit = {},
    onOpenStats: () -> Unit = {},
) {
    FreshShell(
        modifier = modifier,
        topBar = { FreshPageHeader("我的", subtitle = "账号、消息与运营统计") },
    ) { insets ->
        if (state.loading) {
            FreshLoading(
                modifier = Modifier.padding(insets).fillMaxSize(),
                label = "正在读取个人资料",
            )
            return@FreshShell
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
                .verticalScroll(rememberScrollState())
                .padding(FreshSpacing.Md),
            verticalArrangement = Arrangement.spacedBy(FreshSpacing.Sm),
        ) {
            val profile = state.profile
            FreshPanel(
                eyebrow = profile?.riderNo ?: "骑手账号",
                spineTone = if (profile?.workStatus == "ON_DUTY") StatusTone.SUCCESS else StatusTone.NORMAL,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(FreshSpacing.Sm),
                ) {
                    FreshIcon(
                        FreshIconType.PROFILE,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        size = com.yulin.rider.core.designsystem.FreshSpacing.Huge,
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = profile?.name ?: "未登录",
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        profile?.let {
                            Text(
                                "${it.riderNo} · ${it.phone}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                profile?.let {
                    Row(horizontalArrangement = Arrangement.spacedBy(FreshSpacing.Xs)) {
                        FreshStatusBadge(
                            text = if (it.workStatus == "ON_DUTY") "在岗" else "休息中",
                            tone = if (it.workStatus == "ON_DUTY") StatusTone.SUCCESS else StatusTone.NORMAL,
                            icon = if (it.workStatus == "ON_DUTY") FreshIconType.RIDER else FreshIconType.CLOCK,
                        )
                        it.vehiclePlate?.let { plate -> FreshStatusBadge(plate) }
                    }
                    if (it.healthCertExpiringSoon) {
                        FreshBanner(
                            text = "健康证即将过期，请及时更换",
                            tone = StatusTone.WARNING,
                            icon = FreshIconType.WARNING,
                        )
                    }
                }
                state.error?.let {
                    FreshBanner(it, tone = StatusTone.WARNING, icon = FreshIconType.OFFLINE)
                }
            }

            FreshPanel(title = "累计履约", spineTone = StatusTone.INFO) {
                profile?.let {
                    EntryRow(label = "累计完成", value = "${it.totalTaskCount} 单")
                    EntryRow(
                        label = "准时率",
                        value = it.onTimeRate?.let { rate -> "${(rate * 100).toInt()}%" } ?: "—",
                    )
                } ?: Text(
                    "暂无数据",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            FreshPanel(title = "运营工具") {
                NavRow(
                    text = "今日统计",
                    icon = FreshIconType.STATS,
                    onClick = onOpenStats,
                )
                NavRow(
                    text = "消息中心",
                    icon = FreshIconType.MESSAGE,
                    onClick = onOpenMessages,
                )
                NavRow(
                    text = "设置",
                    icon = FreshIconType.SETTINGS,
                    onClick = onOpenSettings,
                )
                NavRow(
                    text = "关于",
                    icon = FreshIconType.ABOUT,
                    onClick = onOpenAbout,
                )
            }
        }
    }
}

@Composable
internal fun EntryRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = FreshSpacing.Xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(text = value, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
internal fun NavRow(
    text: String,
    icon: FreshIconType = FreshIconType.CHEVRON_RIGHT,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { role = Role.Button }
            .padding(vertical = FreshSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FreshSpacing.Sm),
    ) {
        FreshIcon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        FreshIcon(
            FreshIconType.CHEVRON_RIGHT,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Preview(name = "个人 · 在岗", showBackground = true)
@Composable
private fun ProfilePreview() {
    RiderTheme {
        ProfileContent(
            ProfileUiState(
                profile = RiderProfile(
                    id = 1,
                    riderNo = "R0008",
                    name = "林师傅",
                    phone = "13800006608",
                    workStatus = "ON_DUTY",
                    vehiclePlate = "川A·12345",
                    totalTaskCount = 1268,
                    onTimeRate = .98,
                    healthCertExpiringSoon = true,
                ),
                loading = false,
            )
        )
    }
}

@Preview(name = "个人 · 加载", showBackground = true)
@Composable
private fun ProfileLoadingPreview() {
    RiderTheme { ProfileContent(ProfileUiState()) }
}

@Preview(name = "个人 · 离线", showBackground = true)
@Composable
private fun ProfileOfflinePreview() {
    RiderTheme {
        ProfileContent(ProfileUiState(loading = false, error = "当前离线，资料可能不是最新"))
    }
}
