<template>
  <div v-loading="loading" class="fresh-page">
    <div class="fresh-page__head">
      <div>
        <h1 class="fresh-page__title">
          骑手详情
          <span v-if="rider">· {{ rider.name }}</span>
        </h1>
        <p class="fresh-page__desc"
          >基本资料、今日/本周统计、班次历史、服务分流水与历史轨迹回放。</p
        >
      </div>
      <div class="fresh-toolbar__right">
        <ElButton @click="goBack">返回列表</ElButton>
        <ElButton @click="goSettlements">查看结算</ElButton>
        <template v-if="rider">
          <ElButton
            v-if="rider.accountStatus === 'ACTIVE'"
            type="danger"
            plain
            @click="handleSuspend"
          >
            停用
          </ElButton>
          <ElButton v-else type="success" plain @click="handleActivate">启用</ElButton>
          <ElButton
            v-if="rider.workStatus !== 'OFF_DUTY'"
            type="warning"
            plain
            @click="handleForceOff"
          >
            强制下班
          </ElButton>
        </template>
      </div>
    </div>

    <ElAlert
      v-if="rider && healthCertExpiringSoon"
      type="error"
      show-icon
      :closable="false"
      :title="`健康证将于 ${rider.healthCertExpireAt} 到期，请提醒骑手尽快更新，过期后不得上岗。`"
    />
    <ElAlert
      v-if="rider && !rider.locationConsentAt"
      type="warning"
      show-icon
      :closable="false"
      title="该骑手尚未授权位置采集，系统会拒收其定位数据，地图与轨迹不可用。"
    />

    <ElCard class="fresh-card" shadow="never">
      <template #header>
        <div class="fresh-toolbar">
          <strong>基本资料</strong>
          <ElTag v-if="rider" :type="accountStatusTag(rider.accountStatus)" effect="light">
            {{ accountStatusText(rider.accountStatus) }}
          </ElTag>
        </div>
      </template>
      <ElDescriptions v-if="rider" :column="3" border>
        <ElDescriptionsItem label="工号">{{ rider.riderNo }}</ElDescriptionsItem>
        <ElDescriptionsItem label="姓名">{{ rider.name }}</ElDescriptionsItem>
        <ElDescriptionsItem label="手机号">{{ rider.phone }}</ElDescriptionsItem>
        <ElDescriptionsItem label="角色">
          {{ rider.role === 'RIDER_CAPTAIN' ? '骑手队长' : '骑手' }}
        </ElDescriptionsItem>
        <ElDescriptionsItem label="在岗状态">
          <ElTag :type="workStatusTag(rider.workStatus)" effect="plain" size="small">
            {{ workStatusText(rider.workStatus) }}
          </ElTag>
        </ElDescriptionsItem>
        <ElDescriptionsItem label="等级">{{ rider.levelCode || '—' }}</ElDescriptionsItem>
        <ElDescriptionsItem label="车辆">{{
          vehicleTypeText(rider.vehicleType)
        }}</ElDescriptionsItem>
        <ElDescriptionsItem label="车牌">{{ rider.vehiclePlate || '无' }}</ElDescriptionsItem>
        <ElDescriptionsItem label="试用期">{{ rider.probation ? '是' : '否' }}</ElDescriptionsItem>
        <ElDescriptionsItem label="并发上限">{{ rider.maxConcurrentTask }} 单</ElDescriptionsItem>
        <ElDescriptionsItem label="载重上限">
          {{ Number(rider.capacityWeightKg || 0).toFixed(0) }} kg
        </ElDescriptionsItem>
        <ElDescriptionsItem label="服务分">{{ rider.serviceScore }}</ElDescriptionsItem>
        <ElDescriptionsItem label="累计单量">{{ rider.totalTaskCount }}</ElDescriptionsItem>
        <ElDescriptionsItem label="历史准时率">{{ percent(rider.onTimeRate) }}</ElDescriptionsItem>
        <ElDescriptionsItem label="健康证到期">
          <span v-if="!rider.healthCertExpireAt" class="muted">未登记</span>
          <ElTag
            v-else
            :type="healthCertExpiringSoon ? 'danger' : 'success'"
            effect="light"
            size="small"
          >
            {{ rider.healthCertExpireAt }}
          </ElTag>
        </ElDescriptionsItem>
        <ElDescriptionsItem label="位置授权时间" :span="3">
          {{ rider.locationConsentAt ? fullTimeText(rider.locationConsentAt) : '未授权' }}
        </ElDescriptionsItem>
      </ElDescriptions>
      <ElEmpty v-else description="骑手信息加载失败" />
    </ElCard>

    <div class="stats-grid">
      <ElCard class="fresh-card" shadow="never">
        <template #header><strong>今日统计</strong></template>
        <div class="stats-row">
          <div v-for="item in todayStats" :key="item.label">
            <span>{{ item.label }}</span>
            <strong>{{ item.value }}</strong>
          </div>
        </div>
      </ElCard>
      <ElCard class="fresh-card" shadow="never">
        <template #header><strong>本周统计</strong></template>
        <div class="stats-row">
          <div v-for="item in weekStats" :key="item.label">
            <span>{{ item.label }}</span>
            <strong>{{ item.value }}</strong>
          </div>
        </div>
      </ElCard>
    </div>

    <ElCard class="fresh-card" shadow="never">
      <template #header>
        <div class="fresh-toolbar">
          <strong>历史轨迹回放</strong>
          <ElDatePicker
            v-model="trackDate"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="选择日期"
            @change="loadTrack"
          />
        </div>
      </template>
      <div class="track-panel">
        <div class="track-panel__map">
          <DeliveryMap
            :amap-key="amapKey"
            :security-code="amapSecurityCode"
            :track-path="trackPath"
            :cursor="cursor"
            :fallback-center="storeCenter"
            height="320px"
          />
        </div>
        <div class="track-panel__control">
          <ElEmpty v-if="track.length === 0" :image-size="60" description="所选日期没有轨迹数据" />
          <TrackReplay v-else :points="track" @cursor="onCursor" />
        </div>
      </div>
    </ElCard>

    <ElCard class="fresh-card" shadow="never">
      <template #header><strong>班次历史</strong></template>
      <ElTable :data="shifts" empty-text="暂无班次记录">
        <ElTableColumn label="上班时间" width="170">
          <template #default="{ row }">{{ fullTimeText(row.onDutyAt) }}</template>
        </ElTableColumn>
        <ElTableColumn label="下班时间" width="170">
          <template #default="{ row }">
            {{ row.offDutyAt ? fullTimeText(row.offDutyAt) : '在岗中' }}
          </template>
        </ElTableColumn>
        <ElTableColumn label="在线时长" width="110">
          <template #default="{ row }">{{ humanDuration(row.onlineSeconds) }}</template>
        </ElTableColumn>
        <ElTableColumn label="休息时长" width="110">
          <template #default="{ row }">{{ humanDuration(row.restTotalSeconds) }}</template>
        </ElTableColumn>
        <ElTableColumn label="任务/送达" width="110">
          <template #default="{ row }">{{ row.deliveredCount }}/{{ row.taskCount }}</template>
        </ElTableColumn>
        <ElTableColumn label="准时" width="90">
          <template #default="{ row }">{{ row.onTimeCount }}</template>
        </ElTableColumn>
        <ElTableColumn label="里程" width="110">
          <template #default="{ row }">{{ distanceText(row.mileageMeters) }}</template>
        </ElTableColumn>
        <ElTableColumn label="收入" min-width="110">
          <template #default="{ row }">
            <span class="money">{{ money(row.earningAmount) }}</span>
          </template>
        </ElTableColumn>
      </ElTable>
    </ElCard>

    <ElCard class="fresh-card" shadow="never">
      <template #header><strong>服务分流水</strong></template>
      <ElTable :data="scoreEvents" empty-text="暂无服务分变动">
        <ElTableColumn label="时间" width="180">
          <template #default="{ row }">{{ fullTimeText(row.createdAt) }}</template>
        </ElTableColumn>
        <ElTableColumn label="变动" width="100">
          <template #default="{ row }">
            <span :class="row.scoreDelta >= 0 ? 'score-up' : 'score-down'">
              {{ row.scoreDelta > 0 ? `+${row.scoreDelta}` : row.scoreDelta }}
            </span>
          </template>
        </ElTableColumn>
        <ElTableColumn label="原因" min-width="220" prop="reason" />
        <ElTableColumn label="来源" width="140">
          <template #default="{ row }">{{ row.sourceType || '系统' }}</template>
        </ElTableColumn>
      </ElTable>
    </ElCard>
  </div>
