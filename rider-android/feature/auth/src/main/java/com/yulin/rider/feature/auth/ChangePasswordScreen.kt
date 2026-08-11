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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yulin.rider.core.common.RiderResult
import com.yulin.rider.core.common.isValidRiderPassword
import com.yulin.rider.core.designsystem.BigActionButton
import com.yulin.rider.core.designsystem.FreshBanner
import com.yulin.rider.core.designsystem.FreshIconType
import com.yulin.rider.core.designsystem.FreshPanel
import com.yulin.rider.core.designsystem.FreshSpacing
import com.yulin.rider.core.designsystem.FreshStackScaffold
import com.yulin.rider.core.designsystem.RiderDimens
import com.yulin.rider.core.designsystem.RiderTextField
import com.yulin.rider.core.designsystem.RiderTheme
import com.yulin.rider.core.designsystem.SectionCard
import com.yulin.rider.core.designsystem.StatusTone
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChangePasswordUiState(
    val submitting: Boolean = false,
    val error: String? = null,
    val success: Boolean = false,
)

internal class ChangePasswordViewModel(
    private val repository: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ChangePasswordUiState())
    val state: StateFlow<ChangePasswordUiState> = _state.asStateFlow()

    fun submit(oldPassword: String, newPassword: String) {
        if (_state.value.submitting) return
        _state.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.changePassword(oldPassword, newPassword)) {
                is RiderResult.Success -> _state.update { it.copy(submitting = false, success = true) }
                is RiderResult.Failure -> _state.update { it.copy(submitting = false, error = result.message) }
                RiderResult.Loading -> Unit
            }
        }
    }

    fun clearError() {
        if (_state.value.error != null) _state.update { it.copy(error = null) }
    }
}

/**
 * 强制改密页。首次登录必过,[forced] 为 true 时不提供返回入口 ——
 * 初始密码是店长在后台明文看到过的,不改完不能上路。
 */
@Composable
fun ChangePasswordRoute(
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    forced: Boolean = true,
) {
    val repository = rememberAuthRepository()
    val viewModel: ChangePasswordViewModel = viewModel { ChangePasswordViewModel(repository) }
    val state by viewModel.state.collectAsState()

    LaunchedEffect(state.success) {
        if (state.success) onDone()
    }

    ChangePasswordScreen(
        state = state,
        forced = forced,
        onSubmit = viewModel::submit,
        onInputChanged = viewModel::clearError,
        modifier = modifier,
    )
}

@Composable
internal fun ChangePasswordScreen(
    state: ChangePasswordUiState,
    forced: Boolean,
    onSubmit: (oldPassword: String, newPassword: String) -> Unit,
    onInputChanged: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var oldPassword by rememberSaveable { mutableStateOf("") }
    var newPassword by rememberSaveable { mutableStateOf("") }
    var confirmPassword by rememberSaveable { mutableStateOf("") }
    var visible by rememberSaveable { mutableStateOf(false) }

    val newValid = newPassword.isValidRiderPassword()
    val confirmMatched = confirmPassword.isNotEmpty() && confirmPassword == newPassword
    val canSubmit = oldPassword.isNotEmpty() && newValid && confirmMatched &&
        newPassword != oldPassword && !state.submitting

    FreshStackScaffold(
        title = if (forced) "设置你的专属密码" else "修改密码",
        subtitle = if (forced) "首次登录安全步骤" else "账号与设备安全",
        showBack = !forced,
        modifier = modifier,
    ) { insets ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(insets)
                .padding(FreshSpacing.Md),
        ) {
            FreshBanner(
                text = if (forced) {
                    "初始密码由店长设置，为了你的账号安全，第一次登录必须改成只有你知道的密码。"
                } else {
                    "修改后需要用新密码重新登录其他设备。"
                },
                tone = StatusTone.INFO,
                icon = FreshIconType.LOCK,
            )
            Spacer(Modifier.height(FreshSpacing.Md))

            FreshPanel(
                title = "验证并更新",
                eyebrow = "密码安全",
                spineTone = StatusTone.SUCCESS,
            ) {
                RiderTextField(
                    value = oldPassword,
                    onValueChange = { oldPassword = it.take(32); onInputChanged() },
                    label = "当前密码",
                    leadingIcon = FreshIconType.LOCK,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Next,
                    ),
                    isPassword = true,
                    passwordVisible = visible,
                    onTogglePasswordVisibility = { visible = !visible },
                )

                RiderTextField(
                    value = newPassword,
                    onValueChange = { newPassword = it.take(32); onInputChanged() },
                    label = "新密码",
                    placeholder = "8-32 位，需含字母和数字",
                    leadingIcon = FreshIconType.LOCK,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Next,
                    ),
                    isPassword = true,
                    passwordVisible = visible,
                    onTogglePasswordVisibility = { visible = !visible },
                    isError = newPassword.isNotEmpty() && !newValid,
                    supportingText = when {
                        newPassword.isNotEmpty() && !newValid -> "需要 8-32 位，且同时包含字母和数字"
                        newPassword.isNotEmpty() && newPassword == oldPassword -> "新密码不能和当前密码相同"
                        else -> null
                    },
                )

                RiderTextField(
                    value = confirmPassword,
                    onValueChange = { confirmPassword = it.take(32); onInputChanged() },
                    label = "确认新密码",
                    leadingIcon = FreshIconType.CHECK,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    isPassword = true,
                    passwordVisible = visible,
                    onTogglePasswordVisibility = { visible = !visible },
                    isError = confirmPassword.isNotEmpty() && !confirmMatched,
                    supportingText = if (confirmPassword.isNotEmpty() && !confirmMatched) "两次输入不一致" else null,
                )
            }

            Spacer(Modifier.height(FreshSpacing.Sm))

            FreshPanel(title = "密码要求", spineTone = StatusTone.INFO) {
                Text(
                    text = "8 到 32 位 · 同时包含字母和数字\n不要使用生日、手机号等容易被猜到的组合",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(FreshSpacing.Md))

            if (state.error != null) {
                FreshBanner(
                    text = state.error,
                    tone = StatusTone.DANGER,
                )
                Spacer(Modifier.height(FreshSpacing.Sm))
            }

            BigActionButton(
                text = if (state.submitting) "提交中…" else "确认修改",
                enabled = canSubmit,
                disabledReason = "请按要求填写并确认新密码",
                tone = StatusTone.NORMAL,
                icon = FreshIconType.CHECK,
                onClick = { onSubmit(oldPassword, newPassword) },
            )

            Spacer(Modifier.height(FreshSpacing.Xxl))
        }
    }
}

@Preview(name = "改密 · 首次登录", showBackground = true)
@Composable
private fun ChangePasswordForcedPreview() {
    RiderTheme {
        ChangePasswordScreen(ChangePasswordUiState(), true, { _, _ -> }, {})
    }
}

@Preview(name = "改密 · 错误", showBackground = true)
@Composable
private fun ChangePasswordErrorPreview() {
    RiderTheme {
        ChangePasswordScreen(
            state = ChangePasswordUiState(error = "当前密码不正确"),
            forced = false,
            onSubmit = { _, _ -> },
            onInputChanged = {},
        )
    }
}
