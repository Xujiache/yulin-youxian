package com.yulin.rider.feature.profile

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.Settings
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
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
import kotlin.math.roundToInt

/**
 * 字号档位。只给四档、不做无级调节 —— 骑手是在颠簸和强光下改这个设置的，
 * 拖到 1.07 倍这种中间值既没意义又难复现，档位之间必须能一次拖准。
 */
enum class FontSizeLevel(val label: String, val scale: Float) {
    SMALL("小", 0.9f),
    STANDARD("标准", 1.0f),
    LARGE("大", 1.15f),
    EXTRA_LARGE("超大", 1.3f),
    ;

    companion object {
        fun of(scale: Float) = entries.minBy { kotlin.math.abs(it.scale - scale) }
    }
}

/**
 * 播报语速。风噪大的时候调慢一档比调大音量管用得多。
 * 与字号一样只给固定档位：骑手是戴着手套在路边调这个的。
 */
enum class SpeechRateLevel(val label: String, val rate: Float) {
    SLOW("慢", 0.8f),
    NORMAL("正常", 1.0f),
    FAST("快", 1.2f),
    ;

    companion object {
        fun of(rate: Float) = entries.minBy { kotlin.math.abs(it.rate - rate) }
    }
}

class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val store = RiderSettingsStore(app)
    val settings: StateFlow<RiderSettings> = store.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RiderSettings())
    private val _logoutState = MutableStateFlow<RiderResult<Unit>?>(null)
    val logoutState: StateFlow<RiderResult<Unit>?> = _logoutState.asStateFlow()

    /** 试听结果：null 未试听，true 语音正常，false 只响了提示音（缺中文引擎）。 */
    private val _previewResult = MutableStateFlow<Boolean?>(null)
    val previewResult: StateFlow<Boolean?> = _previewResult.asStateFlow()

    // 语音播报与提示音拆成两个独立开关：
    // 骑手可能想要「读出来但别再叮一声」，也可能反过来只要提示音不要读。
    fun setVoice(enabled: Boolean) = viewModelScope.launch { store.setVoiceEnabled(enabled) }
    fun setSound(enabled: Boolean) = viewModelScope.launch { store.setSoundEnabled(enabled) }
    fun setSpeechRate(level: SpeechRateLevel) = viewModelScope.launch { store.setSpeechRate(level.rate) }

    fun previewVoice() = viewModelScope.launch {
        _previewResult.value = ProfileFeature.depsOrNull()?.previewVoice() ?: false
    }

    fun consumePreviewResult() {
        _previewResult.value = null
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
    onOpenChangePassword: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
    onLoggedOut: () -> Unit = {},
) {
    val settings by viewModel.settings.collectAsState()
    val logoutState by viewModel.logoutState.collectAsState()
    val previewResult by viewModel.previewResult.collectAsState()
    val context = LocalContext.current
    LaunchedEffect(logoutState) {
        if (logoutState is RiderResult.Success) {
            viewModel.consumeLogoutState()
            onLoggedOut()
        }
    }
    SettingsContent(
        settings = settings,
        logoutState = logoutState,
        previewResult = previewResult,
        modifier = modifier,
        onBack = onBack,
        onVoiceChange = viewModel::setVoice,
        onSoundChange = viewModel::setSound,
        onSpeechRateChange = viewModel::setSpeechRate,
        onPreviewVoice = viewModel::previewVoice,
        onDismissPreview = viewModel::consumePreviewResult,
        onScreenOnChange = viewModel::setKeepScreenOn,
        onFontChange = viewModel::setFontSize,
        onOpenKeepAliveGuide = onOpenKeepAliveGuide,
        onOpenSystemNotification = { openNotificationSettings(context) },
        onOpenVoiceSettings = { openVoiceSettings(context) },
        onOpenChangePassword = onOpenChangePassword,
        onOpenAbout = onOpenAbout,
        onLogout = viewModel::logout,
    )
}