</template>

<script setup lang="ts">
  import { ElMessage, ElMessageBox } from 'element-plus'
  import {
    activateRider,
    forceRiderOffDuty,
    getDeliveryConfigs,
    getRiderDetail,
    getRiderShifts,
    getRiderTrack,
    getScoreEvents,
    suspendRider,
    type AdminRider,
    type DeliveryConfigItem,
    type GeoPoint,
    type RiderShift,
    type ScoreEvent,
    type TrackPoint
  } from '@/api/delivery'
  import DeliveryMap from '../components/DeliveryMap.vue'
  import TrackReplay from '../components/TrackReplay.vue'
  import { pickAmapConfig } from '../composables/useAmap'
  import {
    accountStatusTag,
    accountStatusText,
    distanceText,
    fullTimeText,
    humanDuration,
    isHealthCertExpiringSoon,
    money,
    percent,
    storeCenterFromConfigs,
    vehicleTypeText,
    workStatusTag,
    workStatusText
  } from '../utils'

  defineOptions({ name: 'FreshDeliveryRiderDetail' })

  const route = useRoute()
  const router = useRouter()

  const riderId = Number(route.params.id || 0)
  const loading = ref(false)
  const rider = ref<AdminRider | null>(null)
  const shifts = ref<RiderShift[]>([])
  const scoreEvents = ref<ScoreEvent[]>([])
  const track = ref<TrackPoint[]>([])
  const cursor = ref<GeoPoint | null>(null)
  const configs = ref<DeliveryConfigItem[]>([])

  const todayText = () => {
    const date = new Date()
    const offset = date.getTimezoneOffset() * 60000
    return new Date(date.getTime() - offset).toISOString().slice(0, 10)
  }

  const trackDate = ref(todayText())

  const amapConfig = computed(() => pickAmapConfig(configs.value))
  const amapKey = computed(() => amapConfig.value.key)
  const amapSecurityCode = computed(() => amapConfig.value.securityCode)
  const storeCenter = computed(() => storeCenterFromConfigs(configs.value))
  const trackPath = computed<GeoPoint[]>(() =>
    track.value.map((point) => ({ lat: point.lat, lng: point.lng }))
  )

  const healthCertExpiringSoon = computed(() =>
    isHealthCertExpiringSoon(rider.value?.healthCertExpireAt)
  )
  const todayStats = computed(() => [
    { label: '已送达', value: String(rider.value?.todayDeliveredCount ?? 0) },
    { label: '准时率', value: percent(rider.value?.todayOnTimeRate) }
  ])
  const weekStats = computed(() => [
    { label: '已送达', value: String(rider.value?.weekDeliveredCount ?? 0) },
    { label: '准时率', value: percent(rider.value?.weekOnTimeRate) }
  ])

  const loadRider = async () => {
    loading.value = true
    try {
      rider.value = await getRiderDetail(riderId)
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '骑手详情加载失败')
    } finally {
      loading.value = false
    }
  }

  const loadShifts = async () => {
    try {
      shifts.value = await getRiderShifts(riderId)
    } catch {
      shifts.value = []
    }
  }

  const loadScoreEvents = async () => {
    try {
      const result = await getScoreEvents({ riderId, pageSize: 50 })
      scoreEvents.value = result.items || []
    } catch {
      scoreEvents.value = []
    }
  }

  const loadTrack = async () => {
    try {
      const result = await getRiderTrack(riderId, { date: trackDate.value })
      track.value = result?.points || []
    } catch (error) {
      track.value = []
      ElMessage.error(error instanceof Error ? error.message : '轨迹加载失败')
    }
  }

  const loadConfigs = async () => {
    try {
      const [amapItems, storeItems] = await Promise.all([
        getDeliveryConfigs('amap'),
        getDeliveryConfigs('STORE')
      ])
      configs.value = [...amapItems, ...storeItems]
    } catch {
      configs.value = []
    }
  }

  const onCursor = (point: TrackPoint | null) => {
    cursor.value = point ? { lat: point.lat, lng: point.lng } : null
  }

  const handleSuspend = async () => {
    if (!rider.value) return
    try {
      await ElMessageBox.confirm(
        `停用后 ${rider.value.name} 无法登录骑手端，是否继续？`,
        '停用骑手',
        {
          type: 'warning'
        }
      )
      await suspendRider(riderId)
      ElMessage.success('骑手已停用')
      await loadRider()
    } catch (error) {
      if (error !== 'cancel' && error !== 'close') {
        ElMessage.error(error instanceof Error ? error.message : '操作失败')
      }
    }
  }

  const handleActivate = async () => {
    if (!rider.value) return
    try {
      await ElMessageBox.confirm(`确认启用 ${rider.value.name} 的账号？`, '启用骑手', {
        type: 'warning'
      })
      await activateRider(riderId)
      ElMessage.success('骑手已启用')
      await loadRider()
    } catch (error) {
      if (error !== 'cancel' && error !== 'close') {
        ElMessage.error(error instanceof Error ? error.message : '操作失败')
      }
    }
  }

  const handleForceOff = async () => {
    if (!rider.value) return
    try {
      const { value } = await ElMessageBox.prompt(
        `强制下班会立即撤销 ${rider.value.name} 的登录会话，请填写原因。`,
        '强制下班',
        {
          type: 'warning',
          confirmButtonText: '确认下班',
          cancelButtonText: '取消',
          inputValidator: (input: string) => (input && input.trim() ? true : '请填写原因')
        }
      )
      await forceRiderOffDuty(riderId, value.trim())
      ElMessage.success('骑手已强制下班')
      await loadRider()
    } catch (error) {
      if (error !== 'cancel' && error !== 'close') {
        ElMessage.error(error instanceof Error ? error.message : '操作失败')
      }
    }
  }

  const goBack = () => {
    router.push('/fresh/delivery/riders')
  }

  const goSettlements = () => {
    router.push({ path: '/fresh/delivery/settlements', query: { riderId: String(riderId) } })
  }

  onMounted(async () => {
    if (!riderId) {
      ElMessage.error('骑手 ID 无效')
      return
    }
    await Promise.all([loadRider(), loadShifts(), loadScoreEvents(), loadConfigs(), loadTrack()])
  })
</script>

<style scoped lang="scss">
  @use '../../style.scss';

  .stats-grid {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 16px;
  }

  .stats-row {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 12px;

    > div {
      display: grid;
      gap: 6px;
    }

    span {
      color: var(--art-gray-600);
      font-size: 12px;
    }

    strong {
      color: var(--art-gray-900);
      font-size: 18px;
    }
  }

  .track-panel {
    display: grid;
    grid-template-columns: 2fr 1fr;
    gap: 16px;
  }

  .track-panel__control {
    display: grid;
    align-content: start;
    gap: 10px;
  }

  .score-up {
    color: #00843d;
    font-weight: 700;
  }

  .score-down {
    color: var(--el-color-danger);
    font-weight: 700;
  }

  @media (max-width: 1200px) {
    .stats-grid,
    .track-panel {
      grid-template-columns: 1fr;
    }

    .stats-row {
      grid-template-columns: repeat(3, minmax(0, 1fr));
    }
  }
</style>
