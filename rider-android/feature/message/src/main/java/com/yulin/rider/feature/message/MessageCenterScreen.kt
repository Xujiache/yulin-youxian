package com.yulin.rider.feature.message

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yulin.rider.core.common.RiderResult
import com.yulin.rider.core.common.RiderTime
import com.yulin.rider.core.designsystem.FreshError
import com.yulin.rider.core.designsystem.FreshIconType
import com.yulin.rider.core.designsystem.FreshLoading
import com.yulin.rider.core.designsystem.FreshSpacing
import com.yulin.rider.core.designsystem.MtAction
import com.yulin.rider.core.designsystem.MtCard
import com.yulin.rider.core.designsystem.MtDivider
import com.yulin.rider.core.designsystem.MtEmptyState
import com.yulin.rider.core.designsystem.MtPrimaryButton
import com.yulin.rider.core.designsystem.MtScaffold
import com.yulin.rider.core.designsystem.RiderColors
import com.yulin.rider.core.designsystem.RiderTheme
import com.yulin.rider.core.designsystem.tabularFigures
import com.yulin.rider.core.model.RiderMessage
import com.yulin.rider.core.network.RiderApis
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

data class MessageUiState(
    val messages: List<RiderMessage> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
)

class MessageViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(MessageUiState())
    val state: StateFlow<MessageUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            val apis = RiderApis.of(getApplication())
            _state.value = when (val result = apis.caller.result { apis.message.getMessages() }) {
                is RiderResult.Success -> MessageUiState(messages = result.data.items, loading = false)
                is RiderResult.Failure -> MessageUiState(loading = false, error = result.message)
                RiderResult.Loading -> _state.value
            }
        }
    }

    /** 打开即已读;需要确认的消息由骑手点「我知道了」单独 ack,留证用。 */
    fun markRead(message: RiderMessage) {
        if (message.read) return
        viewModelScope.launch {
            val apis = RiderApis.of(getApplication())
            if (apis.caller.resultOk { apis.message.markRead(message.id) } is RiderResult.Success) {
                updateLocal(message.id) { it.copy(readAt = RiderTime.nowIsoLocal()) }
            }
        }
    }

    fun ack(message: RiderMessage) {
        viewModelScope.launch {
            val apis = RiderApis.of(getApplication())
            if (apis.caller.resultOk { apis.message.ack(message.id) } is RiderResult.Success) {
                val now = RiderTime.nowIsoLocal()
                updateLocal(message.id) { it.copy(readAt = it.readAt ?: now, ackedAt = now, needAck = false) }
            }
        }
    }

    private fun updateLocal(id: Long, transform: (RiderMessage) -> RiderMessage) {
        _state.value = _state.value.copy(
            messages = _state.value.messages.map { if (it.id == id) transform(it) else it }
        )
    }
}

/** 消息中心(路由 message)。 */
@Composable
fun MessageCenterScreen(
    modifier: Modifier = Modifier,
    viewModel: MessageViewModel = viewModel(),
    onBack: () -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    MessageContent(
        state = state,
        modifier = modifier,
        onBack = onBack,
        onRetry = viewModel::load,
        onRead = viewModel::markRead,
        onAck = viewModel::ack,
    )
}

@Composable
private fun MessageContent(
    state: MessageUiState,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onRetry: () -> Unit = {},
    onRead: (RiderMessage) -> Unit = {},
    onAck: (RiderMessage) -> Unit = {},
) {
    MtScaffold(title = "消息中心", modifier = modifier, onBack = onBack) {
        when {
            state.loading -> FreshLoading(
                modifier = Modifier.fillMaxSize(),
                label = "正在同步消息",
            )

            state.error != null -> FreshError(
                message = state.error,
                modifier = Modifier.fillMaxSize(),
                onRetry = onRetry,
            )

            state.messages.isEmpty() -> MtEmptyState(
                title = "暂无消息",
                message = "新单与门店通知会显示在这里",
                icon = FreshIconType.MESSAGE,
                modifier = Modifier.fillMaxSize(),
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = FreshSpacing.Sm,
                    end = FreshSpacing.Sm,
                    top = FreshSpacing.Xs,
                    bottom = FreshSpacing.Md,
                ),
                verticalArrangement = Arrangement.spacedBy(FreshSpacing.Xs),
            ) {
                items(state.messages, key = { it.id }) { message ->
                    MessageRow(
                        message = message,
                        onRead = { onRead(message) },
                        onAck = { onAck(message) },
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageRow(message: RiderMessage, onRead: () -> Unit, onAck: () -> Unit) {
    MtCard(onClick = onRead) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = FreshSpacing.Sm,
                    end = FreshSpacing.Sm,
                    top = FreshSpacing.Sm,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FreshSpacing.Xs),
        ) {
            // 未读用一个小红点，不占一整枚标签的宽度
            if (!message.read) {
                Box(
                    Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(RiderColors.Danger)
                )
            }
            Text(
                text = message.title ?: "系统消息",
                style = MaterialTheme.typography.titleMedium,
                color = RiderColors.Ink,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = formatTime(message.createdAt) ?: "刚刚",
                style = MaterialTheme.typography.bodySmall.tabularFigures(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        message.content?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(
                    start = FreshSpacing.Sm,
                    end = FreshSpacing.Sm,
                    top = FreshSpacing.Xxs,
                    bottom = FreshSpacing.Sm,
                ),
            )
        }
        if (message.needAck && message.ackedAt.isNullOrBlank()) {
            MtDivider()
            Box(Modifier.padding(FreshSpacing.Sm)) {
                MtPrimaryButton(
                    text = "我知道了",
                    action = MtAction.ACCEPT,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onAck,
                )
            }
        }
    }
}

@Preview(name = "消息 · 列表", showBackground = true)
@Composable
private fun MessageListPreview() {
    RiderTheme {
        MessageContent(
            MessageUiState(
                messages = listOf(
                    RiderMessage(
                        id = 1,
                        title = "冷链订单即将超时",
                        content = "锦江花园订单剩余 8 分钟，请优先送达。",
                        priority = "HIGH",
                        needAck = true,
                        createdAt = "2026-08-12T04:12:00",
                    ),
                    RiderMessage(
                        id = 2,
                        title = "门店出货完成",
                        content = "W-0812-04 波次可以取货。",
                        readAt = "2026-08-12T04:10:00",
                        createdAt = "2026-08-12T04:08:00",
                    ),
                ),
                loading = false,
            )
        )
    }
}

@Preview(name = "消息 · 空态", showBackground = true)
@Composable
private fun MessageEmptyPreview() {
    RiderTheme { MessageContent(MessageUiState(loading = false)) }
}

@Preview(name = "消息 · 错误", showBackground = true)
@Composable
private fun MessageErrorPreview() {
    RiderTheme {
        MessageContent(MessageUiState(loading = false, error = "网络不可用"))
    }
}

private fun formatTime(value: String?): String? {
    if (value.isNullOrBlank()) return null
    return runCatching {
        LocalDateTime.parse(value.take(19)).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
    }.getOrNull()
}
