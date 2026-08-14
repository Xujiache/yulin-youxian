<template>
  <div v-loading="loading" class="fresh-page">
    <div class="fresh-page__head">
      <div>
        <h1 class="fresh-page__title">
          波次详情
          <span v-if="wave">· {{ wave.waveNo }}</span>
        </h1>
        <p class="fresh-page__desc">站点列表可拖拽重排，右侧地图叠加规划路线与实际轨迹。</p>
      </div>
      <div class="fresh-toolbar__right">
        <ElButton @click="goBack">返回列表</ElButton>
        <ElButton :loading="replanning" @click="handleReplan">重新规划</ElButton>
        <ElButton
          type="primary"
          :disabled="!sequenceDirty"
          :loading="savingSequence"
          @click="saveSequence"
        >
          保存站点顺序
        </ElButton>
      </div>
    </div>

    <div class="wave-summary">
      <div v-for="item in summaryItems" :key="item.label" class="metric-card wave-summary__item">
        <div class="metric-card__label">{{ item.label }}</div>
        <div class="wave-summary__value">{{ item.value }}</div>
      </div>
    </div>

    <div class="wave-body">
      <ElCard class="fresh-card" shadow="never">
        <template #header>
          <div class="fresh-toolbar">
            <strong>站点顺序</strong>
            <span class="muted">拖拽卡片或使用上下按钮调整，保存后会重算 ETA</span>
          </div>
        </template>
        <ElEmpty v-if="stops.length === 0" description="该波次还没有站点" />
        <div class="stop-list">
          <div
            v-for="(stop, index) in stops"
            :key="stop.taskId"
            class="stop-item"
            :class="{ 'stop-item--dragging': dragIndex === index }"
            draggable="true"
            @dragstart="dragIndex = index"
            @dragover.prevent="onDragOver(index)"
            @dragend="dragIndex = null"
            @drop.prevent="dragIndex = null"
          >
            <span class="stop-item__seq">{{ index + 1 }}</span>
            <div class="stop-item__main">
              <strong>任务 ID {{ stop.taskId }}</strong>
              <span class="muted">
                段距离 {{ distanceText(stop.legDistanceMeters) }} · 段用时
                {{ humanDuration(stop.legDurationSeconds) }} · 交付
                {{ humanDuration(stop.handoffEstimateSeconds) }}
              </span>
              <span class="muted">
                预计到达 {{ stop.planArriveAt ? clockText(stop.planArriveAt) : '—' }} · 预计离开
                {{ stop.planDepartAt ? clockText(stop.planDepartAt) : '—' }}
              </span>
              <span v-if="stop.actualArriveAt || stop.actualDepartAt" class="muted">
                实际到达 {{ stop.actualArriveAt ? clockText(stop.actualArriveAt) : '—' }} · 实际离开
                {{ stop.actualDepartAt ? clockText(stop.actualDepartAt) : '—' }}
              </span>
            </div>
            <div class="stop-item__side">
              <div class="stop-item__move">
                <ElButton size="small" :disabled="index === 0" @click="move(index, -1)"
                  >上移</ElButton
                >
                <ElButton
                  size="small"
                  :disabled="index === stops.length - 1"
                  @click="move(index, 1)"
                >
                  下移
                </ElButton>
              </div>
            </div>
          </div>
        </div>
      </ElCard>

      <div class="wave-side">
        <ElCard class="fresh-card" shadow="never">
          <template #header>
            <div class="fresh-toolbar">
              <strong>路线与轨迹</strong>
              <span class="muted">绿色实线为规划路线，蓝色虚线为实际轨迹</span>
            </div>
          </template>
          <DeliveryMap
            :amap-key="amapKey"
            :security-code="amapSecurityCode"
            :route-path="routePath"
            :track-path="trackPath"
            :stops="stopMarkers"
            :origin="origin"
            :cursor="cursor"
            height="360px"
          />
        </ElCard>

        <ElCard class="fresh-card" shadow="never">
          <template #header><strong>轨迹回放</strong></template>
          <ElEmpty v-if="replayPoints.length === 0" :image-size="60" description="暂无轨迹数据" />
          <TrackReplay v-else :points="replayPoints" @cursor="onCursor" />
        </ElCard>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
  import { ElMessage, ElMessageBox } from 'element-plus'
  import {
    getDeliveryConfigs,
    getWaveDetail,
    getWaveReplay,
    replanWave,
    updateWaveSequence,
    type DeliveryConfigItem,
    type GeoPoint,
    type TrackPoint,
    type WaveDetail,
    type WaveStop
  } from '@/api/delivery'
  import DeliveryMap from '../components/DeliveryMap.vue'
  import TrackReplay from '../components/TrackReplay.vue'
  import { pickAmapConfig } from '../composables/useAmap'
  import { clockText, distanceText, humanDuration, waveStatusText } from '../utils'

  defineOptions({ name: 'FreshDeliveryWaveDetail' })

  const route = useRoute()
  const router = useRouter()

  const waveId = Number(route.params.id || 0)
  const loading = ref(false)
  const replanning = ref(false)
  const savingSequence = ref(false)
  const wave = ref<WaveDetail | null>(null)
  const stops = ref<WaveStop[]>([])
  const originalOrder = ref<number[]>([])
  const replayPoints = ref<TrackPoint[]>([])
  const cursor = ref<GeoPoint | null>(null)
  const configs = ref<DeliveryConfigItem[]>([])
  const dragIndex = ref<number | null>(null)

  const amapConfig = computed(() => pickAmapConfig(configs.value))
  const amapKey = computed(() => amapConfig.value.key)
  const amapSecurityCode = computed(() => amapConfig.value.securityCode)

  const origin = computed<GeoPoint | null>(() => {
    const point = wave.value?.route?.origin
    return point ? { lat: point.lat, lng: point.lng } : null
  })

  /**
   * 规划路线用「门店 → 站点顺序」串联。
   * route.polyline 是后端编码折线，编码方式随 matrixProvider 变化，这里不做解码。
   */
  const routePath = computed<GeoPoint[]>(() => {
    const points = stops.value.map((stop) => stop.location).filter(Boolean)
    return origin.value ? [origin.value, ...points] : points
  })

  const trackPath = computed<GeoPoint[]>(() =>
    (wave.value?.track || []).map((point) => ({ lat: point.lat, lng: point.lng }))
  )

  const stopMarkers = computed(() =>
    stops.value.map((stop, index) => ({ seqNo: index + 1, location: stop.location }))
  )

  const sequenceDirty = computed(() => {
    const current = stops.value.map((stop) => stop.taskId)
    return current.join(',') !== originalOrder.value.join(',')
  })

  const summaryItems = computed(() => {
    const plan = wave.value?.route
    return [
      { label: '波次状态', value: waveStatusText(wave.value?.status) },
      { label: '骑手', value: wave.value?.riderName || '未指派' },
      // 老波次不是按时段发的，没有 slotLabel
      { label: '配送时段', value: wave.value?.slotLabel || '—' },
      {
        label: '站点数',
        value: `${wave.value?.completedCount ?? 0}/${wave.value?.taskCount ?? 0}`
      },
      {
        label: '规划里程',
        value: distanceText(plan?.totalDistanceMeters ?? wave.value?.planDistanceMeters)
      },
      {
        label: '规划时长',
        value: humanDuration(plan?.totalDurationSeconds ?? wave.value?.planDurationSeconds)
      },
      // 状态为 RETURNING 时这里是空的，正好说明「送完了但还没回店」
      {
        label: '回店时间',
        value: wave.value?.returnedAt ? clockText(wave.value.returnedAt) : '—'
      },
      { label: '优化器', value: plan?.optimizerName || wave.value?.optimizerName || '—' },
      { label: '距离矩阵', value: plan?.matrixProvider || wave.value?.matrixProvider || '—' },
      { label: '规划版本', value: plan?.planVersion !== undefined ? `v${plan.planVersion}` : '—' }
    ]
  })

  const onDragOver = (index: number) => {
    const from = dragIndex.value
    if (from === null || from === index) return
    const list = stops.value.slice()
    const [moved] = list.splice(from, 1)
    list.splice(index, 0, moved)
    stops.value = list
    dragIndex.value = index
  }

  const move = (index: number, offset: number) => {
    const target = index + offset
    if (target < 0 || target >= stops.value.length) return
    const list = stops.value.slice()
    const [moved] = list.splice(index, 1)
    list.splice(target, 0, moved)
    stops.value = list
  }

  const onCursor = (point: TrackPoint | null) => {
    cursor.value = point ? { lat: point.lat, lng: point.lng } : null
  }

  const loadDetail = async () => {
    loading.value = true
    try {
      const detail = await getWaveDetail(waveId)
      wave.value = detail
      const list = detail.stops || []
      stops.value = list.slice().sort((a, b) => a.seqNo - b.seqNo)
      originalOrder.value = stops.value.map((stop) => stop.taskId)
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '波次详情加载失败')
    } finally {
      loading.value = false
    }
  }

  const loadReplay = async () => {
    try {
      const replay = await getWaveReplay(waveId)
      replayPoints.value = Array.isArray(replay.points) ? replay.points : []
    } catch {
      replayPoints.value = wave.value?.track || []
    }
  }

  const loadConfigs = async () => {
    try {
      configs.value = await getDeliveryConfigs('amap')
    } catch {
      configs.value = []
    }
  }

  const saveSequence = async () => {
    try {
      await ElMessageBox.confirm(
        '保存后系统会按新的站点顺序重算 ETA 并推送给骑手，是否继续？',
        '保存站点顺序',
        { type: 'warning', confirmButtonText: '确认保存', cancelButtonText: '取消' }
      )
      savingSequence.value = true
      await updateWaveSequence(
        waveId,
        stops.value.map((stop) => stop.taskId)
      )
      ElMessage.success('站点顺序已保存')
      await loadDetail()
    } catch (error) {
      if (error !== 'cancel' && error !== 'close') {
        ElMessage.error(error instanceof Error ? error.message : '保存失败')
      }
    } finally {
      savingSequence.value = false
    }
  }

  const handleReplan = async () => {
    try {
      const { value } = await ElMessageBox.prompt(
        '重新规划会覆盖当前站点顺序，请填写原因（会记入波次日志）。',
        '重新规划',
        {
          type: 'warning',
          confirmButtonText: '确认重规划',
          cancelButtonText: '取消',
          inputValidator: (input: string) => (input && input.trim() ? true : '请填写原因')
        }
      )
      replanning.value = true
      await replanWave(waveId, value.trim())
      ElMessage.success('已重新规划')
      await loadDetail()
    } catch (error) {
      if (error !== 'cancel' && error !== 'close') {
        ElMessage.error(error instanceof Error ? error.message : '重新规划失败')
      }
    } finally {
      replanning.value = false
    }
  }

  const goBack = () => {
    router.push('/fresh/delivery/waves')
  }

  onMounted(async () => {
    if (!waveId) {
      ElMessage.error('波次 ID 无效')
      return
    }
    await Promise.all([loadDetail(), loadConfigs()])
    await loadReplay()
  })