@Composable
private fun SettingsContent(
    settings: RiderSettings,
    logoutState: RiderResult<Unit>? = null,
    previewResult: Boolean? = null,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onVoiceChange: (Boolean) -> Unit = {},
    onSoundChange: (Boolean) -> Unit = {},
    onSpeechRateChange: (SpeechRateLevel) -> Unit = {},
    onPreviewVoice: () -> Unit = {},
    onDismissPreview: () -> Unit = {},
    onScreenOnChange: (Boolean) -> Unit = {},
    onFontChange: (FontSizeLevel) -> Unit = {},
    onOpenKeepAliveGuide: () -> Unit = {},
    onOpenSystemNotification: () -> Unit = {},
    onOpenVoiceSettings: () -> Unit = {},
    onOpenChangePassword: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
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
                MtSectionTitle(
                    "语音播报",
                    trailing = {
                        MtTag(
                            text = if (settings.voiceEnabled) "已开启" else "已关闭",
                            tone = if (settings.voiceEnabled) StatusTone.SUCCESS else StatusTone.NORMAL,
                        )
                    },
                )
                MtDivider()
                SwitchRow(
                    label = "语音播报",
                    hint = "新单和紧急消息自动读出，骑车时不用看屏幕",
                    icon = FreshIconType.MESSAGE,
                    checked = settings.voiceEnabled,
                    onCheckedChange = onVoiceChange,
                )
                MtDivider()
                SwitchRow(
                    label = "提示音",
                    hint = "播报前先响一声，滑动确认成功也会出声",
                    icon = FreshIconType.CLOCK,
                    checked = settings.soundEnabled,
                    onCheckedChange = onSoundChange,
                )
                if (settings.voiceEnabled) {
                    MtDivider()
                    SpeechRateSlider(
                        current = SpeechRateLevel.of(settings.speechRate),
                        onChange = onSpeechRateChange,
                    )
                }
                MtDivider()
                // 缺中文引擎、手机静音、被勿扰拦掉，这些都只有真响一次才发现得了。
                // 上班前自检比出了问题再排查有用得多。
                VoicePreviewRow(
                    result = previewResult,
                    onPreview = onPreviewVoice,
                    onDismiss = onDismissPreview,
                    onOpenVoiceSettings = onOpenVoiceSettings,
                )
            }

            MtCard {
                MtSectionTitle("提醒与通知")
                MtDivider()
                NavRow(
                    text = "系统通知设置",
                    hint = "关掉通知会漏单，出问题先来这里检查",
                    icon = FreshIconType.MESSAGE,
                    onClick = onOpenSystemNotification,
                )
            }

            MtCard {
                MtSectionTitle("显示")
                MtDivider()
                SwitchRow(
                    label = "骑行常亮",
                    hint = "配送页面保持屏幕不熄灭",
                    icon = FreshIconType.ROUTE,
                    checked = settings.keepScreenOn,
                    onCheckedChange = onScreenOnChange,
                )
                MtDivider()
                FontSizeSlider(
                    current = FontSizeLevel.of(settings.fontScale),
                    onChange = onFontChange,
                )
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
                MtSectionTitle("账号与安全")
                MtDivider()
                NavRow(
                    text = "修改密码",
                    hint = "定期更换，别和其它 App 用同一个",
                    icon = FreshIconType.LOCK,
                    onClick = onOpenChangePassword,
                )
                MtDivider()
                NavRow(
                    text = "关于",
                    hint = "版本、备案号、第三方 SDK 清单",
                    icon = FreshIconType.ABOUT,
                    onClick = onOpenAbout,
                )
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

/**
 * 试听。
 *
 * 试听结果要给出可操作的下一步：只响提示音说明这台手机没有中文语音引擎，
 * 光提示「不可用」没意义，得能一键跳到系统的语音设置去装语音包。
 */
@Composable
private fun VoicePreviewRow(
    result: Boolean?,
    onPreview: () -> Unit,
    onDismiss: () -> Unit,
    onOpenVoiceSettings: () -> Unit,
) {
    Column(Modifier.padding(FreshSpacing.Sm)) {
        MtPrimaryButton(
            text = "试听一次",
            action = MtAction.SECONDARY,
            icon = FreshIconType.MESSAGE,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                onDismiss()
                onPreview()
            },
        )
        when (result) {
            true -> Text(
                text = "已用语音播报。听不到就检查手机音量和勿扰模式。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = FreshSpacing.Xs),
            )

            false -> Column(Modifier.padding(top = FreshSpacing.Xs)) {
                Text(
                    text = "这台手机没有可用的中文语音引擎，只能响提示音。装一个语音包就能正常播报。",
                    style = MaterialTheme.typography.bodySmall,
                    color = RiderColors.Pickup,
                )
                MtPrimaryButton(
                    text = "去装中文语音包",
                    action = MtAction.SECONDARY,
                    icon = FreshIconType.SETTINGS,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = FreshSpacing.Xs),
                    onClick = onOpenVoiceSettings,
                )
            }

            null -> Unit
        }
    }
}

