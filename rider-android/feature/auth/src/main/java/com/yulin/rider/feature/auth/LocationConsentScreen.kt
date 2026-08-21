package com.yulin.rider.feature.auth

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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yulin.rider.core.common.RiderResult
import com.yulin.rider.core.designsystem.FreshIconType
import com.yulin.rider.core.designsystem.FreshSpacing
import com.yulin.rider.core.designsystem.MtAction
import com.yulin.rider.core.designsystem.MtBottomActionBar
import com.yulin.rider.core.designsystem.MtCard
import com.yulin.rider.core.designsystem.MtDivider
import com.yulin.rider.core.designsystem.MtInfoBar
import com.yulin.rider.core.designsystem.MtPrimaryButton
import com.yulin.rider.core.designsystem.MtScaffold
import com.yulin.rider.core.designsystem.MtSectionTitle
import com.yulin.rider.core.designsystem.RiderDimens
import com.yulin.rider.core.designsystem.RiderTheme
import com.yulin.rider.core.designsystem.StatusTone
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LocationConsentUiState(
    val submitting: Boolean = false,
    val error: String? = null,
    val agreed: Boolean = false,
    val declined: Boolean = false,
)

internal class LocationConsentViewModel(
    private val repository: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(LocationConsentUiState())
    val state: StateFlow<LocationConsentUiState> = _state.asStateFlow()

    fun submit(agreed: Boolean) {
        if (_state.value.submitting) return
        _state.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.submitLocationConsent(agreed)) {
                is RiderResult.Success -> _state.update {
                    it.copy(submitting = false, agreed = agreed, declined = !agreed)
                }

                is RiderResult.Failure -> _state.update {
                    it.copy(submitting = false, error = result.message)
                }

                RiderResult.Loading -> Unit
            }
        }
    }
}

/**
 * 定位授权同意页(PIPL 单独同意)。
 *
 * 这一屏必须独立存在,不能和隐私政策、用户协议合并勾选 ——
 * 个人信息保护法对敏感个人信息(精确位置)要求单独同意,合并即无效。
 * 骑手拒绝后 App 仍可登录查看,只是无法上班接单,不做强制退出。
 */
@Composable
fun LocationConsentRoute(
    onAgreed: () -> Unit,
    onDeclined: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val repository = rememberAuthRepository()
    val viewModel: LocationConsentViewModel = viewModel { LocationConsentViewModel(repository) }
    val state by viewModel.state.collectAsState()

    LaunchedEffect(state.agreed, state.declined) {
        when {
            state.agreed -> onAgreed()
            state.declined -> onDeclined()
        }
    }

    LocationConsentScreen(
        state = state,
        onSubmit = viewModel::submit,
        modifier = modifier,
    )
}

@Composable
internal fun LocationConsentScreen(
    state: LocationConsentUiState,
    onSubmit: (agreed: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    MtScaffold(
        title = "位置信息授权",
        subtitle = "精确位置需要单独同意",
        modifier = modifier,
        bottomBar = {
            MtBottomActionBar(hint = state.error, hintTone = StatusTone.DANGER) {
                MtPrimaryButton(
                    text = if (state.submitting) "提交中…" else "同意并继续",
                    action = MtAction.ACCEPT,
                    modifier = Modifier.weight(1f),
                    enabled = !state.submitting,
                    disabledReason = "正在提交授权选择",
                    onClick = { onSubmit(true) },
                )
            }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = FreshSpacing.Md),
        ) {
            MtInfoBar(
                text = "只在上班期间采集，用于派单、导航和争议回查；下班立即停止。",
                icon = FreshIconType.LOCATION,
            )
            Spacer(Modifier.height(FreshSpacing.Xs))

            Column(
                modifier = Modifier.padding(horizontal = FreshSpacing.Sm),
                verticalArrangement = Arrangement.spacedBy(FreshSpacing.Xs),
            ) {
                ConsentCard(
                    "我们收集什么",
                    "你的精确位置（经纬度）、定位精度、速度、方向与电量。",
                )
                ConsentCard(
                    "什么时候收集",
                    "只在你点击「上班」之后、点击「下班」之前采集。下班即停止，App 退到后台也不会在下班时段采集。",
                )
                ConsentCard(
                    "用来做什么",
                    "派顺路订单并计算送达时间\n让顾客看到配送距离\n导航到下一站\n发生时间或里程争议时回查轨迹",
                )
                ConsentCard(
                    "谁能看到",
                    "店长在调度台可以看到你的实时位置；顾客只能在自己订单配送途中看到你的当前位置，" +
                        "看不到历史轨迹，订单完成后立即不可见。",
                )
                ConsentCard(
                    "怎么撤回",
                    "在「我的 - 设置」里可以随时撤回。撤回后服务端立即停止接收你的位置，同时你将无法上班接单。",
                )
            }

            Spacer(Modifier.height(FreshSpacing.Sm))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = { onSubmit(false) },
                    enabled = !state.submitting,
                    modifier = Modifier.height(RiderDimens.TouchTarget),
                ) {
                    Text(
                        text = "暂不同意（将无法上班接单）",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ConsentCard(title: String, body: String) {
    MtCard {
        MtSectionTitle(title)
        MtDivider()
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(FreshSpacing.Sm),
        )
    }
}

@Preview(name = "定位授权 · 默认", showBackground = true)
@Composable
private fun LocationConsentPreview() {
    RiderTheme { LocationConsentScreen(LocationConsentUiState(), {}) }
}

@Preview(name = "定位授权 · 提交错误", showBackground = true)
@Composable
private fun LocationConsentErrorPreview() {
    RiderTheme {
        LocationConsentScreen(
            LocationConsentUiState(error = "网络不可用，请稍后重试"),
            {},
        )
    }
}