</script>

<style scoped lang="scss">
  @use '../../style.scss';

  .wave-summary {
    display: grid;
    grid-template-columns: repeat(5, minmax(0, 1fr));
    gap: 12px;
  }

  .wave-summary__item {
    padding: 14px 16px;
  }

  .wave-summary__value {
    margin-top: 8px;
    color: #007a39;
    font-size: 18px;
    font-weight: 700;
    overflow-wrap: anywhere;
  }

  .wave-body {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 16px;
    align-items: start;
  }

  .wave-side {
    display: grid;
    gap: 16px;
  }

  .stop-list {
    display: grid;
    gap: 10px;
    max-height: 560px;
    overflow-y: auto;
  }

  .stop-item {
    display: grid;
    grid-template-columns: auto 1fr auto;
    gap: 12px;
    padding: 12px;
    border: 1px solid var(--art-border-color);
    border-radius: 10px;
    cursor: grab;

    &--dragging {
      border-color: var(--el-color-primary);
      opacity: 0.7;
    }
  }

  .stop-item__seq {
    display: inline-grid;
    width: 28px;
    height: 28px;
    place-items: center;
    border-radius: 10px;
    color: var(--el-color-primary);
    font-weight: 700;
    background: var(--el-color-primary-light-9);
  }

  .stop-item__main {
    display: grid;
    gap: 4px;
    min-width: 0;

    strong {
      color: var(--art-gray-900);
    }

    span {
      font-size: 12px;
      overflow-wrap: anywhere;
    }
  }

  .stop-item__side {
    display: grid;
    justify-items: end;
    gap: 8px;
  }

  .stop-item__move {
    display: flex;
    gap: 6px;

    :deep(.el-button + .el-button) {
      margin-left: 0;
    }
  }

  @media (max-width: 1300px) {
    .wave-summary {
      grid-template-columns: repeat(3, minmax(0, 1fr));
    }

    .wave-body {
      grid-template-columns: 1fr;
    }
  }
</style>