/** 打开本应用的系统通知设置页。关通知是最常见的漏单原因。 */
private fun openNotificationSettings(context: android.content.Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.onFailure { openAppDetails(context) }
}

/** 打开系统的「文字转语音」设置。部分 ROM 没有这个页面，兜底到应用详情。 */
private fun openVoiceSettings(context: android.content.Context) {
    runCatching {
        context.startActivity(
            Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.onFailure { openAppDetails(context) }
}

private fun openAppDetails(context: android.content.Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/** 关于页。 */
@Composable
fun AboutScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onCheckUpdate: () -> Unit = {},
) {
    val context = LocalContext.current
    val deps = ProfileFeature.depsOrNull()
    val clipboard = LocalClipboardManager.current
    AboutContent(
        versionName = deps?.appVersionName,
        versionCode = deps?.appVersionCode,
        filing = deps?.icpFilingNumber,
        deviceModel = deps?.deviceModel.orEmpty(),
        androidVersion = deps?.androidVersion.orEmpty(),
        deviceId = deps?.deviceId,
        supportPhone = deps?.supportPhone,
        modifier = modifier,
        onBack = onBack,
        onCheckUpdate = onCheckUpdate,
        onOpenFiling = {
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://beian.miit.gov.cn"))
                )
            }
        },
        onCopyDeviceId = {
            deps?.deviceId?.takeIf { it.isNotBlank() }?.let {
                clipboard.setText(AnnotatedString(it))
            }
        },
        onCallSupport = {
            deps?.supportPhone?.takeIf { it.isNotBlank() }?.let { phone ->
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")))
                }
            }
        },
    )
}

