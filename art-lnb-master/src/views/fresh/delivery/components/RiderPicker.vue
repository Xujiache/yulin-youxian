<template>
  <ElDialog
    :model-value="modelValue"
    :title="title"
    width="640px"
    append-to-body
    @update:model-value="(value: boolean) => emit('update:modelValue', value)"
    @closed="reset"
  >
    <div v-loading="loading" class="rider-picker">
      <ElInput v-model="keyword" clearable placeholder="搜索骑手姓名或工号" />

      <ElEmpty v-if="visibleRiders.length === 0" description="没有符合条件的在岗骑手" />

      <div
        v-for="row in visibleRiders"
        :key="row.rider.riderId"
        class="rider-picker__row"
        :class="{
          'rider-picker__row--active': selectedId === row.rider.riderId,
          'rider-picker__row--blocked': Boolean(row.blockReason)
        }"
        @click="select(row)"
      >
        <div class="rider-picker__main">
          <strong>{{ row.rider.name }}</strong>
          <span>{{ row.rider.riderNo }}</span>
          <ElTag v-if="row.recommended" size="small" type="success">系统推荐</ElTag>
        </div>
        <div class="rider-picker__meta">
          <span>负载 {{ row.rider.currentTaskCount }}/{{ row.rider.maxConcurrentTask }}</span>
          <span>
            载重 {{ Number(row.rider.currentWeightKg || 0).toFixed(1) }}/{{
              Number(row.rider.capacityWeightKg || 0).toFixed(0)
            }}kg
          </span>
          <span>准时 {{ percent(row.rider.todayOnTimeRate, 0) }}</span>
          <span v-if="row.candidate">
            新增 {{ distanceText(row.candidate.addedDistanceMeters) }}
          </span>
        </div>
        <div class="rider-picker__score">
          <b v-if="row.candidate">{{ row.candidate.score.toFixed(2) }}</b>
          <span v-else>无评分</span>
        </div>
        <div v-if="row.blockReason || row.blockers.length > 0" class="rider-picker__blockers">
          <ElTag
            v-for="blocker in row.blockers"
            :key="blocker"
            size="small"
            type="danger"
            effect="light"
          >
            {{ blockerText(blocker) }}
          </ElTag>
          <span v-if="row.blockReason">{{ row.blockReason }}</span>
        </div>
      </div>
    </div>

    <ElInput
      v-if="requireReason"
      v-model="reason"
      class="rider-picker__reason"
      type="textarea"
      :rows="2"
      maxlength="200"
      show-word-limit
      placeholder="请填写原因（会写入任务事件流水，可回查）"
    />

    <template #footer>
      <ElButton @click="emit('update:modelValue', false)">取消</ElButton>
      <ElButton type="primary" :disabled="!selectedId" :loading="submitting" @click="confirm">
        {{ confirmText }}
      </ElButton>
    </template>
  </ElDialog>
</template>

