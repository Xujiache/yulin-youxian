<template>
  <div
    class="exception-card"
    :class="`exception-card--${(exception.severity || 'NORMAL').toLowerCase()}`"
  >
    <div class="exception-card__head">
      <ElTag size="small" :type="severityTag(exception.severity)" effect="dark">
        {{ severityText(exception.severity) }}
      </ElTag>
      <strong>{{ exceptionTypeText(exception.exceptionType) }}</strong>
      <ElTag size="small" :type="exceptionStatusTag(exception.status)" effect="light">
        {{ exceptionStatusText(exception.status) }}
      </ElTag>
      <span class="exception-card__time">{{ dateTimeText(exception.createdAt) }}</span>
    </div>

    <div class="exception-card__meta">
      <span>{{ exception.taskId ? `任务 ID ${exception.taskId}` : '未关联配送任务' }}</span>
    </div>

    <div v-if="exception.description" class="exception-card__desc">
      {{ exception.description }}
    </div>

    <div v-if="holdText" class="exception-card__hold">{{ holdText }}</div>

    <div class="exception-card__actions">
      <ElButton size="small" type="primary" @click="emit('handle', exception)">处理</ElButton>
      <ElButton
        size="small"
        text
        type="primary"
        :disabled="!exception.taskId"
        @click="emit('view-task', exception)"
      >
        查看任务
      </ElButton>
    </div>
  </div>
</template>

<script setup lang="ts">
  import type { DeliveryExceptionItem } from '@/api/delivery'
  import {
    dateTimeText,
    durationText,
    exceptionStatusTag,
    exceptionStatusText,
    exceptionTypeText,
    secondsUntil,
    severityTag,
    severityText
  } from '../utils'

  defineOptions({ name: 'ExceptionCard' })

  const props = defineProps<{
    exception: DeliveryExceptionItem
    now: number
  }>()

  const emit = defineEmits<{
    (event: 'handle' | 'view-task', exception: DeliveryExceptionItem): void
  }>()

  const holdText = computed(() => {
    const remain = secondsUntil(props.exception.holdUntilAt, props.now)
    if (remain === null) return ''
    return remain > 0 ? `挂起中，${durationText(remain)} 后到期` : '挂起已到期，请尽快处理'
  })
</script>

<style scoped lang="scss">
  .exception-card {
    display: grid;
    gap: 8px;
    padding: 12px;
    border: 1px solid var(--art-border-color);
    border-left: 3px solid var(--el-color-info);
    border-radius: 10px;
    background: var(--art-main-bg-color);

    &--high {
      border-left-color: var(--el-color-warning);
    }

    &--urgent {
      border-left-color: var(--el-color-danger);
      background: color-mix(in srgb, var(--el-color-danger) 4%, var(--art-main-bg-color));
    }
  }

  .exception-card__head {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 6px;

    strong {
      color: var(--art-gray-900);
      font-size: 13px;
    }
  }

  .exception-card__time {
    margin-left: auto;
    color: var(--art-gray-600);
    font-size: 12px;
  }

  .exception-card__meta {
    display: flex;
    flex-wrap: wrap;
    gap: 4px 10px;
    color: var(--art-gray-600);
    font-size: 12px;
  }

  .exception-card__desc {
    color: var(--art-gray-800);
    font-size: 13px;
    line-height: 18px;
    overflow-wrap: anywhere;
  }

  .exception-card__hold {
    color: var(--el-color-warning);
    font-size: 12px;
  }

  .exception-card__actions {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;

    :deep(.el-button + .el-button) {
      margin-left: 0;
    }
  }
</style>
