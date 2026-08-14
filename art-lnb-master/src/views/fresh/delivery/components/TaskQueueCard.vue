<template>
  <div
    class="queue-card"
    :class="{
      'queue-card--dragging': dragging,
      'queue-card--danger': urgent,
      'queue-card--warning': warning
    }"
    :draggable="mode === 'pending'"
    @dragstart="onDragStart"
    @dragend="onDragEnd"
  >
    <div class="queue-card__head">
      <strong class="queue-card__no">{{ task.taskNo }}</strong>
      <div class="queue-card__tags">
        <ElTag v-if="cold" size="small" type="primary" effect="dark">
          {{ task.coldChainText || '冷链' }}
        </ElTag>
        <ElTag v-if="far" size="small" type="warning" effect="light">远单</ElTag>
        <ElTag v-if="task.reassignCount > 0" size="small" type="info" effect="light">
          改派 {{ task.reassignCount }} 次
        </ElTag>
      </div>
      <span class="queue-card__timer" :class="timerClass">{{ timerText }}</span>
    </div>

    <div class="queue-card__address">
      {{ [task.areaLabel, task.buildingLabel, task.addressDetail].filter(Boolean).join(' ') }}
    </div>

    <div class="queue-card__meta">
      <span>{{ task.itemCount }} 件 · {{ Number(task.totalWeightKg || 0).toFixed(1) }} kg</span>
      <span>承诺 {{ clockText(task.promisedAt) }}</span>
      <span v-if="task.etaAt">ETA {{ clockText(task.etaAt) }}</span>
      <span v-if="task.slotLabel && !simple">{{ task.slotLabel }}</span>
    </div>

    <div v-if="mode === 'risk'" class="queue-card__meta">
      <ElTag size="small" :type="taskStatusTag(task.status)" effect="plain">
        {{ task.statusText || taskStatusText(task.status) }}
      </ElTag>
      <span>{{ task.riderName || '未指派' }}</span>
      <span v-if="task.seqNo && task.totalStops">第 {{ task.seqNo }}/{{ task.totalStops }} 站</span>
    </div>

    <div v-if="mode === 'pending' && recommendText && !simple" class="queue-card__suggest">
      {{ recommendText }}
    </div>

    <div
      class="queue-card__actions"
      :class="{ 'queue-card__actions--simple': simple }"
      @mousedown.stop
    >
      <template v-if="mode === 'pending' && simple">
        <ElButton size="small" type="primary" :loading="busy" @click="emit('assign-now', task)">
          立即派单
        </ElButton>
        <ElDropdown trigger="click" @command="onPendingCommand">
          <ElButton size="small">
            更多
            <ArtSvgIcon icon="ri:arrow-down-s-line" class="queue-card__more-icon" />
          </ElButton>
          <template #dropdown>
            <ElDropdownMenu>
              <ElDropdownItem command="assign-to">指派给…</ElDropdownItem>
              <ElDropdownItem command="view-suggest">查看建议</ElDropdownItem>
              <ElDropdownItem command="cancel" divided>取消任务</ElDropdownItem>
            </ElDropdownMenu>
          </template>
        </ElDropdown>
      </template>
      <template v-else-if="mode === 'pending'">
        <ElButton size="small" type="primary" :loading="busy" @click="emit('assign-now', task)">
          立即派单
        </ElButton>
        <ElButton size="small" @click="emit('assign-to', task)">指派给…</ElButton>
        <ElButton size="small" text type="primary" @click="emit('view-suggest', task)">
          查看建议
        </ElButton>
        <ElButton size="small" text type="danger" @click="emit('cancel', task)">取消</ElButton>
      </template>
      <template v-else>
        <ElButton size="small" type="warning" plain @click="emit('reassign', task)">改派</ElButton>
        <ElButton size="small" @click="emit('contact-rider', task)">联系骑手</ElButton>
        <ElButton size="small" @click="emit('contact-customer', task)">联系顾客</ElButton>
      </template>
    </div>
  </div>
</template>

