package com.yulin.rider.feature.auth

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yulin.rider.core.common.RiderResult
import com.yulin.rider.core.common.isChinaMobile
import com.yulin.rider.core.designsystem.BigActionButton
import com.yulin.rider.core.designsystem.FreshBanner
import com.yulin.rider.core.designsystem.FreshIcon
import com.yulin.rider.core.designsystem.FreshIconType
import com.yulin.rider.core.designsystem.FreshPanel
import com.yulin.rider.core.designsystem.FreshShell
import com.yulin.rider.core.designsystem.FreshSpacing
import com.yulin.rider.core.designsystem.FreshStatusBadge
import com.yulin.rider.core.designsystem.RiderDimens
import com.yulin.rider.core.designsystem.RiderTheme
import com.yulin.rider.core.designsystem.RiderTextField
import com.yulin.rider.core.designsystem.StatusTone
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LoginUiState(
    val submitting: Boolean = false,
    val error: String? = null,
    val mustChangePassword: Boolean = false,
    val locationConsentRequired: Boolean = false,
    val success: Boolean = false,
)

internal class LoginViewModel(
    private val repository: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    fun login(phone: String, password: String) {
        if (_state.value.submitting) return
        _state.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.login(phone.trim(), password)) {
                is RiderResult.Success -> _state.update {
                    it.copy(
                        submitting = false,
                        success = true,
                        mustChangePassword = result.data.mustChangePassword,
                        locationConsentRequired = result.data.locationConsentRequired,
                    )
                }

                is RiderResult.Failure -> _state.update {
                    it.copy(submitting = false, error = result.message)
                }

                RiderResult.Loading -> Unit
            }
        }
    }

    fun clearError() {
        if (_state.value.error != null) _state.update { it.copy(error = null) }
    }
}

/**
 * 登录页。
 * 登录成功后的去向:强制改密 > 定位同意 > 首页,顺序不能颠倒 ——
 * 未签定位同意时上班接口会直接返回 1004。
 */
@Composable
fun LoginRoute(
    onLoggedIn: () -> Unit,
    onNeedChangePassword: () -> Unit,
    onNeedLocationConsent: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val repository = rememberAuthRepository()
    val viewModel: LoginViewModel = viewModel { LoginViewModel(repository) }
    val state by viewModel.state.collectAsState()

    LaunchedEffect(state.success, state.mustChangePassword, state.locationConsentRequired) {
        if (!state.success) return@LaunchedEffect
        when {
            state.mustChangePassword -> onNeedChangePassword()
            state.locationConsentRequired -> onNeedLocationConsent()
            else -> onLoggedIn()
        }
    }

    LoginScreen(
        state = state,
        onSubmit = viewModel::login,
        onInputChanged = viewModel::clearError,
        modifier = modifier,
    )
}

@Composable
internal fun LoginScreen(
    state: LoginUiState,
    onSubmit: (phone: String, password: String) -> Unit,
    onInputChanged: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var phone by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }

    val phoneValid = phone.isChinaMobile()
    val canSubmit = phoneValid && password.length >= 6 && !state.submitting

    FreshShell(modifier = modifier) { insets ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(insets)
                .padding(horizontal = FreshSpacing.Md),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(FreshSpacing.Xxl))
            FreshIcon(
                type = FreshIconType.STORE,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                size = 56.dp,
            )
            Spacer(Modifier.height(FreshSpacing.Sm))
            Text(
                text = "禹邻优鲜",
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(FreshSpacing.Xs))
            FreshStatusBadge(
                text = "家庭生鲜配送运营中枢",
                tone = StatusTone.INFO,
                icon = FreshIconType.ROUTE,
            )
            Spacer(Modifier.height(FreshSpacing.Xs))
            Text(
                text = "强光可读 · 单手开工 · 离线可用",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(FreshSpacing.Xl))

            FreshPanel(
                title = "骑手登录",
                eyebrow = "今日配送入口",
                spineTone = StatusTone.SUCCESS,
            ) {
                RiderTextField(
                    value = phone,
                    onValueChange = { input ->
                        phone = input.filter { it.isDigit() }.take(11)
                        onInputChanged()
                    },
                    label = "手机号",
                    placeholder = "请输入 11 位手机号",
                    leadingIcon = FreshIconType.PHONE,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Phone,
                        imeAction = ImeAction.Next,
                    ),
                    isError = phone.isNotEmpty() && !phoneValid,
                    supportingText = if (phone.isNotEmpty() && !phoneValid) "手机号应为 11 位数字" else null,
                )

                RiderTextField(
                    value = password,
                    onValueChange = {
                        password = it.take(32)
                        onInputChanged()
                    },
                    label = "密码",
                    placeholder = "请输入密码",
                    leadingIcon = FreshIconType.LOCK,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    isPassword = true,
                    passwordVisible = passwordVisible,
                    onTogglePasswordVisibility = { passwordVisible = !passwordVisible },
                )

                if (state.error != null) {
                    FreshBanner(
                        text = state.error,
                        tone = StatusTone.DANGER,
                        icon = FreshIconType.ERROR,
                    )
                }

                BigActionButton(
                    text = if (state.submitting) "正在登录…" else "登录并开始接单",
                    enabled = canSubmit,
                    disabledReason = when {
                        state.submitting -> "正在提交登录信息"
                        !phoneValid -> "请输入正确的手机号"
                        password.length < 6 -> "密码至少 6 位"
                        else -> null
                    },
                    tone = StatusTone.NORMAL,
                    icon = FreshIconType.ROUTE,
                    onClick = { onSubmit(phone, password) },
                )
            }

            Spacer(Modifier.height(FreshSpacing.Md))
            Text(
                text = "忘记密码请联系店长重置",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(FreshSpacing.Xxl))
        }
    }
}

@Preview(name = "登录 · 默认", showBackground = true)
@Composable
private fun LoginPreview() {
    RiderTheme { LoginScreen(LoginUiState(), { _, _ -> }, {}) }
}

@Preview(name = "登录 · 错误", showBackground = true)
@Composable
private fun LoginErrorPreview() {
    RiderTheme {
        LoginScreen(
            state = LoginUiState(error = "账号或密码不正确，请重新输入"),
            onSubmit = { _, _ -> },
            onInputChanged = {},
        )
    }
}

@Preview(name = "登录 · 提交中", showBackground = true)
@Composable
private fun LoginSubmittingPreview() {
    RiderTheme { LoginScreen(LoginUiState(submitting = true), { _, _ -> }, {}) }
}