@Composable
private fun AboutContent(
    versionName: String?,
    versionCode: Long?,
    filing: String?,
    deviceModel: String = "",
    androidVersion: String = "",
    deviceId: String? = null,
    supportPhone: String? = null,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onCheckUpdate: () -> Unit = {},
    onOpenFiling: () -> Unit = {},
    onCopyDeviceId: () -> Unit = {},
    onCallSupport: () -> Unit = {},
) {
    var sdkExpanded by remember { mutableStateOf(false) }

    MtScaffold(title = "关于", modifier = modifier, onBack = onBack) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    start = FreshSpacing.Sm,
                    end = FreshSpacing.Sm,
                    top = FreshSpacing.Md,
                    bottom = FreshSpacing.Xl,
                ),
            verticalArrangement = Arrangement.spacedBy(FreshSpacing.Xs),
        ) {
            AboutHero(versionName = versionName)

            MtCard {
                MtSectionTitle("版本信息")
                MtDivider()
                EntryRow("版本号", versionName ?: "—")
                MtDivider()
                EntryRow("构建号", versionCode?.toString() ?: "—")
                MtDivider()
                EntryRow("应用包名", "com.yulin.rider")
                MtDivider()
                Box(Modifier.padding(FreshSpacing.Sm)) {
                    MtPrimaryButton(
                        text = "检查更新",
                        action = MtAction.ACCEPT,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onCheckUpdate,
                    )
                }
            }

            MtCard {
                MtSectionTitle(
                    "合规信息",
                    trailing = {
                        MtTag(
                            text = if (filing.isNullOrBlank()) "备案办理中" else "已备案",
                            tone = if (filing.isNullOrBlank()) StatusTone.WARNING else StatusTone.SUCCESS,
                        )
                    },
                )
                MtDivider()
                if (filing.isNullOrBlank()) {
                    MtInfoBar(
                        text = "APP 备案办理中，取得备案号后会在这里展示并可跳转工信部系统核验。",
                        icon = FreshIconType.INFO,
                    )
                } else {
                    NavRow(
                        text = "APP 备案号",
                        hint = filing,
                        icon = FreshIconType.ABOUT,
                        onClick = onOpenFiling,
                    )
                }
                MtDivider()
                // 位置信息授权同意书骑手在首次登录时签过，这里放个入口方便随时回看
                EntryRow("位置信息授权", "已在首次登录时签署")
                MtDivider()
                SdkDisclosureRow(expanded = sdkExpanded, onToggle = { sdkExpanded = !sdkExpanded })
            }

            MtCard {
                MtSectionTitle("本机信息", trailing = { MtTag("报障时提供", tone = StatusTone.NORMAL) })
                MtDivider()
                EntryRow("手机型号", deviceModel.ifBlank { "—" })
                MtDivider()
                EntryRow("系统版本", androidVersion.ifBlank { "—" })
                MtDivider()
                // 设备号是服务端 rider_device 的主键，排查「为什么这台手机收不到单」全靠它
                NavRow(
                    text = "设备号",
                    hint = deviceId?.takeIf { it.isNotBlank() } ?: "—",
                    icon = FreshIconType.RIDER,
                    trailingText = "复制",
                    onClick = onCopyDeviceId,
                )
            }

            MtCard {
                MtSectionTitle("遇到问题")
                MtDivider()
                if (supportPhone.isNullOrBlank()) {
                    MtInfoBar(
                        text = "配送、账号、结算相关问题请直接联系店长。",
                        icon = FreshIconType.INFO,
                    )
                } else {
                    NavRow(
                        text = "联系店长",
                        hint = supportPhone,
                        icon = FreshIconType.PHONE,
                        onClick = onCallSupport,
                    )
                }
            }

            Text(
                text = "禹邻优鲜 · 家庭生鲜配送\n本应用仅供签约骑手使用",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = FreshSpacing.Md),
            )
        }
    }
}

/** 关于页头图。图标压在品牌黄的圆角块上，版本号做成胶囊贴在名字下面。 */
@Composable
private fun AboutHero(versionName: String?) {
    MtCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = FreshSpacing.Lg),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(FreshRadius.Hero))
                    .background(RiderColors.Primary),
                contentAlignment = Alignment.Center,
            ) {
                FreshIcon(
                    FreshIconType.STORE,
                    contentDescription = null,
                    tint = RiderColors.OnPrimary,
                    size = 38.dp,
                )
            }
            Text(
                text = "禹邻优鲜骑手",
                style = MaterialTheme.typography.headlineSmall,
                color = RiderColors.Ink,
                modifier = Modifier.padding(top = FreshSpacing.Sm),
            )
            Text(
                text = "家庭生鲜配送",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = FreshSpacing.Xxs),
            )
            Box(modifier = Modifier.padding(top = FreshSpacing.Xs)) {
                MtTag(
                    text = versionName?.let { "v$it" } ?: "版本未知",
                    tone = StatusTone.NORMAL,
                )
            }
        }
    }
}