<script setup lang="ts">
  import type { AdminTaskCard, DispatchSuggestion } from '@/api/delivery'
  import {
    clockText,
    durationText,
    isColdChain,
    isFarDelivery,
    secondsUntil,
    taskStatusTag,
    taskStatusText
  } from '../utils'

  defineOptions({ name: 'TaskQueueCard' })

  const props = withDefaults(
    defineProps<{
      task: AdminTaskCard
      mode: 'pending' | 'risk'
      /** 与服务端对齐后的当前时间戳 */
      now: number
      suggestion?: DispatchSuggestion | null
      busy?: boolean
      /** 调度台待派：主按钮 + 更多菜单，避免每张卡排四个按钮 */
      simple?: boolean
    }>(),
    { suggestion: null, busy: false, simple: false }
  )

  const emit = defineEmits<{
    (event: 'assign-now' | 'assign-to' | 'view-suggest' | 'cancel', task: AdminTaskCard): void
    (event: 'reassign' | 'contact-rider' | 'contact-customer', task: AdminTaskCard): void
    (event: 'drag-start' | 'drag-end', task: AdminTaskCard): void
  }>()

  const dragging = ref(false)

  const cold = computed(() => isColdChain(props.task))
  const far = computed(() => isFarDelivery(props.task))

  /** 待派看压单倒计时，在途看距离承诺时间的剩余时间 */
  const remainSeconds = computed(() => {
    if (props.mode === 'pending') return secondsUntil(props.task.holdUntilAt, props.now)
    const byPromise = secondsUntil(props.task.promisedAt, props.now)
    return byPromise ?? props.task.remainingSeconds
  })

  const urgent = computed(() => {
    if (props.mode === 'risk') {
      const remain = remainSeconds.value
      return remain !== null && remain < 300
    }
    return props.task.overtimeRisk === 'OVERTIME'
  })

  const warning = computed(() => props.mode === 'pending' && (remainSeconds.value ?? 1) <= 0)

  const timerText = computed(() => {
    const remain = remainSeconds.value
    if (remain === null) return props.mode === 'pending' ? '可立即派' : '—'
    if (props.mode === 'pending') {
      return remain > 0 ? `压单 ${durationText(remain)}` : '已到派单时间'
    }
    return remain > 0 ? `剩余 ${durationText(remain)}` : `超时 ${durationText(Math.abs(remain))}`
  })

  const timerClass = computed(() => ({
    'queue-card__timer--danger': urgent.value,
    'queue-card__timer--warning': warning.value && !urgent.value
  }))

  const recommendText = computed(() => {
    const candidate = props.suggestion?.candidates?.find(
      (item) => item.riderId === props.suggestion?.recommendedRiderId
    )
    if (candidate) {
      return `建议派给 ${candidate.riderName}（得分 ${candidate.score.toFixed(2)}，新增 ${Math.round(candidate.addedDistanceMeters)} m）`
    }
    if (props.task.riderName && props.task.dispatchScore !== null) {
      return `建议派给 ${props.task.riderName}（得分 ${Number(props.task.dispatchScore).toFixed(2)}）`
    }
    return ''
  })

  const onPendingCommand = (command: 'assign-to' | 'view-suggest' | 'cancel') => {
    emit(command, props.task)
  }

  const onDragStart = (event: DragEvent) => {
    if (props.mode !== 'pending') return
    dragging.value = true
    event.dataTransfer?.setData('text/plain', `task:${props.task.taskId}`)
    if (event.dataTransfer) event.dataTransfer.effectAllowed = 'move'
    emit('drag-start', props.task)
  }

  const onDragEnd = () => {
    dragging.value = false
    emit('drag-end', props.task)
  }
</script>

<style scoped lang="scss">
  .queue-card {
    display: grid;
    gap: 8px;
    padding: 12px;
    border: 1px solid var(--art-border-color);
    border-left: 3px solid var(--el-color-primary-light-5);
    border-radius: 10px;
    background: var(--art-main-bg-color);
    cursor: grab;
    transition:
      box-shadow 0.2s ease,
      transform 0.2s ease;

    &:hover {
      box-shadow: 0 8px 20px rgb(0 0 0 / 8%);
    }

    &--dragging {
      opacity: 0.5;
      transform: scale(0.98);
    }

    &--warning {
      border-left-color: var(--el-color-warning);
    }

    &--danger {
      border-left-color: var(--el-color-danger);
      background: color-mix(in srgb, var(--el-color-danger) 5%, var(--art-main-bg-color));
    }
  }

  .queue-card__head {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 6px;
  }

  .queue-card__no {
    color: var(--art-gray-900);
    font-size: 13px;
  }

  .queue-card__tags {
    display: flex;
    flex-wrap: wrap;
    gap: 4px;
  }

  .queue-card__timer {
    margin-left: auto;
    color: var(--art-gray-600);
    font-size: 12px;
    font-weight: 700;
    font-variant-numeric: tabular-nums;

    &--warning {
      color: var(--el-color-warning);
    }

    &--danger {
      color: var(--el-color-danger);
      animation: queue-blink 1.2s ease-in-out infinite;
    }
  }

  .queue-card__address {
    color: var(--art-gray-800);
    font-size: 13px;
    line-height: 18px;
    overflow-wrap: anywhere;
  }

  .queue-card__meta {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 4px 10px;
    color: var(--art-gray-600);
    font-size: 12px;
  }

  .queue-card__suggest {
    padding: 6px 8px;
    border-radius: 6px;
    color: #007a39;
    font-size: 12px;
    background: rgb(0 132 61 / 8%);
  }

  .queue-card__actions {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;

    :deep(.el-button + .el-button) {
      margin-left: 0;
    }

    :deep(.el-button) {
      padding-right: 8px;
      padding-left: 8px;
    }
  }

  .queue-card__more-icon {
    margin-left: 2px;
    font-size: 14px;
  }

  @keyframes queue-blink {
    50% {
      opacity: 0.35;
    }
  }
</style>
