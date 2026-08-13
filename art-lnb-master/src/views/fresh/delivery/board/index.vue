<template>
  <div ref="pageRef" class="fresh-page board-page">
    <div class="fresh-page__head">
      <div>
        <h1 class="fresh-page__title">配送调度台</h1>
        <p class="fresh-page__desc">
          四列队列看板（待派积压、超时风险、未关闭异常、在岗骑手）+
          地图辅助面板。不用地图也能完成全部派单。
        </p>
      </div>
      <div class="board-actions">
        <ElTag
          :type="streamDegraded ? 'warning' : streamConnected ? 'success' : 'info'"
          effect="light"
        >
          {{ connectionText }}
        </ElTag>
        <ElButton v-if="streamDegraded" link type="primary" @click="reconnectStream">
          立即重连
        </ElButton>
        <span class="board-clock">{{ clockDisplay }}</span>
        <div v-if="autoDispatchConfig" class="board-switch">
          <span>自动派单</span>
          <ElSwitch
            :model-value="autoDispatchOn"
            :loading="autoDispatchSaving"
            @change="toggleAutoDispatch"
          />
        </div>
        <ElButton :loading="dispatchRunning" @click="runDispatch">手动触发调度</ElButton>
        <ElButton type="primary" :loading="loading" @click="loadBoard()">刷新</ElButton>
        <ElButton @click="toggleFullscreen">{{ isFullscreen ? '退出全屏' : '全屏' }}</ElButton>
      </div>
    </div>

    <div class="board-metrics">
      <button
        v-for="metric in metrics"
        :key="metric.key"
        type="button"
        class="metric-card board-metric"
        :class="{ 'board-metric--active': focus === metric.focus && metric.focus !== '' }"
        @click="applyFocus(metric)"
      >
        <div class="metric-card__label">{{ metric.label }}</div>
        <div class="metric-card__value" :class="metric.danger ? 'board-metric__value--danger' : ''">
          {{ metric.value }}
        </div>
        <div class="board-metric__hint">{{ metric.hint }}</div>
      </button>
    </div>

    <ElAlert
      v-if="board?.summary.capacityWarning"
      type="warning"
      show-icon
      :closable="false"
      title="运力预警：待派任务已超出当前在岗骑手的承载能力，请考虑增派人手或延长时间窗。"
    />

    <div class="board-body" :class="{ 'board-body--full': mapCollapsed }">
      <div v-loading="loading && !board" class="board-queues">
        <section class="queue-col" :class="{ 'queue-col--focus': focus === 'pending' }">
          <header class="queue-col__head">
            <span class="queue-col__dot queue-col__dot--pending"></span>
            <strong>待派积压</strong>
            <ElTag size="small" type="danger" effect="light">{{ pendingTasks.length }}</ElTag>
            <span class="queue-col__hint">按压单到期升序，可直接拖到骑手卡片派单</span>
          </header>
          <div class="queue-col__filter">
            <ElInput
              v-model="pendingKeyword"
              size="small"
              clearable
              placeholder="任务号 / 订单号 / 地址"
            />
            <ElSelect v-model="pendingCold" size="small" class="queue-col__select">
              <ElOption label="全部温层" value="all" />
              <ElOption label="仅冷链" value="cold" />
              <ElOption label="仅常温" value="normal" />
            </ElSelect>
            <ElCheckbox v-model="pendingFarOnly" size="small">仅远单</ElCheckbox>
          </div>
          <div class="queue-col__body">
            <ElEmpty
              v-if="pendingTasks.length === 0"
              :image-size="56"
              :description="board ? '没有待派任务，积压已清空' : '暂无数据'"
            />
            <TaskQueueCard
              v-for="task in pendingTasks"
              :key="task.taskId"
              :task="task"
              mode="pending"
              :now="now"
              :suggestion="suggestionMap[task.taskId] || null"
              @assign-now="handleAssignNow"
              @assign-to="handleAssignTo"
              @view-suggest="handleViewSuggest"
              @cancel="handleCancelTask"
              @drag-start="draggingTask = $event"
              @drag-end="draggingTask = null"
            />
          </div>
        </section>

        <section class="queue-col" :class="{ 'queue-col--focus': focus === 'risk' }">
          <header class="queue-col__head">
            <span class="queue-col__dot queue-col__dot--risk"></span>
            <strong>在途超时风险</strong>
            <ElTag size="small" type="warning" effect="light">{{ riskTasks.length }}</ElTag>
            <span class="queue-col__hint">按剩余时间升序，不足 5 分钟标红</span>
          </header>
          <div class="queue-col__filter">
            <ElInput
              v-model="riskKeyword"
              size="small"
              clearable
              placeholder="任务号 / 订单号 / 地址"
            />
            <ElSelect
              v-model="riskRiderId"
              size="small"
              clearable
              placeholder="全部骑手"
              class="queue-col__select"
            >
              <ElOption
                v-for="rider in riderOptions"
                :key="rider.riderId"
                :label="rider.name"
                :value="rider.riderId"
              />
            </ElSelect>
            <ElCheckbox v-model="riskOvertimeOnly" size="small">仅已超时</ElCheckbox>
          </div>
          <div class="queue-col__body">
            <ElEmpty
              v-if="riskTasks.length === 0"
              :image-size="56"
              description="在途任务全部在时间窗内"
            />
            <TaskQueueCard
              v-for="task in riskTasks"
              :key="task.taskId"
              :task="task"
              mode="risk"
              :now="now"
              @reassign="handleReassign"
              @contact-rider="handleContactRider"
              @contact-customer="handleContactCustomer"
            />
          </div>
        </section>

        <section class="queue-col" :class="{ 'queue-col--focus': focus === 'exception' }">
          <header class="queue-col__head">
            <span class="queue-col__dot queue-col__dot--exception"></span>
            <strong>未关闭异常</strong>
            <ElTag size="small" type="danger" effect="light">{{ exceptions.length }}</ElTag>
            <span class="queue-col__hint">按严重度降序</span>
          </header>
          <div class="queue-col__filter">
            <span class="muted">看板仅返回异常任务卡，处理详情请进入异常页</span>
            <ElButton size="small" type="primary" plain @click="goExceptions">
              进入异常处理
            </ElButton>
          </div>
          <div class="queue-col__body">
            <ElEmpty v-if="exceptions.length === 0" :image-size="56" description="没有未关闭异常" />
            <TaskQueueCard
              v-for="task in exceptions"
              :key="task.taskId"
              :task="task"
              mode="risk"
              :now="now"
              @reassign="handleReassign"
              @contact-rider="handleContactRider"
              @contact-customer="handleContactCustomer"
            />
          </div>
        </section>

        <section class="queue-col" :class="{ 'queue-col--focus': focus === 'rider' }">
          <header class="queue-col__head">
            <span class="queue-col__dot queue-col__dot--rider"></span>
            <strong>在岗骑手</strong>
            <ElTag size="small" type="primary" effect="light">{{ riders.length }}</ElTag>
            <span class="queue-col__hint">按负载升序，空闲在最上</span>
          </header>
          <div class="queue-col__filter">
            <ElInput v-model="riderKeyword" size="small" clearable placeholder="姓名 / 工号" />
            <ElCheckbox v-model="riderAvailableOnly" size="small">仅可派单</ElCheckbox>
            <ElButton size="small" text type="primary" @click="openBroadcast">群发消息</ElButton>
          </div>
          <div class="queue-col__body">
            <ElEmpty v-if="riders.length === 0" :image-size="56" description="当前没有在岗骑手" />
            <RiderBoardCardItem
              v-for="rider in riders"
              :key="rider.riderId"
              :rider="rider"
              :now="now"
              :wave="waveOf(rider)"
              :drag-active="Boolean(draggingTask)"
              @dispatch="openTaskPicker"
              @track="goRiderDetail"
              @message="openRiderMessage"
              @force-off="handleForceOff"
              @drop-task="handleDropTask"
            />
          </div>
        </section>
      </div>

      <aside v-if="!mapCollapsed" class="board-map">
        <div class="board-map__head">
          <strong>地图辅助面板</strong>
          <span class="muted">每 5 秒刷新位置</span>
          <ElButton link type="primary" @click="mapCollapsed = true">收起</ElButton>
        </div>
        <DeliveryMap
          :amap-key="amapKey"
          :security-code="amapSecurityCode"
          :riders="mapSnapshot.riders"
          :tasks="mapSnapshot.tasks"
          :active-rider-id="activeRiderId"
          :active-task-id="activeTaskId"
          height="calc(100% - 40px)"
          @select="onMapSelect"
        />
      </aside>
      <ElButton v-else class="board-map__expand" type="primary" plain @click="mapCollapsed = false">
        展开地图
      </ElButton>
    </div>

    <ElDialog v-model="suggestVisible" title="派单建议" width="720px">
      <DispatchSuggestPanel
        :suggestion="activeSuggestion"
        :task="activeTask"
        :loading="suggestLoading"
        @adopt="adoptCandidate"
      />
    </ElDialog>

    <RiderPicker
      v-model="pickerVisible"
      :riders="pickerRiders"
      :candidates="activeSuggestion?.candidates || []"
      :recommended-rider-id="activeSuggestion?.recommendedRiderId || null"
      :now="now"
      :title="pickerMode === 'reassign' ? '改派骑手' : '指派骑手'"
      :confirm-text="pickerMode === 'reassign' ? '确认改派' : '确认派单'"
      :require-reason="pickerMode === 'reassign'"
      :loading="suggestLoading"
      :submitting="assigning"
      @confirm="onPickerConfirm"
    />

    <RiderMessageDialog v-model="messageVisible" :rider="messageRider" />

    <ElDialog v-model="contactVisible" title="联系顾客" width="420px">
      <div v-if="contactTask" class="contact-box">
        <div
          ><span>收货人</span><strong>{{ contactTask.receiverName || '—' }}</strong></div
        >
        <div
          ><span>联系电话</span
          ><strong>{{ contactTask.receiverPhone || contactTask.receiverPhoneMasked }}</strong></div
        >
        <div v-if="contactTask.callNumber">
          <span>隐私号</span><strong>{{ contactTask.callNumber }}</strong>
        </div>
        <div
          ><span>收货地址</span><strong>{{ contactTask.addressDetail }}</strong></div
        >
        <ElAlert
          v-if="contactTask.phoneDegraded"
          type="warning"
          :closable="false"
          show-icon
          title="隐私号已降级，当前展示真实号码，请谨慎使用"
        />
      </div>
      <template #footer>
        <ElButton @click="contactVisible = false">关闭</ElButton>
        <ElButton type="primary" @click="copyPhone">复制号码</ElButton>
      </template>
    </ElDialog>

    <ElDialog
      v-model="taskPickerVisible"
      :title="`给 ${taskPickerRider?.name || ''} 派单`"
      width="720px"
    >
      <ElAlert
        v-if="taskPickerRider"
        class="task-picker__tip"
        type="info"
        :closable="false"
        show-icon
        :title="`当前负载 ${taskPickerRider.currentTaskCount}/${taskPickerRider.maxConcurrentTask}，剩余可接 ${Math.max(taskPickerRider.maxConcurrentTask - taskPickerRider.currentTaskCount, 0)} 单`"
      />
      <div class="task-picker__slot">
        <span class="task-picker__slot-label">配送时段</span>
        <ElSelect
          v-model="taskPickerSlot"
          clearable
          placeholder="全部时段（不发车，仅批量指派）"
          class="task-picker__slot-select"
          @change="onTaskPickerSlotChange"
        >
          <ElOption
            v-for="option in pendingSlotOptions"
            :key="option.value"
            :label="option.label"
            :value="option.value"
          />
        </ElSelect>
      </div>
      <ElTable
        ref="taskPickerTableRef"
        :data="taskPickerTasks"
        height="320"
        @selection-change="onTaskPickerSelect"
      >
        <ElTableColumn type="selection" width="46" />
        <ElTableColumn label="任务号" prop="taskNo" width="170" />
        <ElTableColumn label="时段" width="130">
          <template #default="{ row }">{{ row.slotLabel || '—' }}</template>
        </ElTableColumn>
        <ElTableColumn label="地址" min-width="220">
          <template #default="{ row }">
            {{ [row.areaLabel, row.buildingLabel, row.addressDetail].filter(Boolean).join(' ') }}
          </template>
        </ElTableColumn>
        <ElTableColumn label="件数/重量" width="120">
          <template #default="{ row }">
            {{ row.itemCount }} 件 / {{ Number(row.totalWeightKg || 0).toFixed(1) }} kg
          </template>
        </ElTableColumn>
        <ElTableColumn label="承诺时间" width="100">
          <template #default="{ row }">{{ clockText(row.promisedAt) }}</template>
        </ElTableColumn>
      </ElTable>
      <template #footer>
        <ElButton @click="taskPickerVisible = false">取消</ElButton>
        <ElButton
          type="primary"
          :disabled="taskPickerSelection.length === 0"
          :loading="assigning"
          @click="confirmTaskPicker"
        >
          {{
            taskPickerSlot
              ? `发车（${taskPickerSelection.length} 单）`
              : `派 ${taskPickerSelection.length} 单并建波次`
          }}
        </ElButton>
      </template>
    </ElDialog>
  </div>
