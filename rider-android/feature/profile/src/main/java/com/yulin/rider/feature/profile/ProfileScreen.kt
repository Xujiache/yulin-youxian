package com.yulin.rider.feature.profile

import android.app.Application
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yulin.rider.core.common.RiderResult
import com.yulin.rider.core.designsystem.FreshIcon
import com.yulin.rider.core.designsystem.FreshIconType
import com.yulin.rider.core.designsystem.FreshLoading
import com.yulin.rider.core.designsystem.FreshSpacing
import com.yulin.rider.core.designsystem.MtCard
import com.yulin.rider.core.designsystem.MtDivider
import com.yulin.rider.core.designsystem.MtInfoBar
import com.yulin.rider.core.designsystem.MtMetric
import com.yulin.rider.core.designsystem.MtScaffold
import com.yulin.rider.core.designsystem.MtSectionTitle
import com.yulin.rider.core.designsystem.MtTag
import com.yulin.rider.core.designsystem.RiderColors
import com.yulin.rider.core.designsystem.RiderDimens
import com.yulin.rider.core.designsystem.RiderTheme
import com.yulin.rider.core.designsystem.StatusTone
import com.yulin.rider.core.designsystem.tabularFigures
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

    /**
     * 设置页「试听」。返回 false 表示这台手机没有可用的中文语音引擎，只响了提示音。
     *
     * 语音是骑车时唯一能用的通道，但缺中文引擎、被静音、被勿扰拦掉这些问题
     * 只有真的响一次才发现得了，所以必须给骑手一个上班前自检的入口。
     */
    suspend fun previewVoice(): Boolean = false

    /** 设备信息，关于页展示给客服排查用。 */
    val deviceModel: String get() = "${android.os.Build.BRAND} ${android.os.Build.MODEL}".trim()

    val androidVersion: String get() = "Android ${android.os.Build.VERSION.RELEASE}"

    /** 本机设备号，与服务端 rider_device 对应，报障时要用。 */
    val deviceId: String? get() = null

    /** 客服/店长电话，关于页可一键拨号。 */
    val supportPhone: String? get() = null
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
    onBack: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
    onOpenMessages: () -> Unit = {},
    onOpenStats: () -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    ProfileContent(
        state = state,
        modifier = modifier,
        onBack = onBack,
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
    onBack: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
    onOpenMessages: () -> Unit = {},
    onOpenStats: () -> Unit = {},
) {
    MtScaffold(title = "个人主页", modifier = modifier, onBack = onBack) {
        if (state.loading) {
            FreshLoading(modifier = Modifier.fillMaxSize(), label = "正在读取个人资料")
            return@MtScaffold
        }
        val profile = state.profile
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
                Row(
                    modifier = Modifier.fillMaxWidth().padding(FreshSpacing.Sm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(FreshSpacing.Sm),
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(RiderColors.PrimaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        FreshIcon(
                            FreshIconType.PROFILE,
                            contentDescription = null,
                            tint = RiderColors.Ink,
                            size = 30.dp,
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = profile?.name ?: "未登录",
                            style = MaterialTheme.typography.headlineSmall,
                            color = RiderColors.Ink,
                        )
                        profile?.let {
                            Text(
                                text = "${it.riderNo} · ${it.phone}",
                                style = MaterialTheme.typography.bodyMedium.tabularFigures(),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    profile?.let {
                        MtTag(
                            text = if (it.workStatus == "ON_DUTY") "在岗" else "休息中",
                            tone = if (it.workStatus == "ON_DUTY") {
                                StatusTone.SUCCESS
                            } else {
                                StatusTone.NORMAL
                            },
                        )
                    }
                }
                profile?.vehiclePlate?.let { plate ->
                    MtDivider()
                    Text(
                        text = "车牌 $plate",
                        style = MaterialTheme.typography.bodyMedium.tabularFigures(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(FreshSpacing.Sm),
                    )
                }
                if (profile?.healthCertExpiringSoon == true) {
                    MtInfoBar(text = "健康证即将过期，请及时更换", tone = StatusTone.WARNING)
                }
                state.error?.let {
                    MtInfoBar(it, tone = StatusTone.WARNING, icon = FreshIconType.OFFLINE)
                }
            }

            MtCard {
                MtSectionTitle("累计履约")
                MtDivider()
                Row(
                    modifier = Modifier.fillMaxWidth().padding(FreshSpacing.Sm),
                    horizontalArrangement = Arrangement.spacedBy(FreshSpacing.Sm),
                ) {
                    MtMetric(
                        label = "累计完成",
                        value = profile?.totalTaskCount?.toString() ?: "—",
                        unit = "单",
                        modifier = Modifier.weight(1f),
                    )
                    MtMetric(
                        label = "准时率",
                        value = profile?.onTimeRate?.let { "${(it * 100).toInt()}" } ?: "—",
                        unit = "%",
                        valueColor = RiderColors.Deliver,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            MtCard {
                NavRow("我的账户", FreshIconType.WALLET, onClick = onOpenStats)
                MtDivider()
                NavRow("消息中心", FreshIconType.MESSAGE, onClick = onOpenMessages)
                MtDivider()
                NavRow("设置", FreshIconType.SETTINGS, onClick = onOpenSettings)
                MtDivider()
                NavRow("关于", FreshIconType.ABOUT, onClick = onOpenAbout)
            }
        }
    }
}

@Composable
internal fun EntryRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = FreshSpacing.Sm, vertical = FreshSpacing.Xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(text = value, style = MaterialTheme.typography.titleMedium.tabularFigures())
    }
}

@Composable
internal fun NavRow(
    text: String,
    icon: FreshIconType = FreshIconType.CHEVRON_RIGHT,
    hint: String? = null,
    trailingText: String? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { role = Role.Button }
            .heightIn(min = RiderDimens.TouchTarget)
            .padding(horizontal = FreshSpacing.Sm, vertical = FreshSpacing.Sm),
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
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                color = RiderColors.Ink,
            )
            hint?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailingText?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FreshIcon(
            FreshIconType.CHEVRON_RIGHT,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            size = 16.dp,
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