/**
 * 第三方 SDK 清单。
 *
 * 这不是装饰：个人信息保护法要求把「哪些第三方拿到了什么信息」写清楚，
 * 内容按工程里实际打进包的依赖列，不能照抄模板。
 */
@Composable
private fun SdkDisclosureRow(expanded: Boolean, onToggle: () -> Unit) {
    Column {
        NavRow(
            text = "第三方 SDK 清单",
            hint = if (expanded) "点击收起" else "看看哪些 SDK 拿到了什么信息",
            icon = FreshIconType.INFO,
            onClick = onToggle,
        )
        if (!expanded) return@Column
        Column(
            modifier = Modifier.padding(
                start = FreshSpacing.Sm,
                end = FreshSpacing.Sm,
                bottom = FreshSpacing.Sm,
            ),
            verticalArrangement = Arrangement.spacedBy(FreshSpacing.Xs),
        ) {
            SDK_DISCLOSURES.forEach { item ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(FreshRadius.Control))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .padding(FreshSpacing.Xs),
                ) {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = RiderColors.Ink,
                    )
                    Text(
                        text = "用途：${item.purpose}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "涉及信息：${item.data}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private data class SdkDisclosure(val name: String, val purpose: String, val data: String)

/** 按 rider-android 实际打进包的依赖列，改依赖时这里要跟着改。 */
private val SDK_DISCLOSURES = listOf(
    SdkDisclosure(
        name = "高德地图 / 导航 SDK（高德软件）",
        purpose = "配送路线展示、导航、定位",
        data = "位置信息、设备标识",
    ),
    SdkDisclosure(
        name = "CameraX（Google）",
        purpose = "拍摄取货与送达凭证",
        data = "相机画面，照片仅存本机并上传至门店服务器",
    ),
    SdkDisclosure(
        name = "OkHttp / Retrofit（Square）",
        purpose = "与门店服务器通信",
        data = "不额外采集信息",
    ),
    SdkDisclosure(
        name = "极光推送（和讯华谷）",
        purpose = "派单实时提醒",
        data = "当前版本未启用，派单提醒走轮询",
    ),
)

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

/** 播报语速。复用字号那套带吸附的滑块，交互一致，骑手只要学一次。 */
@Composable
private fun SpeechRateSlider(
    current: SpeechRateLevel,
    onChange: (SpeechRateLevel) -> Unit,
) {
    val levels = SpeechRateLevel.entries
    val haptic = LocalHapticFeedback.current
    var dragging by remember { mutableStateOf<Float?>(null) }
    val committed = levels.indexOf(current).coerceAtLeast(0)
    val position = dragging ?: committed.toFloat()
    val activeIndex = position.roundToInt().coerceIn(0, levels.lastIndex)

    LaunchedEffect(current) { dragging = null }

    Column(
        modifier = Modifier.padding(
            start = FreshSpacing.Sm,
            end = FreshSpacing.Sm,
            top = FreshSpacing.Xs,
            bottom = FreshSpacing.Sm,
        ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "播报语速",
                style = MaterialTheme.typography.bodyLarge,
                color = RiderColors.Ink,
                modifier = Modifier.weight(1f),
            )
            MtTag(current.label, tone = StatusTone.NORMAL)
        }
        Text(
            text = "风噪大听不清时调慢一档，比调大音量管用。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = position,
            valueRange = 0f..levels.lastIndex.toFloat(),
            steps = levels.size - 2,
            onValueChange = { raw ->
                val snapped = raw.roundToInt().coerceIn(0, levels.lastIndex)
                if (snapped != (dragging?.roundToInt() ?: committed)) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                }
                dragging = snapped.toFloat()
            },
            onValueChangeFinished = {
                val target = (dragging?.roundToInt() ?: committed).coerceIn(0, levels.lastIndex)
                levels.getOrNull(target)?.let(onChange)
            },
            colors = SliderDefaults.colors(
                thumbColor = RiderColors.Primary,
                activeTrackColor = RiderColors.Primary,
                activeTickColor = RiderColors.OnPrimary.copy(alpha = 0.35f),
            ),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = RiderDimens.TouchTarget)
                .semantics { contentDescription = "播报语速，当前${levels[activeIndex].label}" },
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            levels.forEachIndexed { index, level ->
                Text(
                    text = level.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (index == activeIndex) {
                        RiderColors.Ink
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    textAlign = when (index) {
                        0 -> TextAlign.Start
                        levels.lastIndex -> TextAlign.End
                        else -> TextAlign.Center
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * 字号选择。
 *
 * 用带吸附的滑块而不是一排按钮：四档并排后每个只剩 80 dp 宽，戴手套点不准，
 * 而滑块可以从任意位置拖过去。吸附是刻意的 —— 骑手在颠簸和强光下调这个，
 * 拖到 1.07 倍这种中间值既没意义又难复现，所以只在四个档位上停。
 */
@Composable
private fun FontSizeSlider(
    current: FontSizeLevel,
    onChange: (FontSizeLevel) -> Unit,
) {
    val levels = FontSizeLevel.entries
    val haptic = LocalHapticFeedback.current
    // 拖动中先用本地值驱动，松手才落盘：每一帧都写 DataStore 会把整棵树重组到卡顿
    var dragging by remember { mutableStateOf<Float?>(null) }
    val committed = levels.indexOf(current).coerceAtLeast(0)
    val position = dragging ?: committed.toFloat()
    val activeIndex = position.roundToInt().coerceIn(0, levels.lastIndex)

    // 落盘生效后再交回给 current 驱动。中途不清 dragging，否则松手瞬间
    // 会先弹回旧档位再跳到新档位，看着像抖了一下。
    LaunchedEffect(current) { dragging = null }

    Column(
        modifier = Modifier.padding(
            start = FreshSpacing.Sm,
            end = FreshSpacing.Sm,
            top = FreshSpacing.Xs,
            bottom = FreshSpacing.Sm,
        ),
    ) {
        Text(
            text = "拖动选择，会自动停在最近一档。调到「大」以上时按钮和卡片会跟着增高，一屏能看的单子变少。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = position,
            valueRange = 0f..levels.lastIndex.toFloat(),
            steps = levels.size - 2,
            onValueChange = { raw ->
                val snapped = raw.roundToInt().coerceIn(0, levels.lastIndex)
                // 这里必须读 dragging 这个 state，不能用组合期算好的 activeIndex：
                // 一次拖动里 onValueChange 会连发多次，中间不保证有重组，
                // 读快照值等于一直在跟旧档位比较。
                if (snapped != (dragging?.roundToInt() ?: committed)) {
                    // 每跨过一档震一下，不用盯着屏幕也知道换档了
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                }
                dragging = snapped.toFloat()
            },
            onValueChangeFinished = {
                // 同上：松手时也要读 state。之前读 activeIndex 会把原档位又写回去，
                // 表现就是滑块怎么拖都弹回原位。
                val target = (dragging?.roundToInt() ?: committed).coerceIn(0, levels.lastIndex)
                levels.getOrNull(target)?.let(onChange)
            },
            colors = SliderDefaults.colors(
                thumbColor = RiderColors.Primary,
                activeTrackColor = RiderColors.Primary,
                activeTickColor = RiderColors.OnPrimary.copy(alpha = 0.35f),
            ),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = RiderDimens.TouchTarget)
                .semantics { contentDescription = "字号，当前${levels[activeIndex].label}" },
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            levels.forEachIndexed { index, level ->
                Text(
                    text = level.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (index == activeIndex) {
                        RiderColors.Ink
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    textAlign = when (index) {
                        0 -> TextAlign.Start
                        levels.lastIndex -> TextAlign.End
                        else -> TextAlign.Center
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
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
