<template>
  <div
    class="rider-card"
    :class="{
      'rider-card--stale': rider.locationStale,
      'rider-card--paused': paused,
      'rider-card--drop': dropHover,
      'rider-card--reject': dropHover && Boolean(blockReason)
    }"
    @dragover="onDragOver"
    @dragenter.prevent="onDragEnter"
    @dragleave="onDragLeave"
    @drop="onDrop"
  >
    <div class="rider-card__head">
      <ElAvatar :size="34" :src="avatar">{{ rider.name?.slice(0, 1) || '骑' }}</ElAvatar>
      <div class="rider-card__title">
        <strong>{{ rider.name }}</strong>
        <span>{{ rider.riderNo }}</span>
      </div>
      <div class="rider-card__badges">
        <ElTag size="small" :type="workStatusTag(rider.workStatus)" effect="light">
          {{ workStatusText(rider.workStatus) }} {{ humanDuration(rider.onDutySeconds) }}
        </ElTag>
        <ElTag v-if="rider.batteryLevel !== null" size="small" type="info" effect="plain">
          电量 {{ rider.batteryLevel }}%
        </ElTag>
        <ElTag v-if="rider.locationStale" size="small" type="danger" effect="dark">定位异常</ElTag>
      </div>
    </div>

    <div class="rider-card__load">
      <div class="rider-card__load-row">
        <span>负载</span>
        <ElProgress
          :percentage="loadPercent(rider)"
          :stroke-width="8"
          :show-text="false"
          :status="loadPercent(rider) >= 100 ? 'exception' : undefined"
          class="rider-card__bar"
        />
        <b>{{ rider.currentTaskCount }}/{{ rider.maxConcurrentTask }}</b>
      </div>
      <div class="rider-card__load-row">
        <span>载重</span>
        <ElProgress
          :percentage="weightPercent(rider)"
          :stroke-width="8"
          :show-text="false"
          :status="weightPercent(rider) >= 100 ? 'exception' : undefined"
          class="rider-card__bar"
        />
        <b>
          {{ Number(rider.currentWeightKg || 0).toFixed(1) }}/{{
            Number(rider.capacityWeightKg || 0).toFixed(0)
          }}kg
        </b>
      </div>
    </div>

    <div class="rider-card__meta">
      <span v-if="wave">
        当前波次 {{ wave.waveNo }} · 第 {{ Math.min(wave.completedCount + 1, wave.taskCount) }}/{{
          wave.taskCount
        }}
        站
      </span>
      <span v-else-if="rider.currentTaskCount > 0">在途 {{ rider.currentTaskCount }} 单</span>
      <span v-else>暂无在途任务</span>
      <span v-if="rider.planReturnAt">预计 {{ clockText(rider.planReturnAt) }} 返店</span>
    </div>

    <div class="rider-card__meta">
      <span>今日 {{ rider.todayDeliveredCount }} 单</span>
      <span>准时 {{ percent(rider.todayOnTimeRate, 0) }}</span>
      <span>服务分 {{ rider.serviceScore }}</span>
      <ElTag v-if="rider.probation" size="small" type="warning" effect="plain">试用期</ElTag>
    </div>

    <div v-if="rider.locationStale" class="rider-card__alert rider-card__alert--stale">
      定位已陈旧（最后上报 {{ clockText(rider.locatedAt) }}），地图位置仅供参考
    </div>

    <div v-if="paused" class="rider-card__alert rider-card__alert--paused">
      {{ fatigueText(rider.fatigueLevel) }}
      <template v-if="rider.dispatchPausedUntil">
        ，{{ clockText(rider.dispatchPausedUntil) }} 前不派单
      </template>
      （合规要求，不可绕过）
    </div>

    <div class="rider-card__actions">
      <!-- 疲劳停派中不渲染派单入口：合规硬门禁 -->
      <ElButton
        v-if="!paused"
        size="small"
        type="primary"
        plain
        :disabled="Number(rider.loadRatio || 0) >= 1"
        @click="emit('dispatch', rider)"
      >
        派单
      </ElButton>
      <ElButton size="small" @click="emit('track', rider)">查看轨迹</ElButton>
      <ElButton size="small" @click="emit('message', rider)">发消息</ElButton>
      <ElButton size="small" text type="danger" @click="emit('force-off', rider)"
        >强制下班</ElButton
      >
    </div>
  </div>
</template>