<script setup lang="ts">
  import { ElMessage } from 'element-plus'
  import type { DispatchCandidate, RiderBoardCard } from '@/api/delivery'
  import { blockerText, distanceText, percent, riderBlockReason } from '../utils'

  defineOptions({ name: 'RiderPicker' })

  const props = withDefaults(
    defineProps<{
      modelValue: boolean
      riders: RiderBoardCard[]
      candidates?: DispatchCandidate[]
      recommendedRiderId?: number | null
      now: number
      title?: string
      confirmText?: string
      requireReason?: boolean
      loading?: boolean
      submitting?: boolean
    }>(),
    {
      candidates: () => [],
      recommendedRiderId: null,
      title: '选择骑手',
      confirmText: '确认派单',
      requireReason: false,
      loading: false,
      submitting: false
    }
  )

  const emit = defineEmits<{
    (event: 'update:modelValue', value: boolean): void
    (event: 'confirm', payload: { riderId: number; reason: string }): void
  }>()

  interface PickerRow {
    rider: RiderBoardCard
    candidate: DispatchCandidate | null
    blockers: string[]
    blockReason: string | null
    recommended: boolean
  }

  const keyword = ref('')
  const reason = ref('')
  const selectedId = ref<number | null>(null)

  const rows = computed<PickerRow[]>(() => {
    const candidateMap = new Map(props.candidates.map((item) => [item.riderId, item]))
    return props.riders
      .map((rider) => {
        const candidate = candidateMap.get(rider.riderId) || null
        return {
          rider,
          candidate,
          blockers: candidate?.blockers || [],
          blockReason: riderBlockReason(rider, props.now),
          recommended: props.recommendedRiderId === rider.riderId
        }
      })
      .sort((a, b) => {
        const blockDiff = Number(Boolean(a.blockReason)) - Number(Boolean(b.blockReason))
        if (blockDiff !== 0) return blockDiff
        const scoreDiff = (b.candidate?.score || -1) - (a.candidate?.score || -1)
        if (scoreDiff !== 0) return scoreDiff
        return Number(a.rider.loadRatio || 0) - Number(b.rider.loadRatio || 0)
      })
  })

  const visibleRiders = computed(() => {
    const text = keyword.value.trim().toLowerCase()
    if (!text) return rows.value
    return rows.value.filter((row) =>
      `${row.rider.name} ${row.rider.riderNo}`.toLowerCase().includes(text)
    )
  })

  const select = (row: PickerRow) => {
    if (row.blockReason) {
      ElMessage.warning(`${row.rider.name}：${row.blockReason}`)
      return
    }
    if (row.blockers.length > 0) {
      ElMessage.warning(`${row.rider.name}：${row.blockers.map(blockerText).join('、')}`)
      return
    }
    selectedId.value = row.rider.riderId
  }

  const reset = () => {
    keyword.value = ''
    reason.value = ''
    selectedId.value = null
  }

  watch(
    () => [props.modelValue, props.recommendedRiderId, props.candidates, props.now] as const,
    ([open, recommended]) => {
      if (!open || !recommended) return
      const row = rows.value.find((item) => item.rider.riderId === recommended)
      if (!row || row.blockReason || row.blockers.length > 0) return
      selectedId.value = recommended
    }
  )

  const confirm = () => {
    if (!selectedId.value) return
    if (props.requireReason && !reason.value.trim()) {
      ElMessage.warning('请填写原因')
      return
    }
    emit('confirm', { riderId: selectedId.value, reason: reason.value.trim() })
  }
</script>

<style scoped lang="scss">
  .rider-picker {
    display: grid;
    gap: 10px;
    max-height: 46vh;
    overflow-y: auto;
  }

  .rider-picker__row {
    display: grid;
    grid-template-columns: 1fr auto;
    gap: 6px 12px;
    padding: 10px 12px;
    border: 1px solid var(--art-border-color);
    border-radius: 10px;
    cursor: pointer;
    transition: border-color 0.2s ease;

    &:hover {
      border-color: var(--el-color-primary-light-5);
    }

    &--active {
      border-color: var(--el-color-primary);
      background: var(--el-color-primary-light-9);
    }

    &--blocked {
      cursor: not-allowed;
      opacity: 0.65;
    }
  }

  .rider-picker__main {
    display: flex;
    align-items: center;
    gap: 8px;

    strong {
      color: var(--art-gray-900);
    }

    span {
      color: var(--art-gray-600);
      font-size: 12px;
    }
  }

  .rider-picker__score {
    grid-row: span 2;
    align-self: center;
    color: #00843d;
    text-align: right;
    font-size: 18px;
    font-weight: 700;

    span {
      color: var(--art-gray-500);
      font-size: 12px;
      font-weight: 400;
    }
  }

  .rider-picker__meta {
    display: flex;
    flex-wrap: wrap;
    gap: 4px 12px;
    color: var(--art-gray-600);
    font-size: 12px;
  }

  .rider-picker__blockers {
    display: flex;
    flex-wrap: wrap;
    grid-column: 1 / -1;
    align-items: center;
    gap: 6px;
    color: var(--el-color-danger);
    font-size: 12px;
  }

  .rider-picker__reason {
    margin-top: 12px;
  }
</style>