</template>

<script setup lang="ts">
  import { ElMessage, ElMessageBox } from 'element-plus'
  import { useFullscreen } from '@vueuse/core'
  import {
    assignTask,
    batchAssignTasks,
    cancelTask,
    dispatchSlot,
    forceRiderOffDuty,
    getDeliveryBoard,
    getDeliveryConfigs,
    getDeliveryMap,
    reassignTask,
    runDispatchNow,
    suggestDispatch,
    updateDeliveryConfigs,
    type AdminTaskCard,
    type BoardWaveBrief,
    type DeliveryBoardData,
    type DeliveryConfigItem,
    type DeliveryMapSnapshot,
    type DispatchCandidate,
    type DispatchSuggestion,
    type RiderBoardCard
  } from '@/api/delivery'
  import DeliveryMap from '../components/DeliveryMap.vue'
  import DispatchSuggestPanel from '../components/DispatchSuggestPanel.vue'
  import RiderBoardCardItem from '../components/RiderBoardCard.vue'
  import RiderMessageDialog from '../components/RiderMessageDialog.vue'
  import RiderPicker from '../components/RiderPicker.vue'
  import TaskQueueCard from '../components/TaskQueueCard.vue'
  import { pickAmapConfig } from '../composables/useAmap'
  import { useDeliveryStream } from '../composables/useDeliveryStream'
  import {
    clockText,
    distanceText,
    isColdChain,
    isFarDelivery,
    isFatiguePaused,
    isOnRoadStatus,
    parseTime,
    riderBlockReason,
    secondsUntil,
    blockerText
  } from '../utils'

  defineOptions({ name: 'FreshDeliveryBoard' })

  const router = useRouter()

  const pageRef = ref<HTMLElement>()
  const { isFullscreen, toggle: toggleFullscreen } = useFullscreen(pageRef)

  const board = ref<DeliveryBoardData | null>(null)
  const mapSnapshot = ref<DeliveryMapSnapshot>({ riders: [], tasks: [] })
  const configs = ref<DeliveryConfigItem[]>([])
  const loading = ref(false)
  const dispatchRunning = ref(false)
  const assigning = ref(false)
  const autoDispatchSaving = ref(false)

  /** 与服务端对齐后的当前时间（毫秒），倒计时全部基于它 */
  const now = ref(Date.now())
  let serverOffset = 0
  let tickTimer: number | null = null
  let mapTimer: number | null = null
  let started = false

  const focus = ref<'' | 'pending' | 'risk' | 'exception' | 'rider'>('')
  const pendingKeyword = ref('')
  const pendingCold = ref<'all' | 'cold' | 'normal'>('all')
  const pendingFarOnly = ref(false)
  const riskKeyword = ref('')
  const riskRiderId = ref<number | undefined>()
  const riskOvertimeOnly = ref(false)
  const riderKeyword = ref('')
  const riderAvailableOnly = ref(false)

  const mapCollapsed = ref(false)
  const activeRiderId = ref<number | null>(null)
  const activeTaskId = ref<number | null>(null)

  const draggingTask = ref<AdminTaskCard | null>(null)
  const suggestionMap = ref<Record<number, DispatchSuggestion>>({})
  const activeSuggestion = ref<DispatchSuggestion | null>(null)
  const activeTask = ref<AdminTaskCard | null>(null)
  const suggestLoading = ref(false)
  const suggestVisible = ref(false)

  const pickerVisible = ref(false)
  const pickerMode = ref<'assign' | 'reassign'>('assign')

  const messageVisible = ref(false)
  const messageRider = ref<{ riderId: number; riderNo: string; name: string } | null>(null)

  const contactVisible = ref(false)
  const contactTask = ref<AdminTaskCard | null>(null)

  const taskPickerVisible = ref(false)
  const taskPickerRider = ref<RiderBoardCard | null>(null)
  const taskPickerSelection = ref<AdminTaskCard[]>([])
  // 选了时段就走「发车」：一个时段的单一次性给一个骑手，服务端会校验时段一致
  const taskPickerSlot = ref('')
  const taskPickerTableRef = ref()

  const pendingSlotOptions = computed(() => {
    const counts = new Map<string, number>()
    pendingTasks.value.forEach((task) => {
      const slot = (task.slotLabel || '').trim()
      if (slot) counts.set(slot, (counts.get(slot) || 0) + 1)
    })
    return [...counts.entries()]
      .sort(([left], [right]) => left.localeCompare(right))
      .map(([value, count]) => ({ value, label: `${value}（${count} 单）` }))
  })

  const taskPickerTasks = computed(() =>
    taskPickerSlot.value
      ? pendingTasks.value.filter((task) => (task.slotLabel || '').trim() === taskPickerSlot.value)
      : pendingTasks.value
  )

  /** 选定时段后默认全选：店主的意图就是把这个时段整批发出去，不必再逐条勾。 */
  const onTaskPickerSlotChange = async () => {
    await nextTick()
    const table = taskPickerTableRef.value
    if (!table) return
    table.clearSelection()
    if (!taskPickerSlot.value) return
    taskPickerTasks.value.forEach((task: AdminTaskCard) => table.toggleRowSelection(task, true))
  }

  // ==================== 实时通道 ====================

  const upsertTask = (list: AdminTaskCard[], task: AdminTaskCard, keep: boolean) => {
    const index = list.findIndex((item) => item.taskId === task.taskId)
    if (keep) {
      if (index >= 0) list.splice(index, 1, task)
      else list.push(task)
    } else if (index >= 0) {
      list.splice(index, 1)
    }
  }

  const {
    connected: streamConnected,
    degraded: streamDegraded,
    start: startStream,
    stop: stopStream,
    reconnect: reconnectStream
  } = useDeliveryStream({
    onFullRefresh: () => loadBoard(true),
    onTaskChanged: (task) => {
      const data = board.value
      if (!data) return
      upsertTask(data.queues.pending, task, task.status === 'PENDING')
      upsertTask(
        data.queues.overtimeRisk,
        task,
        isOnRoadStatus(task.status) &&
          (task.overtimeRisk === 'HIGH' || task.overtimeRisk === 'OVERTIME')
      )
    },
    onRiderMoved: (rider) => {
      const data = board.value
      if (!data) return
      const index = data.riders.findIndex((item) => item.riderId === rider.riderId)
      if (index >= 0) data.riders.splice(index, 1, rider)
      else data.riders.push(rider)
    },
    // 看板全量里的异常队列是任务卡，而 SSE 推送的是异常实体，无法安全局部合并。
    onExceptionRaised: () => void loadBoard(),
    onSummaryUpdated: (summary) => {
      if (board.value) board.value.summary = summary
    }
  })

  const connectionText = computed(() => {
    if (streamDegraded.value) return '实时连接已断开，正在 5 秒轮询'
    if (streamConnected.value) return '实时连接正常'
    return '实时连接建立中'
  })

  const clockDisplay = computed(() => {
    const date = new Date(now.value)
    const pad = (value: number) => String(value).padStart(2, '0')
    return `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
  })

  // ==================== 数据加载 ====================

  const loadBoard = async (silent = false) => {
    if (!silent) loading.value = true
    try {
      const data = await getDeliveryBoard()
      board.value = data
      const serverTime = parseTime(data.serverTime)
      serverOffset = Number.isNaN(serverTime) ? 0 : Date.now() - serverTime
      now.value = Date.now() - serverOffset
    } catch (error) {
      if (!silent) {
        ElMessage.error(error instanceof Error ? error.message : '调度看板加载失败')
      }
    } finally {
      loading.value = false
    }
  }

  const loadMap = async () => {
    if (!amapKey.value || mapCollapsed.value) return
    try {
      mapSnapshot.value = await getDeliveryMap()
    } catch {
      // 地图是辅助面板，静默失败，不打断队列操作
    }
  }

  const loadConfigs = async () => {
    try {
      configs.value = await getDeliveryConfigs()
    } catch {
      // 配置读取失败时地图降级为「未配置地图 Key」占位，四列队列不受影响
    }
  }

  const amapConfig = computed(() => pickAmapConfig(configs.value))
  const amapKey = computed(() => amapConfig.value.key)
  const amapSecurityCode = computed(() => amapConfig.value.securityCode)

  /** dispatch.enabled 是自动派单总开关（03 §配置表），找不到时退回同类 BOOL 开关 */
  const autoDispatchConfig = computed(
    () =>
      configs.value.find((item) => item.key === 'dispatch.enabled') ||
      configs.value.find(
        (item) => item.valueType === 'BOOL' && /^dispatch\.(auto_dispatch|enabled)/i.test(item.key)
      )
  )

  const autoDispatchOn = computed(() => {
    const value = String(autoDispatchConfig.value?.value || '').toLowerCase()
    return value === 'true' || value === '1'
  })

  const toggleAutoDispatch = async () => {
    const config = autoDispatchConfig.value
    if (!config) return
    const next = !autoDispatchOn.value
    try {
      await ElMessageBox.confirm(
        next
          ? '开启后系统会按压单窗口自动派单，是否继续？'
          : '关闭后所有任务都需要人工派单，是否继续？',
        next ? '开启自动派单' : '关闭自动派单',
        { type: 'warning' }
      )
      autoDispatchSaving.value = true
      await updateDeliveryConfigs([{ key: config.key, value: String(next) }])
      config.value = String(next)
      ElMessage.success(next ? '自动派单已开启' : '自动派单已关闭')
    } catch (error) {
      if (error !== 'cancel' && error !== 'close') {
        ElMessage.error(error instanceof Error ? error.message : '操作失败')
      }
    } finally {
      autoDispatchSaving.value = false
    }
  }

  const runDispatch = async () => {
    try {
      await ElMessageBox.confirm(
        '确认立即执行一次调度循环？系统会按当前运力重新分配待派任务。',
        '手动触发调度',
        {
          type: 'warning'
        }
      )
      dispatchRunning.value = true
      await runDispatchNow()
      ElMessage.success('调度已触发')
      await loadBoard(true)
    } catch (error) {
      if (error !== 'cancel' && error !== 'close') {
        ElMessage.error(error instanceof Error ? error.message : '操作失败')
      }
    } finally {
      dispatchRunning.value = false
    }
  }

  // ==================== 指标与队列 ====================

  interface MetricItem {
    key: string
    label: string
    value: number
    hint: string
    focus: '' | 'pending' | 'risk' | 'exception' | 'rider'
    danger?: boolean
    idleOnly?: boolean
  }

  const metrics = computed<MetricItem[]>(() => {
    const summary = board.value?.summary
    return [
      {
        key: 'pending',
        label: '待派',
        value: summary?.pendingCount ?? 0,
        hint: '点击聚焦待派积压',
        focus: 'pending'
      },
      {
        key: 'delivering',
        label: '在途',
        value: (summary?.assignedCount ?? 0) + (summary?.deliveringCount ?? 0),
        hint: '点击聚焦在途队列',
        focus: 'risk'
      },
      {
        key: 'risk',
        label: '超时风险',
        value: summary?.overtimeRiskCount ?? 0,
        hint: '点击只看已超时',
        focus: 'risk',
        danger: (summary?.overtimeRiskCount ?? 0) > 0
      },
      {
        key: 'exception',
        label: '未关闭异常',
        value: summary?.openExceptionCount ?? 0,
        hint: '点击聚焦异常队列',
        focus: 'exception',
        danger: (summary?.openExceptionCount ?? 0) > 0
      },
      {
        key: 'onDuty',
        label: '在岗骑手',
        value: summary?.onDutyRiderCount ?? 0,
        hint: '点击聚焦骑手队列',
        focus: 'rider'
      },
      {
        key: 'available',
        label: '空闲骑手',
        value: summary?.availableRiderCount ?? 0,
        hint: '点击只看可派单骑手',
        focus: 'rider',
        idleOnly: true
      }
    ]
  })

  const applyFocus = (metric: MetricItem) => {
    const same = focus.value === metric.focus
    if (metric.key === 'risk') riskOvertimeOnly.value = !same
    if (metric.idleOnly) riderAvailableOnly.value = !same
    focus.value = same ? '' : metric.focus
  }

  const matchText = (task: AdminTaskCard, keyword: string) => {
    const text = keyword.trim().toLowerCase()
    if (!text) return true
    return [
      task.taskNo,
      task.orderNo,
      task.areaLabel,
      task.buildingLabel,
      task.addressDetail,
      task.receiverName,
      task.receiverPhone,
      task.riderName
    ]
      .filter(Boolean)
      .join(' ')
      .toLowerCase()
      .includes(text)
  }

  const pendingTasks = computed(() => {
    const list = (board.value?.queues.pending || []).filter((task) => {
      if (!matchText(task, pendingKeyword.value)) return false
      if (pendingCold.value === 'cold' && !isColdChain(task)) return false
      if (pendingCold.value === 'normal' && isColdChain(task)) return false
      if (pendingFarOnly.value && !isFarDelivery(task)) return false
      return true
    })
    return list.sort((a, b) => {
      const left = parseTime(a.holdUntilAt)
      const right = parseTime(b.holdUntilAt)
      return (Number.isNaN(left) ? 0 : left) - (Number.isNaN(right) ? 0 : right)
    })
  })

  const riskTasks = computed(() => {
    const list = (board.value?.queues.overtimeRisk || []).filter((task) => {
      if (!matchText(task, riskKeyword.value)) return false
      if (riskRiderId.value && task.riderId !== riskRiderId.value) return false
      if (riskOvertimeOnly.value && task.overtimeRisk !== 'OVERTIME') return false
      return true
    })
    return list.sort((a, b) => {
      const left = secondsUntil(a.promisedAt, now.value) ?? a.remainingSeconds ?? 0
      const right = secondsUntil(b.promisedAt, now.value) ?? b.remainingSeconds ?? 0
      return left - right
    })
  })

  const exceptions = computed(() => {
    return (board.value?.queues.openException || [])
      .slice()
      .sort((a, b) => parseTime(a.createdAt) - parseTime(b.createdAt))
  })

  const riders = computed(() => {
    const text = riderKeyword.value.trim().toLowerCase()
    const list = (board.value?.riders || []).filter((rider) => {
      if (text && !`${rider.name} ${rider.riderNo}`.toLowerCase().includes(text)) return false
      if (riderAvailableOnly.value && riderBlockReason(rider, now.value)) return false
      return true
    })
    return list.sort((a, b) => Number(a.loadRatio || 0) - Number(b.loadRatio || 0))
  })

  const riderOptions = computed(() => board.value?.riders || [])

  const pickerRiders = computed(() => board.value?.riders || [])

  const waveOf = (rider: RiderBoardCard): BoardWaveBrief | null =>
    (board.value?.waves || []).find((wave) => wave.waveId === rider.currentWaveId) || null

  // ==================== 派单动作 ====================

  const fetchSuggestion = async (task: AdminTaskCard) => {
    suggestLoading.value = true
    try {
      const result = await suggestDispatch([task.taskId])
      const suggestion = result.suggestions?.[0] || null
      if (suggestion) suggestionMap.value[task.taskId] = suggestion
      activeSuggestion.value = suggestion
      return suggestion
    } catch (error) {
      activeSuggestion.value = null
      ElMessage.error(error instanceof Error ? error.message : '派单建议获取失败')
      return null
    } finally {
      suggestLoading.value = false
    }
  }

  /** 乐观更新：先把任务移出待派、给骑手加一单，返回回滚函数 */
  const applyOptimisticAssign = (taskId: number, riderId: number) => {
    const data = board.value
    if (!data) return () => {}
    const pending = data.queues.pending
    const index = pending.findIndex((item) => item.taskId === taskId)
    const removed = index >= 0 ? pending.splice(index, 1)[0] : null
    const rider = data.riders.find((item) => item.riderId === riderId)
    const previous = rider ? { count: rider.currentTaskCount, ratio: rider.loadRatio } : null
    if (rider) {
      rider.currentTaskCount += 1
      rider.loadRatio =
        rider.maxConcurrentTask > 0
          ? rider.currentTaskCount / rider.maxConcurrentTask
          : rider.loadRatio
    }
    if (removed && data.summary.pendingCount > 0) data.summary.pendingCount -= 1
    return () => {
      if (removed && index >= 0) pending.splice(index, 0, removed)
      if (rider && previous) {
        rider.currentTaskCount = previous.count
        rider.loadRatio = previous.ratio
      }
      if (removed) data.summary.pendingCount += 1
    }
  }

  const doAssign = async (task: AdminTaskCard, riderId: number) => {
    const rollback = applyOptimisticAssign(task.taskId, riderId)
    assigning.value = true
    try {
      await assignTask(task.taskId, { riderId })
      ElMessage.success(`${task.taskNo} 已派单`)
      await loadBoard(true)
    } catch (error) {
      rollback()
      ElMessage.error(error instanceof Error ? error.message : '派单失败')
    } finally {
      assigning.value = false
    }
  }

  const candidateSummary = (candidate: DispatchCandidate | null) => {
    if (!candidate) return '系统未返回里程预估'
    const parts = [`预计新增里程 ${distanceText(candidate.addedDistanceMeters)}`]
    if (candidate.addedDurationSeconds) {
      parts.push(`新增用时 ${Math.round(candidate.addedDurationSeconds / 60)} 分钟`)
    }
    if (candidate.estimatedArriveAt)
      parts.push(`预计送达 ${clockText(candidate.estimatedArriveAt)}`)
    return parts.join('，')
  }

  const confirmAssign = async (
    task: AdminTaskCard,
    rider: { riderId: number; name: string },
    candidate: DispatchCandidate | null
  ) => {
    await ElMessageBox.confirm(
      `把 ${task.taskNo} 派给 ${rider.name}？${candidateSummary(candidate)}。`,
      '确认派单',
      { type: 'warning', confirmButtonText: '确认派单', cancelButtonText: '取消' }
    )
    await doAssign(task, rider.riderId)
  }

  const handleAssignNow = async (task: AdminTaskCard) => {
    activeTask.value = task
    const suggestion = await fetchSuggestion(task)
    const candidate =
      suggestion?.candidates.find((item) => item.riderId === suggestion.recommendedRiderId) || null
    if (!candidate) {
      ElMessage.warning('系统暂无推荐骑手，请手动指派')
      pickerMode.value = 'assign'
      pickerVisible.value = true
      return
    }
    if (candidate.blockers.length > 0) {
      ElMessage.warning(
        `推荐骑手存在阻断项：${candidate.blockers.map(blockerText).join('、')}，请手动指派`
      )
      pickerMode.value = 'assign'
      pickerVisible.value = true
      return
    }
    try {
      await confirmAssign(
        task,
        { riderId: candidate.riderId, name: candidate.riderName },
        candidate
      )
    } catch (error) {
      if (error !== 'cancel' && error !== 'close') {
        ElMessage.error(error instanceof Error ? error.message : '派单失败')
      }
    }
  }

  const handleAssignTo = async (task: AdminTaskCard) => {
    activeTask.value = task
    pickerMode.value = 'assign'
    pickerVisible.value = true
    await fetchSuggestion(task)
  }

  const handleViewSuggest = async (task: AdminTaskCard) => {
    activeTask.value = task
    activeSuggestion.value = suggestionMap.value[task.taskId] || null
    suggestVisible.value = true
    await fetchSuggestion(task)
  }

  const adoptCandidate = async (candidate: DispatchCandidate) => {
    const task = activeTask.value
    if (!task) return
    try {
      await confirmAssign(
        task,
        { riderId: candidate.riderId, name: candidate.riderName },
        candidate
      )
      suggestVisible.value = false
    } catch (error) {
      if (error !== 'cancel' && error !== 'close') {
        ElMessage.error(error instanceof Error ? error.message : '派单失败')
      }
    }
  }

  const handleReassign = async (task: AdminTaskCard) => {
    activeTask.value = task
    pickerMode.value = 'reassign'
    pickerVisible.value = true
    await fetchSuggestion(task)
  }

  const onPickerConfirm = async (payload: { riderId: number; reason: string }) => {
    const task = activeTask.value
    if (!task) return
    const rider = board.value?.riders.find((item) => item.riderId === payload.riderId)
    const candidate =
      activeSuggestion.value?.candidates.find((item) => item.riderId === payload.riderId) || null
    try {
      if (pickerMode.value === 'reassign') {
        await ElMessageBox.confirm(
          `把 ${task.taskNo} 从 ${task.riderName || '原骑手'} 改派给 ${rider?.name || '所选骑手'}？改派会写入事件流水。`,
          '确认改派',
          { type: 'warning', confirmButtonText: '确认改派', cancelButtonText: '取消' }
        )
        assigning.value = true
        await reassignTask(task.taskId, payload.riderId, payload.reason)
        ElMessage.success('已改派')
        pickerVisible.value = false
        await loadBoard(true)
      } else {
        await confirmAssign(
          task,
          { riderId: payload.riderId, name: rider?.name || '所选骑手' },
          candidate
        )
        pickerVisible.value = false
      }
    } catch (error) {
      if (error !== 'cancel' && error !== 'close') {
        ElMessage.error(error instanceof Error ? error.message : '操作失败')
      }
    } finally {
      assigning.value = false
    }
  }

  const handleCancelTask = async (task: AdminTaskCard) => {
    try {
      const { value } = await ElMessageBox.prompt(
        `取消后订单 ${task.orderNo} 会退回备货中，请填写原因。`,
        `取消任务 ${task.taskNo}`,
        {
          type: 'warning',
          confirmButtonText: '确认取消',
          cancelButtonText: '返回',
          inputPlaceholder: '如：顾客要求改期',
          inputValidator: (input: string) => (input && input.trim() ? true : '请填写取消原因')
        }
      )
      await cancelTask(task.taskId, value.trim())
      ElMessage.success('任务已取消')
      await loadBoard(true)
    } catch (error) {
      if (error !== 'cancel' && error !== 'close') {
        ElMessage.error(error instanceof Error ? error.message : '操作失败')
      }
    }
  }

  /** 拖拽派单：本地硬校验 → 建议里程 → 二次确认 → assign，失败回滚 */
  const handleDropTask = async (payload: { riderId: number; taskId: number }) => {
    const task = draggingTask.value
    draggingTask.value = null
    const rider = board.value?.riders.find((item) => item.riderId === payload.riderId)
    if (!task || task.taskId !== payload.taskId || !rider) return

    const blockReason = riderBlockReason(rider, now.value)
    if (blockReason) {
      ElMessage.warning(`${rider.name} 当前不可接单：${blockReason}`)
      return
    }

    activeTask.value = task
    const suggestion = await fetchSuggestion(task)
    const candidate = suggestion?.candidates.find((item) => item.riderId === rider.riderId) || null
    if (candidate && candidate.blockers.length > 0) {
      ElMessage.warning(
        `${rider.name} 存在阻断项：${candidate.blockers.map(blockerText).join('、')}，已取消派单`
      )
      return
    }
    try {
      await confirmAssign(task, rider, candidate)
    } catch (error) {
      if (error !== 'cancel' && error !== 'close') {
        ElMessage.error(error instanceof Error ? error.message : '派单失败')
      }
    }
  }

  // ==================== 骑手动作 ====================

  const openTaskPicker = (rider: RiderBoardCard) => {
    if (isFatiguePaused(rider, now.value)) return
    taskPickerRider.value = rider
    taskPickerSelection.value = []
    taskPickerSlot.value = ''
    taskPickerVisible.value = true
  }

  const onTaskPickerSelect = (rows: AdminTaskCard[]) => {
    taskPickerSelection.value = rows
  }

  const confirmTaskPicker = async () => {
    const rider = taskPickerRider.value
    if (!rider || taskPickerSelection.value.length === 0) return
    const totalWeight = taskPickerSelection.value.reduce(
      (sum, task) => sum + Number(task.totalWeightKg || 0),
      0
    )
    const slot = taskPickerSlot.value
    const taskIds = taskPickerSelection.value.map((task) => task.taskId)
    try {
      await ElMessageBox.confirm(
        slot
          ? `把「${slot}」这一波 ${taskIds.length} 单（合计 ${totalWeight.toFixed(1)} kg）发给 ${rider.name}？发车后骑手可一键接单并到店取货。`
          : `把 ${taskIds.length} 个任务（合计 ${totalWeight.toFixed(1)} kg）派给 ${rider.name} 并创建波次？`,
        slot ? '按时段发车' : '批量派单',
        {
          type: 'warning',
          confirmButtonText: slot ? '确认发车' : '确认派单',
          cancelButtonText: '取消'
        }
      )
      assigning.value = true
      if (slot) {
        // 发车是整批成功或整批不动，服务端还会顺带触发路径规划
        await dispatchSlot({ riderId: rider.riderId, slotLabel: slot, taskIds })
        ElMessage.success(`「${slot}」已发车`)
      } else {
        await batchAssignTasks({ taskIds, riderId: rider.riderId })
        ElMessage.success('批量派单成功')
      }
      taskPickerVisible.value = false
      await loadBoard(true)
    } catch (error) {
      if (error !== 'cancel' && error !== 'close') {
        ElMessage.error(error instanceof Error ? error.message : slot ? '发车失败' : '批量派单失败')
      }
    } finally {
      assigning.value = false
    }
  }

  const goRiderDetail = (rider: RiderBoardCard) => {
    router.push(`/fresh/delivery/riders/${rider.riderId}`)
  }

  const openRiderMessage = (rider: RiderBoardCard) => {
    messageRider.value = { riderId: rider.riderId, riderNo: rider.riderNo, name: rider.name }
    messageVisible.value = true
  }

  const openBroadcast = () => {
    messageRider.value = null
    messageVisible.value = true
  }

  const handleForceOff = async (rider: RiderBoardCard) => {
    try {
      const { value } = await ElMessageBox.prompt(
        `强制下班会立即撤销 ${rider.name} 的登录会话，未完成任务需要改派，请填写原因。`,
        '强制下班',
        {
          type: 'warning',
          confirmButtonText: '确认下班',
          cancelButtonText: '取消',
          inputPlaceholder: '如：车辆故障需返店',
          inputValidator: (input: string) => (input && input.trim() ? true : '请填写原因')
        }
      )
      await forceRiderOffDuty(rider.riderId, value.trim())
      ElMessage.success('骑手已强制下班')
      await loadBoard(true)
    } catch (error) {
      if (error !== 'cancel' && error !== 'close') {
        ElMessage.error(error instanceof Error ? error.message : '操作失败')
      }
    }
  }

  const handleContactRider = (task: AdminTaskCard) => {
    const rider = board.value?.riders.find((item) => item.riderId === task.riderId)
    if (!rider) {
      ElMessage.warning('该任务还没有骑手')
      return
    }
    openRiderMessage(rider)
  }

  const handleContactCustomer = (task: AdminTaskCard) => {
    contactTask.value = task
    contactVisible.value = true
  }

  const copyPhone = async () => {
    const phone = contactTask.value?.receiverPhone || contactTask.value?.callNumber || ''
    if (!phone) {
      ElMessage.warning('没有可复制的号码')
      return
    }
    try {
      await navigator.clipboard.writeText(phone)
      ElMessage.success('号码已复制')
    } catch {
      ElMessage.warning('复制失败，请手动复制')
    }
  }

  const goExceptions = () => {
    router.push('/fresh/delivery/exceptions')
  }

  const onMapSelect = (payload: { type: 'rider' | 'task' | 'stop'; id: number }) => {
    if (payload.type === 'rider') {
      activeRiderId.value = payload.id
      const rider = board.value?.riders.find((item) => item.riderId === payload.id)
      if (rider) riderKeyword.value = rider.name
    }
    if (payload.type === 'task') {
      activeTaskId.value = payload.id
      const task = [
        ...(board.value?.queues.pending || []),
        ...(board.value?.queues.overtimeRisk || [])
      ].find((item) => item.taskId === payload.id)
      if (task) {
        pendingKeyword.value = task.taskNo
        riskKeyword.value = task.taskNo
      }
    }
  }

  // ==================== 生命周期：定时器与 SSE 必须成对释放 ====================

  const startTimers = () => {
    if (tickTimer === null) {
      tickTimer = window.setInterval(() => {
        now.value = Date.now() - serverOffset
      }, 1000)
    }
    if (mapTimer === null) {
      mapTimer = window.setInterval(() => {
        void loadMap()
      }, 5000)
    }
  }

  const stopTimers = () => {
    if (tickTimer !== null) {
      window.clearInterval(tickTimer)
      tickTimer = null
    }
    if (mapTimer !== null) {
      window.clearInterval(mapTimer)
      mapTimer = null
    }
  }

  const activate = async () => {
    if (started) return
    started = true
    startTimers()
    startStream()
    await Promise.all([loadBoard(), loadConfigs()])
    await loadMap()
  }

  const deactivate = () => {
    if (!started) return
    started = false
    stopTimers()
    stopStream()
    draggingTask.value = null
  }

  watch(mapCollapsed, (collapsed) => {
    if (!collapsed) void loadMap()
  })

  onMounted(() => {
    void activate()
  })
  onActivated(() => {
    void activate()
  })
  onDeactivated(deactivate)
  onUnmounted(deactivate)
</script>

<style scoped lang="scss">
  @use '../../style.scss';

  .board-page {
    height: 100%;
  }

  .board-actions {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 10px;
  }

  .board-clock {
    color: var(--art-gray-800);
    font-size: 15px;
    font-weight: 700;
    font-variant-numeric: tabular-nums;
  }

  .board-switch {
    display: flex;
    align-items: center;
    gap: 6px;
    color: var(--art-gray-600);
    font-size: 13px;
  }

  .board-metrics {
    display: grid;
    grid-template-columns: repeat(6, minmax(0, 1fr));
    gap: 12px;
  }

  .board-metric {
    width: 100%;
    padding: 14px 16px;
    text-align: left;
    font: inherit;
    cursor: pointer;
    appearance: none;
    transition:
      border-color 0.2s ease,
      box-shadow 0.2s ease,
      transform 0.2s ease;

    &:hover {
      border-color: rgb(0 132 61 / 28%);
      transform: translateY(-2px);
    }

    &--active {
      border-color: var(--el-color-primary);
      box-shadow: 0 0 0 2px color-mix(in srgb, var(--el-color-primary) 20%, transparent);
    }
  }

  .board-metric__hint {
    margin-top: 6px;
    color: var(--art-gray-500);
    font-size: 12px;
  }

  .board-metric__value--danger {
    color: var(--el-color-danger);
  }

  .board-body {
    display: grid;
    grid-template-columns: 2fr 1fr;
    gap: 16px;
    align-items: start;
    min-height: 0;

    &--full {
      grid-template-columns: 1fr auto;
    }
  }

  .board-queues {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 16px;
    min-height: 420px;
  }

  .queue-col {
    display: flex;
    flex-direction: column;
    min-height: 320px;
    overflow: hidden;
    border: 1px solid var(--art-border-color);
    border-radius: 12px;
    background: var(--art-main-bg-color);

    &--focus {
      border-color: var(--el-color-primary);
      box-shadow: 0 0 0 2px color-mix(in srgb, var(--el-color-primary) 16%, transparent);
    }
  }

  .queue-col__head {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 8px;
    padding: 12px 14px;
    border-bottom: 1px solid var(--art-border-color);

    strong {
      color: var(--art-gray-900);
      font-size: 15px;
    }
  }

  .queue-col__hint {
    margin-left: auto;
    color: var(--art-gray-500);
    font-size: 12px;
  }

  .queue-col__dot {
    width: 10px;
    height: 10px;
    border-radius: 50%;

    &--pending {
      background: var(--el-color-danger);
    }

    &--risk {
      background: var(--el-color-warning);
    }

    &--exception {
      background: #7b61ff;
    }

    &--rider {
      background: var(--el-color-primary);
    }
  }

  .queue-col__filter {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 8px;
    padding: 10px 14px;
    border-bottom: 1px dashed var(--art-border-color);
    background: var(--el-fill-color-lighter);

    :deep(.el-input) {
      width: 190px;
    }
  }

  .queue-col__select {
    width: 130px;
  }

  .queue-col__body {
    display: grid;
    align-content: start;
    gap: 10px;
    max-height: 46vh;
    padding: 12px 14px;
    overflow-y: auto;
  }

  .board-map {
    display: flex;
    flex-direction: column;
    gap: 8px;
    height: calc(46vh + 130px);
    padding: 12px;
    border: 1px solid var(--art-border-color);
    border-radius: 12px;
    background: var(--art-main-bg-color);
  }

  .board-map__head {
    display: flex;
    align-items: center;
    gap: 10px;

    strong {
      color: var(--art-gray-900);
    }

    span {
      font-size: 12px;
    }

    :deep(.el-button) {
      margin-left: auto;
    }
  }

  .board-map__expand {
    align-self: start;
  }

  .contact-box {
    display: grid;
    gap: 10px;

    > div {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 16px;
    }

    span {
      color: var(--art-gray-600);
      font-size: 13px;
    }

    strong {
      color: var(--art-gray-900);
      overflow-wrap: anywhere;
    }
  }

  .task-picker__tip {
    margin-bottom: 12px;
  }

  .task-picker__slot {
    display: flex;
    align-items: center;
    gap: 12px;
    margin-bottom: 12px;
  }

  .task-picker__slot-label {
    color: var(--art-text-gray-600);
    font-size: 13px;
    white-space: nowrap;
  }

  .task-picker__slot-select {
    width: 320px;
  }

  @media (max-width: 1400px) {
    .board-metrics {
      grid-template-columns: repeat(3, minmax(0, 1fr));
    }

    .board-body {
      grid-template-columns: 1fr;
    }

    .board-map {
      height: 420px;
    }
  }

  @media (max-width: 900px) {
    .board-queues {
      grid-template-columns: 1fr;
    }

    .board-metrics {
      grid-template-columns: repeat(2, minmax(0, 1fr));
    }
  }
</style>
