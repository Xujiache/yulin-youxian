package com.yulin.rider.core.designsystem

import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.foundation.layout.padding

/**
 * 大输入框。骑手戴手套单手输入,默认高度对齐主按钮,标签与错误提示都用正文字号。
 * 不在冻结契约内,但 A8/A9 可直接复用(核销码、异常描述等都要输入)。
 */
@Composable
fun RiderTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    enabled: Boolean = true,
    leadingIcon: FreshIconType? = null,
    isError: Boolean = false,
    supportingText: String? = null,
    singleLine: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    isPassword: Boolean = false,
    passwordVisible: Boolean = false,
    onTogglePasswordVisibility: (() -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = singleLine,
        isError = isError,
        label = { Text(text = label, style = MaterialTheme.typography.bodyMedium) },
        placeholder = placeholder?.let {
            { Text(text = it, style = MaterialTheme.typography.bodyMedium) }
        },
        supportingText = supportingText?.let {
            { Text(text = it, style = MaterialTheme.typography.bodySmall) }
        },
        leadingIcon = leadingIcon?.let { icon ->
            {
                FreshIcon(
                    type = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        },
        trailingIcon = if (isPassword && onTogglePasswordVisibility != null) {
            {
                TextButton(onClick = onTogglePasswordVisibility) {
                    Text(
                        text = if (passwordVisible) "隐藏" else "显示",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        } else {
            null
        },
        visualTransformation = if (isPassword && !passwordVisible) {
            PasswordVisualTransformation()
        } else {
            VisualTransformation.None
        },
        keyboardOptions = keyboardOptions,
        textStyle = MaterialTheme.typography.titleMedium,
        shape = RoundedCornerShape(RiderDimens.ControlCorner),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            errorBorderColor = RiderColors.Danger,
        ),
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = riderControlHeight(RiderDimens.BigButtonHeight)),
    )
}

@Preview(name = "RiderTextField", showBackground = true)
@Composable
private fun RiderTextFieldPreview() {
    RiderTheme {
        RiderTextField(
            value = "13800000000",
            onValueChange = {},
            label = "手机号",
            leadingIcon = FreshIconType.PHONE,
            modifier = Modifier.padding(FreshSpacing.Md),
        )
    }
}
