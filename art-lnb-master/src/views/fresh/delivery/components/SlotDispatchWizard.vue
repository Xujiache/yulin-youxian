<template>
  <ElDialog
    :model-value="modelValue"
    title="按时段发车"
    width="720px"
    append-to-body
    destroy-on-close
    @update:model-value="(value: boolean) => emit('update:modelValue', value)"
    @closed="reset"
  >
    <ElSteps :active="step" finish-status="success" align-center class="slot-wizard__steps">
      <ElStep title="选择时段" />
      <ElStep title="选择骑手" />
      <ElStep title="确认发车" />
    </ElSteps>

    <div v-show="step === 0" class="slot-wizard__pane">
      <ElEmpty v-if="groups.length === 0" description="没有待派时段" />
      <button
        v-for="group in groups"
        :key="group.key"
        type="button"
        class="slot-wizard__card"
        :class="{ 'slot-wizard__card--active': selectedKey === group.key }"
        @click="selectedKey = group.key"
      >
        <div class="slot-wizard__card-head">
          <strong>{{ group.title }}</strong>
          <ElTag v-if="relativeLabel(group)" size="small" type="success" effect="light">
            {{ relativeLabel(group) }}
          </ElTag>
        </div>
        <div class="slot-wizard__card-meta">
          {{ group.allTasks.length }} 单 · {{ group.weightText }}
        </div>
        <p class="slot-wizard__card-preview">{{ addressPreview(group) }}</p>
      </button>
    </div>

    <div v-show="step === 1" v-loading="loading" class="slot-wizard__pane">
      <ElAlert
        v-if="selectedGroup"
        class="slot-wizard__tip"
        type="info"
        :closable="false"
        show-icon
        :title="`将发「${selectedGroup.title}」，共 ${selectedGroup.allTasks.length} 单`"
      />
      <ElInput v-model="keyword" clearable placeholder="搜索骑手姓名或工号" />
      <ElEmpty v-if="visibleRiders.length === 0" description="没有符合条件的在岗骑手" />
      <div
        v-for="row in visibleRiders"
        :key="row.rider.riderId"
        class="slot-wizard__rider"
        :class="{
          'slot-wizard__rider--active': selectedRiderId === row.rider.riderId,
          'slot-wizard__rider--blocked': Boolean(row.blockReason)
        }"
        @click="selectRider(row)"
      >
        <div class="slot-wizard__rider-main">
          <strong>{{ row.rider.name }}</strong>
          <span>{{ row.rider.riderNo }}</span>
          <ElTag v-if="row.recommended" size="small" type="success">系统推荐</ElTag>
        </div>
        <div class="slot-wizard__rider-meta">
          <span>负载 {{ row.rider.currentTaskCount }}/{{ row.rider.maxConcurrentTask }}</span>
          <span>
            载重 {{ Number(row.rider.currentWeightKg || 0).toFixed(1) }}/{{
              Number(row.rider.capacityWeightKg || 0).toFixed(0)
            }}kg
          </span>
          <span>准时 {{ percent(row.rider.todayOnTimeRate, 0) }}</span>
          <span v-if="row.candidate">新增 {{ distanceText(row.candidate.addedDistanceMeters) }}</span>
        </div>
        <div class="slot-wizard__rider-score">
          <b v-if="row.candidate">{{ row.candidate.score.toFixed(2) }}</b>
          <span v-else>无评分</span>
        </div>
        <div v-if="row.blockReason || row.blockers.length > 0" class="slot-wizard__rider-block">
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

    <div v-show="step === 2" class="slot-wizard__pane">
      <ElAlert
        type="warning"
        :closable="false"
        show-icon
        :title="confirmTitle"
      />
      <ul v-if="selectedGroup" class="slot-wizard__tasks">
        <li v-for="task in selectedGroup.allTasks" :key="task.taskId">
          <strong>{{ task.taskNo }}</strong>
          <span>{{
            [task.areaLabel, task.buildingLabel, task.addressDetail].filter(Boolean).join(' ')
          }}</span>
        </li>
      </ul>
    </div>

    <template #footer>
      <ElButton @click="emit('update:modelValue', false)">取消</ElButton>
      <ElButton v-if="step > 0" @click="step -= 1">上一步</ElButton>
      <ElButton v-if="step < 2" type="primary" :disabled="!canNext" @click="goNext">
        下一步
      </ElButton>
      <ElButton
        v-else
        type="primary"
        :disabled="!selectedGroup || !selectedRider"
        :loading="submitting"
        @click="confirm"
      >
        确认发车
      </ElButton>
    </template>
  </ElDialog>