<script setup lang="ts">
  import type { BoardWaveBrief, RiderBoardCard } from '@/api/delivery'
  import { resolveFreshAssetUrl } from '@/utils/fresh-assets'
  import {
    clockText,
    fatigueText,
    humanDuration,
    isFatiguePaused,
    loadPercent,
    percent,
    riderBlockReason,
    weightPercent,
    workStatusTag,
    workStatusText
  } from '../utils'

  defineOptions({ name: 'RiderBoardCard' })

  const props = withDefaults(
    defineProps<{
      rider: RiderBoardCard
      now: number
      wave?: BoardWaveBrief | null
      /** 是否有任务正在拖拽（用于高亮可放置区域） */
      dragActive?: boolean
    }>(),
    { wave: null, dragActive: false }
  )

  const emit = defineEmits<{
    (event: 'dispatch' | 'track' | 'message' | 'force-off', rider: RiderBoardCard): void
    (event: 'drop-task', payload: { riderId: number; taskId: number }): void
    (event: 'drop-slot', payload: { riderId: number; slotKey: string }): void
  }>()

  const dropHover = ref(false)

  const avatar = computed(() => resolveFreshAssetUrl(props.rider.avatarUrl))
  const paused = computed(() => isFatiguePaused(props.rider, props.now))
  const blockReason = computed(() => riderBlockReason(props.rider, props.now))

  const onDragOver = (event: DragEvent) => {
    if (!props.dragActive) return
    event.preventDefault()
    if (event.dataTransfer) {
      event.dataTransfer.dropEffect = blockReason.value ? 'none' : 'move'
    }
  }

  const onDragEnter = () => {
    if (props.dragActive) dropHover.value = true
  }

  const onDragLeave = () => {
    dropHover.value = false
  }

  const onDrop = (event: DragEvent) => {
    event.preventDefault()
    dropHover.value = false
    const raw = event.dataTransfer?.getData('text/plain') || ''
    if (raw.startsWith('slot:')) {
      emit('drop-slot', { riderId: props.rider.riderId, slotKey: raw.slice(5) })
      return
    }
    const taskId = Number(raw.startsWith('task:') ? raw.slice(5) : raw)
    if (!taskId) return
    emit('drop-task', { riderId: props.rider.riderId, taskId })
  }
</script>

<style scoped lang="scss">
  .rider-card {
    display: grid;
    gap: 8px;
    padding: 12px;
    border: 1px solid var(--art-border-color);
    border-left: 3px solid var(--el-color-primary-light-5);
    border-radius: 10px;
    background: var(--art-main-bg-color);
    transition:
      border-color 0.2s ease,
      box-shadow 0.2s ease;

    &--stale {
      border-color: var(--el-color-danger-light-5);
      border-left-color: var(--el-color-danger);
      background: color-mix(in srgb, var(--el-color-danger) 4%, var(--art-main-bg-color));
    }

    &--paused {
      border-left-color: var(--el-color-warning);
    }

    &--drop {
      border-color: var(--el-color-primary);
      box-shadow: 0 0 0 3px color-mix(in srgb, var(--el-color-primary) 18%, transparent);
    }

    &--reject {
      border-color: var(--el-color-danger);
      box-shadow: 0 0 0 3px color-mix(in srgb, var(--el-color-danger) 18%, transparent);
    }
  }

  .rider-card__head {
    display: flex;
    align-items: center;
    gap: 8px;
  }

  .rider-card__title {
    display: grid;
    min-width: 0;

    strong {
      color: var(--art-gray-900);
      font-size: 14px;
    }

    span {
      color: var(--art-gray-600);
      font-size: 12px;
    }
  }

  .rider-card__badges {
    display: flex;
    flex-wrap: wrap;
    justify-content: flex-end;
    margin-left: auto;
    gap: 4px;
  }

  .rider-card__load {
    display: grid;
    gap: 6px;
  }

  .rider-card__load-row {
    display: flex;
    align-items: center;
    gap: 8px;
    color: var(--art-gray-600);
    font-size: 12px;

    b {
      min-width: 74px;
      color: var(--art-gray-800);
      text-align: right;
      font-variant-numeric: tabular-nums;
    }
  }

  .rider-card__bar {
    flex: 1;
    min-width: 0;
  }

  .rider-card__meta {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 4px 10px;
    color: var(--art-gray-600);
    font-size: 12px;
  }

  .rider-card__alert {
    padding: 6px 8px;
    border-radius: 6px;
    font-size: 12px;
    line-height: 17px;

    &--paused {
      color: #a15c00;
      background: rgb(230 162 60 / 14%);
    }

    &--stale {
      color: var(--el-color-danger);
      background: rgb(245 108 108 / 12%);
    }
  }

  .rider-card__actions {
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
</style>