</template>

<script setup lang="ts">
  import { ElMessage } from 'element-plus'
  import type { AdminTaskCard, DispatchCandidate, RiderBoardCard } from '@/api/delivery'
  import { blockerText, distanceText, percent, relativeDayLabel, riderBlockReason } from '../utils'

  defineOptions({ name: 'SlotDispatchWizard' })

  export interface SlotDispatchGroup {
    key: string
    slotLabel: string
    deliveryDate: string
    title: string
    allTasks: AdminTaskCard[]
    tasks: AdminTaskCard[]
    weightText: string
  }

  const props = withDefaults(
    defineProps<{
      modelValue: boolean
      groups: SlotDispatchGroup[]
      riders: RiderBoardCard[]
      candidates?: DispatchCandidate[]
      recommendedRiderId?: number | null
      now: number
      initialKey?: string
      loading?: boolean
      submitting?: boolean
    }>(),
    {
      candidates: () => [],
      recommendedRiderId: null,
      initialKey: '',
      loading: false,
      submitting: false
    }
  )

  const emit = defineEmits<{
    (event: 'update:modelValue', value: boolean): void
    (event: 'select-slot', groupKey: string): void
    (event: 'confirm', payload: { groupKey: string; riderId: number }): void
  }>()

  interface RiderRow {
    rider: RiderBoardCard
    candidate: DispatchCandidate | null
    blockers: string[]
    blockReason: string | null
    recommended: boolean
  }

  const step = ref(0)
  const selectedKey = ref('')
  const selectedRiderId = ref<number | null>(null)
  const keyword = ref('')

  const selectedGroup = computed(
    () => props.groups.find((group) => group.key === selectedKey.value) || null
  )

  const selectedRider = computed(
    () => props.riders.find((rider) => rider.riderId === selectedRiderId.value) || null
  )

  const canNext = computed(() => {
    if (step.value === 0) return Boolean(selectedGroup.value)
    if (step.value === 1) return Boolean(selectedRider.value)
    return false
  })

  const confirmTitle = computed(() => {
    const group = selectedGroup.value
    const rider = selectedRider.value
    if (!group || !rider) return '请确认时段和骑手'
    return `把「${group.title}」共 ${group.allTasks.length} 单（${group.weightText}）发给 ${rider.name}？发车后骑手可一键接单并到店取货。`
  })

  const riderRows = computed<RiderRow[]>(() => {
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
    if (!text) return riderRows.value
    return riderRows.value.filter((row) =>
      `${row.rider.name} ${row.rider.riderNo}`.toLowerCase().includes(text)
    )
  })

  const relativeLabel = (group: SlotDispatchGroup) =>
    group.deliveryDate ? relativeDayLabel(group.deliveryDate, props.now) : ''

  const addressPreview = (group: SlotDispatchGroup) => {
    const addresses = group.allTasks.map((task) =>
      [task.areaLabel, task.buildingLabel, task.addressDetail].filter(Boolean).join(' ')
    )
    const shown = addresses.slice(0, 2).filter(Boolean)
    if (addresses.length > 2) shown.push(`另 ${addresses.length - 2} 单`)
    return shown.join('；') || '暂无地址'
  }

  const selectRider = (row: RiderRow) => {
    if (row.blockReason) {
      ElMessage.warning(`${row.rider.name}：${row.blockReason}`)
      return
    }
    if (row.blockers.length > 0) {
      ElMessage.warning(`${row.rider.name}：${row.blockers.map(blockerText).join('、')}`)
      return
    }
    selectedRiderId.value = row.rider.riderId
  }

  const goNext = () => {
    if (!canNext.value) return
    if (step.value === 0 && selectedKey.value) {
      emit('select-slot', selectedKey.value)
    }
    step.value += 1
  }

  const confirm = () => {
    if (!selectedKey.value || !selectedRiderId.value) return
    emit('confirm', { groupKey: selectedKey.value, riderId: selectedRiderId.value })
  }

  const reset = () => {
    step.value = 0
    selectedKey.value = ''
    selectedRiderId.value = null
    keyword.value = ''
  }

  watch(selectedKey, () => {
    selectedRiderId.value = null
  })

  watch(
    () => props.modelValue,
    (open) => {
      if (!open) return
      const preset = props.initialKey && props.groups.some((group) => group.key === props.initialKey)
        ? props.initialKey
        : props.groups.length === 1
          ? props.groups[0].key
          : ''
      selectedKey.value = preset
      selectedRiderId.value = null
      keyword.value = ''
      step.value = props.initialKey && preset ? 1 : 0
      if (step.value === 1 && preset) emit('select-slot', preset)
    }
  )

  watch(
    () => [props.modelValue, props.recommendedRiderId, props.candidates] as const,
    ([open, recommended]) => {
      if (!open || step.value !== 1 || !recommended || selectedRiderId.value) return
      const row = riderRows.value.find((item) => item.rider.riderId === recommended)
      if (!row || row.blockReason || row.blockers.length > 0) return
      selectedRiderId.value = recommended
    }
  )
</script>

<style scoped lang="scss">
  .slot-wizard__steps {
    margin-bottom: 18px;
  }

  .slot-wizard__pane {
    display: grid;
    gap: 10px;
    max-height: 46vh;
    overflow-y: auto;
  }

  .slot-wizard__tip {
    margin-bottom: 2px;
  }

  .slot-wizard__card {
    display: grid;
    gap: 4px;
    padding: 12px 14px;
    text-align: left;
    font: inherit;
    cursor: pointer;
    border: 1px solid var(--art-border-color);
    border-radius: 10px;
    background: var(--art-main-bg-color);
    appearance: none;

    &:hover {
      border-color: var(--el-color-primary-light-5);
    }

    &--active {
      border-color: var(--el-color-primary);
      background: var(--el-color-primary-light-9);
    }
  }

  .slot-wizard__card-head {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 8px;

    strong {
      color: var(--art-gray-900);
      font-size: 15px;
    }
  }

  .slot-wizard__card-meta,
  .slot-wizard__card-preview {
    margin: 0;
    color: var(--art-gray-600);
    font-size: 12px;
  }

  .slot-wizard__rider {
    display: grid;
    grid-template-columns: 1fr auto;
    gap: 6px 12px;
    padding: 10px 12px;
    border: 1px solid var(--art-border-color);
    border-radius: 10px;
    cursor: pointer;

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

  .slot-wizard__rider-main {
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

  .slot-wizard__rider-meta {
    display: flex;
    flex-wrap: wrap;
    gap: 4px 12px;
    color: var(--art-gray-600);
    font-size: 12px;
  }

  .slot-wizard__rider-score {
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

  .slot-wizard__rider-block {
    display: flex;
    flex-wrap: wrap;
    grid-column: 1 / -1;
    align-items: center;
    gap: 6px;
    color: var(--el-color-danger);
    font-size: 12px;
  }

  .slot-wizard__tasks {
    margin: 0;
    padding: 0;
    list-style: none;
    display: grid;
    gap: 8px;

    li {
      display: grid;
      gap: 2px;
      padding: 8px 10px;
      border: 1px solid var(--art-border-color);
      border-radius: 8px;
    }

    strong {
      color: var(--art-gray-900);
      font-size: 13px;
    }

    span {
      color: var(--art-gray-600);
      font-size: 12px;
    }
  }
</style>
