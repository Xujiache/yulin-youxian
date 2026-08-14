/**
 * 配送域管理端 API 封装
 *
 * 契约来源：docs/rider/04-API契约.md §0 / §2 / §四（冻结契约）
 * 全部接口挂在 /api/admin/delivery/** 下，复用 src/utils/http 的令牌注入与响应解包。
 * 金额一律为整数（分）；时间为 ISO-8601 本地时间字符串（Asia/Shanghai，无时区后缀）。
 */
import request from '@/utils/http'
import { useUserStore } from '@/store/modules/user'
import type { PageResult, BatchOrderActionResult } from './admin'

// ==================== 通用类型（04 §0） ====================

/** 全系统统一坐标，GCJ-02 */
export interface GeoPoint {
  lat: number
  lng: number
}

/** 配送任务状态 */
export type DeliveryTaskStatus =
  | 'PENDING'
  | 'ASSIGNED'
  | 'ACCEPTED'
  | 'PICKED_UP'
  | 'DELIVERING'
  | 'ARRIVED'
  | 'DELIVERED'
  | 'RETURNED'
  | 'CANCELLED'

/** 超时风险等级 */
export type OvertimeRisk = 'LOW' | 'MEDIUM' | 'HIGH' | 'OVERTIME'

/** 疲劳等级 */
export type FatigueLevel = 'NONE' | 'WARN_4H' | 'CONFIRM_8H' | 'FORCE_12H'

/** 冷链等级（契约样例为 FROZEN，保留任意字符串兼容） */
export type ColdChainLevel = 'NORMAL' | 'CHILLED' | 'FROZEN' | (string & {})

// ==================== 调度看板（04 §2.1） ====================

/** 骑手端 TaskCard 基础字段（04 §1.3） */
export interface DeliveryTaskCard {
  taskId: number
  taskNo: string
  orderNo: string
  status: DeliveryTaskStatus
  statusText: string
  seqNo: number | null
  totalStops: number | null
  receiverName: string
  receiverPhoneMasked: string
  callNumber: string
  phoneDegraded: boolean
  addressDetail: string
  areaLabel: string
  buildingLabel: string
  unitNo: number | null
  floorNo: number | null
  roomNo: string
  location: GeoPoint | null
  distanceFromRiderMeters: number | null
  itemCount: number
  totalWeightKg: number
  packageCount: number
  coldChainLevel: ColdChainLevel
  coldChainText: string
  goodsSummary: string
  customerRemark: string
  deliveryInstruction: string
  highlightNotes: string[]
  /** 预约时段。delivery_task.slot_label 可空，老单没有时段 */
  slotLabel: string | null
  promisedAt: string
  etaAt: string | null
  remainingSeconds: number | null
  overtimeRisk: OvertimeRisk
  requireVerifyCode: boolean
  requirePhoto: boolean
  sameAddressTaskCount: number
}

/** AdminTaskCard = TaskCard + 后台可见字段（04 §2.1） */
export interface AdminTaskCard extends DeliveryTaskCard {
  receiverPhone: string
  riderId: number | null
  riderName: string | null
  dispatchScore: number | null
  reassignCount: number
  holdUntilAt: string | null
  createdAt: string
  pickedReadyAt: string | null
  waveNo: string | null
}

/** 看板骑手卡（04 §2.1 RiderBoardCard） */
export interface RiderBoardCard {
  riderId: number
  riderNo: string
  name: string
  avatarUrl: string
  workStatus: string
  onDutySeconds: number
  location: GeoPoint | null
  locatedAt: string | null
  /** 超过 120 秒无上报为 true，前端标红 */
  locationStale: boolean
  batteryLevel: number | null
  currentWaveId: number | null
  currentTaskCount: number
  maxConcurrentTask: number
  loadRatio: number
  currentWeightKg: number
  capacityWeightKg: number
  todayDeliveredCount: number
  todayOnTimeRate: number
  serviceScore: number
  probation: boolean
  fatigueLevel: FatigueLevel
  dispatchPausedUntil: string | null
  planReturnAt: string | null
}

/** 看板指标汇总 */
export interface BoardSummary {
  pendingCount: number
  assignedCount: number
  deliveringCount: number
  overtimeRiskCount: number
  openExceptionCount: number
  onDutyRiderCount: number
  availableRiderCount: number
  capacityWarning: boolean
  avgDeliveryMinutes: number
  onTimeRateToday: number
}

/** 看板四列队列 */
export interface BoardQueues {
  pending: AdminTaskCard[]
  overtimeRisk: AdminTaskCard[]
  /** 后端看板返回异常中的任务卡；异常实体需进入异常页查询 */
  openException: AdminTaskCard[]
  idleRiders: RiderBoardCard[]
}

/** 看板内的活跃波次摘要（DeliveryBoardDto.WaveBriefDto） */
export interface BoardWaveBrief {
  waveId: number
  waveNo: string
  riderId: number | null
  riderName: string | null
  status: string
  taskCount: number
  completedCount: number
  planDistanceMeters: number
  planReturnAt: string | null
  maxColdChainLevel: ColdChainLevel | null
}

/** GET /board 全量数据 */
export interface DeliveryBoardData {
  serverTime: string
  summary: BoardSummary
  queues: BoardQueues
  riders: RiderBoardCard[]
  waves: BoardWaveBrief[]
}

/** 地图轻量接口的骑手点 */
export interface MapRiderPoint {
  riderId: number
  lat: number
  lng: number
  bearing: number | null
  locatedAt: string
}

/** 地图轻量接口的任务点 */
export interface MapTaskPoint {
  taskId: number
  lat: number
  lng: number
  status: DeliveryTaskStatus
}

/** GET /map 响应 */
export interface DeliveryMapSnapshot {
  riders: MapRiderPoint[]
  tasks: MapTaskPoint[]
}

export function getDeliveryBoard() {
  return request.get<DeliveryBoardData>({
    url: '/api/admin/delivery/board'
  })
}

export function getDeliveryMap() {
  return request.get<DeliveryMapSnapshot>({
    url: '/api/admin/delivery/map'
  })
}

// ==================== 拣货与派单（04 §2.2） ====================

/** 拣货称重明细 */
export interface WeightCheckInput {
  orderItemId: number
  productName: string
  orderedQty: number
  pickedWeightKg: number
  scaleEvidenceId: number | null
}

/** POST /orders/{id}/pick-ready 请求 */
export interface PickReadyPayload {
  itemCount: number
  totalWeightKg: number
  packageCount: number
  coldChainLevel: ColdChainLevel
  weightChecks: WeightCheckInput[]
  autoDispatch: boolean
  /** 拣货备注，落在任务事件流水里，不影响派单 */
  remark?: string
}

/** 派单预览 */
export interface DispatchPreview {
  recommendedRiderId: number | null
  recommendedRiderName: string | null
  score: number | null
  reason: string
}

/** POST /orders/{id}/pick-ready 响应 */
export interface PickReadyResult {
  taskId: number
  taskNo: string
  status: DeliveryTaskStatus
  holdUntilAt: string | null
  dispatchPreview: DispatchPreview | null
}

export function pickReadyOrder(orderId: number, data: PickReadyPayload) {
  return request.post<PickReadyResult>({
    url: `/api/admin/delivery/orders/${orderId}/pick-ready`,
    data
  })
}

export function batchPickReadyOrders(orderIds: number[], autoDispatch = true) {
  return request.post<BatchOrderActionResult>({
    url: '/api/admin/delivery/orders/batch-pick-ready',
    data: { orderIds, autoDispatch }
  })
}

/** POST /tasks/{id}/assign 请求 */
export interface AssignTaskPayload {
  riderId: number
  /** true 时忽略并发上限与疲劳限制（仍留痕）；疲劳停派前端不应放开 */
  force?: boolean
  reason?: string
}

export function assignTask(taskId: number, data: AssignTaskPayload) {
  return request.post<void>({
    url: `/api/admin/delivery/tasks/${taskId}/assign`,
    data
  })
}

/**
 * POST /tasks/batch-assign 请求。
 *
 * 批量指派一定会建或并波次。原来有个 createWave 开关，但服务端从来没读过它，
 * 传 false 也照样建波次，已经去掉。
 */
export interface BatchAssignPayload {
  taskIds: number[]
  riderId: number
}

export function batchAssignTasks(data: BatchAssignPayload) {
  return request.post<void>({
    url: '/api/admin/delivery/tasks/batch-assign',
    data
  })
}

/** POST /waves/dispatch-slot 请求 */
export interface SlotDispatchPayload {
  riderId: number
  /** 期望时段。服务端会与任务实际时段核对，对不上直接拒绝 */
  slotLabel?: string
  taskIds: number[]
}

export interface SlotDispatchResult {
  waveId: number
  waveCreated: boolean
  riderId: number
  deliveryDate: string
  slotLabel: string | null
  taskIds: number[]
}

/**
 * 按时段发车：把一个时段备好的单一次性发给一个骑手。
 *
 * 与 batchAssignTasks 的区别是它不走打分和聚类 —— 店主已经决定好给谁，
 * 系统只负责原样落库并触发路径规划。整批成功或整批不动。
 */
export function dispatchSlot(data: SlotDispatchPayload) {
  return request.post<SlotDispatchResult>({
    url: '/api/admin/delivery/waves/dispatch-slot',
    data
  })
}

export function reassignTask(taskId: number, toRiderId: number, reason: string) {
  return request.post<void>({
    url: `/api/admin/delivery/tasks/${taskId}/reassign`,
    data: { toRiderId, reason }
  })
}

export function cancelTask(taskId: number, reason: string) {
  return request.post<void>({
    url: `/api/admin/delivery/tasks/${taskId}/cancel`,
    data: { reason }
  })
}

/** 派单建议得分拆解 */
export interface DispatchScoreBreakdown {
  addedDistance: number
  overtimeRisk: number
  loadBalance: number
  coldChain: number
  riderLevel: number
}

/** 派单候选骑手 */
export interface DispatchCandidate {
  riderId: number
  riderName: string
  score: number
  addedDistanceMeters: number
  addedDurationSeconds: number
  estimatedArriveAt: string | null
  overtimeRiskAfter: OvertimeRisk
  breakdown: DispatchScoreBreakdown
  /** 如 ["FATIGUE_PAUSED"] */
  blockers: string[]
}

/** 并单提示 */
export interface BatchingHint {
  mergeWithTaskIds: number[]
  reason: string
}

/** 单个任务的建议 */
export interface DispatchSuggestion {
  taskId: number
  candidates: DispatchCandidate[]
  recommendedRiderId: number | null
  batchingHint: BatchingHint | null
}

/** POST /dispatch/suggest 响应 */
export interface DispatchSuggestResult {
  suggestions: DispatchSuggestion[]
}

export function suggestDispatch(taskIds: number[]) {
  return request.post<DispatchSuggestResult>({
    url: '/api/admin/delivery/dispatch/suggest',
    data: { taskIds }
  })
}

/** 手动触发一次调度循环（调试用） */
export function runDispatchNow() {
  return request.post<void>({
    url: '/api/admin/delivery/dispatch/run-now'
  })
}

// ==================== 配送任务列表与事件（04 §2.2 补充） ====================
//
// 契约 §2.2 只冻结了派单类写接口，配送任务页需要的列表 / 详情 / 事件流水三个读接口
// 按同一命名风格补齐（路径与 delivery_task、delivery_task_event 表一一对应）。

export interface DeliveryTaskListParams {
  /** DeliveryTaskStatus，缺省为全部 */
  status?: string
  /** yyyy-MM-dd，按承诺送达日筛选 */
  date?: string
  riderId?: number
  waveId?: number
  /** 任务号、订单号、收货人、电话、地址 */
  keyword?: string
  page?: number
  pageSize?: number
}

/** 任务详情中的商品明细 */
export interface DeliveryTaskItem {
  productName: string
  quantity: number
  unit: string
  weightKg: number | null
  /** 金额单位分 */
  amount: number | null
}

/** 任务事件流水（TaskDetailDto.TaskEventDto） */
export interface DeliveryTaskEvent {
  id: number
  eventType: string
  fromStatus: string | null
  toStatus: string | null
  operatorType: string
  operatorName: string | null
  reason: string | null
  clientEventAt: string | null
  createdAt: string
}

/** GET /tasks/{id} 返回的是详情信封，不是任务卡本身 */
export interface DeliveryTaskDetail {
  card: DeliveryTaskCard
  items: DeliveryTaskItem[]
  events: DeliveryTaskEvent[]
  evidences: DeliveryEvidence[]
}

export function getDeliveryTasks(params?: DeliveryTaskListParams) {
  return request.get<PageResult<DeliveryTaskCard>>({
    url: '/api/admin/delivery/tasks',
    params
  })
}

export function getDeliveryTaskDetail(taskId: number) {
  return request.get<DeliveryTaskDetail>({
    url: `/api/admin/delivery/tasks/${taskId}`
  })
}

// ==================== 波次与路线（04 §2.3） ====================

/** 波次概览（WaveDetailDto 的列表可见字段） */
export interface WaveSummary {
  waveId: number
  waveNo: string
  riderId: number | null
  riderName: string | null
  status: string
  deliveryDate: string
  /** 配送时段。按时段发车的波次才有，老波次为空 */
  slotLabel: string | null
  taskCount: number
  completedCount: number
  totalWeightKg: number
  totalItemCount: number
  maxColdChainLevel: ColdChainLevel | null
  planDistanceMeters: number | null
  planDurationSeconds: number | null
  actualDistanceMeters: number | null
  planReturnAt: string | null
  optimizerName: string | null
  matrixProvider: string | null
  assignedAt: string | null
  startedAt: string | null
  completedAt: string | null
  /** 骑手确认回店的时间。状态为 RETURNING 且此项为空表示送完了还没回店 */
  returnedAt: string | null
}

/** 波次站点（含规划信息，04 §1.3 route.stops） */
export interface WaveStop {
  taskId: number
  seqNo: number
  originalSeqNo: number
  location: GeoPoint
  legDistanceMeters: number
  legDurationSeconds: number
  handoffEstimateSeconds: number
  planArriveAt: string | null
  planDepartAt: string | null
  actualArriveAt: string | null
  actualDepartAt: string | null
  adjustedByRider: boolean
}

/** 路线规划快照中的站点字段少于 WaveDetailDto.stops */
export interface WaveRouteStop {
  taskId: number
  seqNo: number
  location: GeoPoint
  legDistanceMeters: number
  legDurationSeconds: number
  handoffEstimateSeconds: number
  planArriveAt: string | null
  planDepartAt: string | null
}

/**
 * 轨迹点。骑手轨迹带速度/朝向/运动状态；波次轨迹只有坐标和时间，
 * 所以除 lat/lng/locatedAt 外都是可选的。
 */
export interface TrackPoint {
  lat: number
  lng: number
  bearing?: number | null
  speedMps?: number | null
  /** STILL / WALKING / RIDING */
  motionState?: string | null
  locatedAt: string
}

/** 波次路线规划结果 */
export interface WaveRoutePlan {
  waveId: number
  planVersion: number
  optimizerName: string
  matrixProvider: string
  origin: GeoPoint & { name?: string }
  stops: WaveRouteStop[]
  totalDistanceMeters: number
  totalDurationSeconds: number
  /** 编码折线；无高德 Key 时为直线段串联 */
  polyline: string
}

/** 波次详情：全部站点 + 路线 + 实际轨迹 */
export interface WaveDetail extends WaveSummary {
  stops: WaveStop[]
  route: WaveRoutePlan | null
  track: TrackPoint[]
}

/** 轨迹回放数据 */
export interface WaveReplayData {
  waveId: number
  waveNo: string
  speed: number
  from: string | null
  to: string | null
  points: TrackPoint[]
  stops: WaveStop[]
}

export interface WaveListParams {
  date?: string
  status?: string
  riderId?: number
  slotLabel?: string
  page?: number
  pageSize?: number
}

export function getWaves(params?: WaveListParams) {
  return request.get<PageResult<WaveDetail>>({
    url: '/api/admin/delivery/waves',
    params
  })
}

export function getWaveDetail(waveId: number) {
  return request.get<WaveDetail>({
    url: `/api/admin/delivery/waves/${waveId}`
  })
}

export function createWave(riderId: number, taskIds: number[]) {
  return request.post<WaveDetail>({
    url: '/api/admin/delivery/waves',
    data: { riderId, taskIds }
  })
}

export function replanWave(waveId: number, reason: string) {
  return request.post<void>({
    url: `/api/admin/delivery/waves/${waveId}/replan`,
    data: { reason }
  })
}

export function updateWaveSequence(waveId: number, taskIds: number[]) {
  return request.put<WaveDetail>({
    url: `/api/admin/delivery/waves/${waveId}/sequence`,
    data: { taskIds }
  })
}

export function cancelWave(waveId: number, reason?: string) {
  return request.post<void>({
    url: `/api/admin/delivery/waves/${waveId}/cancel`,
    data: reason ? { reason } : undefined
  })
}

export function getWaveReplay(waveId: number, speed?: number) {
  return request.get<WaveReplayData>({
    url: `/api/admin/delivery/waves/${waveId}/replay`,
    params: speed ? { speed } : undefined
  })
}

// ==================== 骑手管理（04 §2.4） ====================

/** 管理端骑手 DTO（RiderAdminDto，统计字段为扁平结构） */
export interface AdminRider {
  id: number
  riderNo: string
  name: string
  phone: string
  avatarUrl: string
  idCardMasked: string
  role: string
  accountStatus: string
  workStatus: string
  healthCertNo: string | null
  healthCertExpireAt: string | null
  vehiclePlate: string
  vehicleType: string
  maxConcurrentTask: number
  capacityWeightKg: number | null
  insulatedBoxCount: number
  probation: boolean
  hiredAt: string | null
  serviceScore: number
  levelCode: string
  totalTaskCount: number
  onTimeTaskCount: number
  onTimeRate: number
  locationConsentAt: string | null
  remark: string | null
  createdAt: string
  todayDeliveredCount: number
  todayOnTimeRate: number
  weekDeliveredCount: number
  weekOnTimeRate: number
  /** 仅新建接口返回，列表与详情为 null */
  initialPassword: string | null
}

/** 新建/编辑骑手请求 */
export interface RiderUpsertPayload {
  name: string
  phone: string
  vehicleType: string
  vehiclePlate?: string
  maxConcurrentTask?: number
  capacityWeightKg?: number
  healthCertExpireAt?: string
  probation?: boolean
}

/** 新建骑手响应仍是 RiderAdminDto，initialPassword 仅此一次有值 */
export type RiderCreateResult = AdminRider

/** 班次记录（04 §1.2 shift 字段） */
export interface RiderShift {
  shiftId: number
  shiftDate: string
  onDutyAt: string
  offDutyAt: string | null
  offDutyReason: string | null
  onlineSeconds: number
  restTotalSeconds: number
  taskCount: number
  deliveredCount: number
  onTimeCount: number
  exceptionCount: number
  mileageMeters: number
  earningAmount: number
}

export interface RiderListParams {
  status?: string
  keyword?: string
  page?: number
  pageSize?: number
}

export function getRiders(params?: RiderListParams) {
  return request.get<PageResult<AdminRider>>({
    url: '/api/admin/delivery/riders',
    params
  })
}

export function createRider(data: RiderUpsertPayload) {
  return request.post<RiderCreateResult>({
    url: '/api/admin/delivery/riders',
    data
  })
}

export function getRiderDetail(riderId: number) {
  return request.get<AdminRider>({
    url: `/api/admin/delivery/riders/${riderId}`
  })
}

export function updateRider(riderId: number, data: RiderUpsertPayload) {
  return request.put<AdminRider>({
    url: `/api/admin/delivery/riders/${riderId}`,
    data
  })
}

export function resetRiderPassword(riderId: number) {
  return request.post<{ initialPassword: string }>({
    url: `/api/admin/delivery/riders/${riderId}/reset-password`
  })
}

export function suspendRider(riderId: number) {
  return request.post<AdminRider>({
    url: `/api/admin/delivery/riders/${riderId}/suspend`
  })
}

export function activateRider(riderId: number) {
  return request.post<AdminRider>({
    url: `/api/admin/delivery/riders/${riderId}/activate`
  })
}

/** 强制下班，同时撤销骑手会话 */
export function forceRiderOffDuty(riderId: number, reason: string) {
  return request.post<AdminRider>({
    url: `/api/admin/delivery/riders/${riderId}/force-off-duty`,
    data: { reason }
  })
}

export interface RiderTrackParams {
  date?: string
  from?: string
  to?: string
}

/** 历史轨迹响应：点集外面包了一层，取点要读 points */
export interface RiderTrack {
  riderId: number
  /** yyyy-MM-dd，服务端回显实际查询的那一天 */
  date: string
  points: TrackPoint[]
}

/** 历史轨迹（服务端降采样，最多 1000 点） */
export function getRiderTrack(riderId: number, params?: RiderTrackParams) {
  return request.get<RiderTrack>({
    url: `/api/admin/delivery/riders/${riderId}/track`,
    params
  })
}

export function getRiderShifts(riderId: number, params?: { from?: string; to?: string }) {
  return request.get<RiderShift[]>({
    url: `/api/admin/delivery/riders/${riderId}/shifts`,
    params
  })
}

// ==================== 异常、凭证、公平秤（04 §2.5） ====================

/** 配送异常（后台视角） */
export interface DeliveryExceptionItem {
  exceptionId: number
  exceptionNo: string
  taskId: number | null
  exceptionType: string
  severity: string
  status: string
  description: string
  riderExempt: boolean
  holdUntilAt: string | null
  guidance: string | null
  allowedNextActions: string[]
  resolutionType: string | null
  resolutionNote: string | null
  createdAt: string
}

/** 异常处理请求 */
export interface ExceptionHandlePayload {
  /** CONTINUE / RETURN / REASSIGN / REFUND / CANCEL / IGNORE */
  resolutionType: string
  resolutionNote?: string
  /** 是否免除骑手责任（UI 默认勾选） */
  riderExempt: boolean
}

export interface ExceptionListParams {
  status?: string
  type?: string
  severity?: string
  page?: number
  pageSize?: number
}

export function getExceptions(params?: ExceptionListParams) {
  return request.get<PageResult<DeliveryExceptionItem>>({
    url: '/api/admin/delivery/exceptions',
    params
  })
}

export function getExceptionDetail(id: number) {
  return request.get<DeliveryExceptionItem>({
    url: `/api/admin/delivery/exceptions/${id}`
  })
}

export function handleException(id: number, data: ExceptionHandlePayload, toRiderId?: number) {
  return request.post<DeliveryExceptionItem>({
    url: `/api/admin/delivery/exceptions/${id}/handle`,
    data,
    params: toRiderId === undefined ? undefined : { toRiderId }
  })
}

/** 配送凭证 */
export interface DeliveryEvidence {
  id: number
  fileUrl: string
  evidenceType: string
  capturedAt: string | null
}

export function getEvidences(params?: { taskId?: number; type?: string; exceptionId?: number }) {
  return request.get<DeliveryEvidence[]>({
    url: '/api/admin/delivery/evidences',
    params
  })
}

/** 公平秤记录 */
export interface WeightCheckItem {
  id: number
  taskId: number
  taskNo: string
  orderNo: string
  orderItemId: number
  productName: string
  orderedQty: number
  pickedWeightKg: number
  scaleEvidenceId: number | null
  scaleEvidenceUrl: string | null
  verdict: string | null
  refundAmount: number | null
  note: string | null
  createdAt: string
}

/** 公平秤裁定请求 */
export interface WeightCheckJudgePayload {
  /** 如 AUTO_REFUND */
  verdict: string
  /** 单位分 */
  refundAmount?: number
  note?: string
}

export function getWeightChecks(params?: { verdict?: string; page?: number; pageSize?: number }) {
  return request.get<PageResult<WeightCheckItem>>({
    url: '/api/admin/delivery/weight-checks',
    params
  })
}

export function judgeWeightCheck(id: number, data: WeightCheckJudgePayload) {
  return request.post<void>({
    url: `/api/admin/delivery/weight-checks/${id}/judge`,
    data
  })
}

// ==================== 结算与服务分（04 §2.6） ====================

/** 骑手结算单（金额单位分） */
export interface RiderSettlement {
  id: number
  settlementNo: string
  riderId: number
  riderName: string
  periodType: string
  periodStart: string
  periodEnd: string
  status: string
  taskCount: number
  baseAmount: number
  bonusAmount: number
  deductionAmount: number
  adjustAmount: number
  totalAmount: number
  remark: string | null
  confirmedAt: string | null
  paidAt: string | null
  createdAt: string
}

export interface SettlementListParams {
  riderId?: number
  period?: string
  status?: string
  page?: number
  pageSize?: number
}

export interface SettlementGeneratePayload {
  periodType: string
  periodStart: string
  periodEnd: string
  riderIds?: number[]
}

export interface SettlementGenerateResult {
  generatedCount: number
  settlementIds: number[]
}

/** CSV 导出（沿用现有 dashboard 导出风格） */
export interface SettlementExport {
  filename: string
  content: string
}

export function getSettlements(params?: SettlementListParams) {
  return request.get<PageResult<RiderSettlement>>({
    url: '/api/admin/delivery/settlements',
    params
  })
}

export function generateSettlements(data: SettlementGeneratePayload) {
  return request.post<SettlementGenerateResult>({
    url: '/api/admin/delivery/settlements/generate',
    data
  })
}

export function confirmSettlement(id: number) {
  return request.post<RiderSettlement>({
    url: `/api/admin/delivery/settlements/${id}/confirm`
  })
}

export function paySettlement(id: number) {
  return request.post<RiderSettlement>({
    url: `/api/admin/delivery/settlements/${id}/pay`
  })
}

export function voidSettlement(id: number) {
  return request.post<RiderSettlement>({
    url: `/api/admin/delivery/settlements/${id}/void`
  })
}

export function adjustSettlement(id: number, adjustAmount: number, remark: string) {
  return request.put<RiderSettlement>({
    url: `/api/admin/delivery/settlements/${id}/adjust`,
    data: { adjustAmount, remark }
  })
}

export function exportSettlement(id: number) {
  return request.get<SettlementExport>({
    url: `/api/admin/delivery/settlements/${id}/export`
  })
}

/** 服务分流水 */
export interface ScoreEvent {
  id: number
  riderId: number
  riderName: string
  scoreDelta: number
  reason: string
  sourceType: string | null
  createdAt: string
}

export function getScoreEvents(params?: { riderId?: number; page?: number; pageSize?: number }) {
  return request.get<PageResult<ScoreEvent>>({
    url: '/api/admin/delivery/score-events',
    params
  })
}

/** 人工加减分 */
export function createScoreEvent(riderId: number, scoreDelta: number, reason: string) {
  return request.post<ScoreEvent>({
    url: '/api/admin/delivery/score-events',
    data: { riderId, scoreDelta, reason }
  })
}

/** 骑手申诉 */
export interface RiderAppeal {
  id: number
  riderId: number
  riderName: string
  targetType: string
  targetId: number
  reason: string
  evidenceIds: number[]
  status: string
  reviewNote: string | null
  reviewedAt: string | null
  createdAt: string
}

export function getAppeals(params?: { status?: string; page?: number; pageSize?: number }) {
  return request.get<PageResult<RiderAppeal>>({
    url: '/api/admin/delivery/appeals',
    params
  })
}

export function reviewAppeal(id: number, approved: boolean, reviewNote?: string) {
  return request.post<void>({
    url: `/api/admin/delivery/appeals/${id}/review`,
    data: { approved, reviewNote }
  })
}

// ==================== 配置、区域、分析、消息（04 §2.7） ====================

/** 配置项元数据：前端按 category 分组动态渲染，不硬编码字段 */
export interface DeliveryConfigItem {
  key: string
  value: string
  category: string
  displayName: string
  description: string
  valueType: string
  minValue: number | null
  maxValue: number | null
  editable: boolean
}

export function getDeliveryConfigs(category?: string) {
  return request.get<DeliveryConfigItem[]>({
    url: '/api/admin/delivery/configs',
    params: category ? { category } : undefined
  })
}

export function updateDeliveryConfigs(items: Array<{ key: string; value: string }>) {
  return request.put<void>({
    url: '/api/admin/delivery/configs',
    data: { items }
  })
}

/** 配送区域 */
export interface DeliveryZone {
  id?: number
  name: string
  color?: string
  polygon: GeoPoint[]
  enabled: boolean
  remark?: string
}

export function getDeliveryZones() {
  return request.get<DeliveryZone[]>({
    url: '/api/admin/delivery/zones'
  })
}

export function createDeliveryZone(data: DeliveryZone) {
  return request.post<DeliveryZone>({
    url: '/api/admin/delivery/zones',
    data
  })
}

export function updateDeliveryZone(id: number, data: DeliveryZone) {
  return request.put<DeliveryZone>({
    url: `/api/admin/delivery/zones/${id}`,
    data
  })
}

export function deleteDeliveryZone(id: number) {
  return request.del<void>({
    url: `/api/admin/delivery/zones/${id}`
  })
}

/** 分析总览的趋势点 */
export interface AnalyticsDailyPoint {
  date: string
  taskCount: number
  deliveredCount: number
  onTimeRate: number
  avgDeliveryMinutes: number
}

/** 异常类型分布 */
export interface AnalyticsExceptionSlice {
  exceptionType: string
  count: number
}

/** 楼栋难度榜条目（交付时长 top） */
export interface BuildingDifficultyItem {
  areaLabel: string
  buildingLabel: string
  taskCount: number
  avgHandoffSeconds: number
}

/** 时段单量热力 */
export interface HourlyHeatPoint {
  hour: number
  taskCount: number
}

/** GET /analytics/overview 响应 */
export interface DeliveryAnalyticsOverview {
  onTimeRate: number
  avgDeliveryMinutes: number
  totalTaskCount: number
  dailyTrend: AnalyticsDailyPoint[]
  exceptionDistribution: AnalyticsExceptionSlice[]
  buildingDifficultyTop: BuildingDifficultyItem[]
  hourlyHeatmap: HourlyHeatPoint[]
}

/** 骑手人效条目 */
export interface RiderAnalyticsItem {
  riderId: number
  riderName: string
  deliveredCount: number
  onTimeRate: number
  avgDeliveryMinutes: number
  mileageMeters: number
  earningAmount: number
  exceptionCount: number
  serviceScore: number
}

export function getDeliveryAnalyticsOverview(params?: { from?: string; to?: string }) {
  return request.get<DeliveryAnalyticsOverview>({
    url: '/api/admin/delivery/analytics/overview',
    params
  })
}

export function getDeliveryAnalyticsRiders(params?: { from?: string; to?: string }) {
  return request.get<RiderAnalyticsItem[]>({
    url: '/api/admin/delivery/analytics/riders',
    params
  })
}

/** 骑手消息广播 */
export interface BroadcastPayload {
  title: string
  content: string
  /** 缺省为全体在岗骑手 */
  riderIds?: number[]
  priority: string
  needVoice: boolean
}

export function broadcastMessage(data: BroadcastPayload) {
  return request.post<void>({
    url: '/api/admin/delivery/messages/broadcast',
    data
  })
}

// ==================== 订单页集成（04 §四） ====================

/** 单个订单的配送简报 */
export interface OrderDeliveryBrief {
  taskId: number
  taskNo: string
  status: DeliveryTaskStatus
  statusText: string
  riderId: number | null
  riderName: string | null
  waveNo: string | null
  etaAt: string | null
  overtimeRisk: OvertimeRisk
  farDelivery: boolean
}

/** GET /tasks/by-orders 响应：key 为订单 id 字符串，无配送任务的订单不出现 */
export interface TasksByOrdersResult {
  items: Record<string, OrderDeliveryBrief>
}

/** 批量查询订单的配送任务（≤100 个订单），用于订单列表合并渲染 */
export function getTasksByOrders(orderIds: number[]) {
  return request.get<TasksByOrdersResult>({
    url: '/api/admin/delivery/tasks/by-orders',
    params: { orderIds: orderIds.join(',') }
  })
}

// ==================== 看板 SSE（04 §2.1 board/stream） ====================

export interface BoardStreamHandlers {
  onTaskChanged?: (task: AdminTaskCard) => void
  onRiderMoved?: (rider: RiderBoardCard) => void
  onExceptionRaised?: (exception: DeliveryExceptionItem) => void
  onSummaryUpdated?: (summary: BoardSummary) => void
  onHeartbeat?: () => void
  onOpen?: () => void
  /** 连接失败或中断时触发；重连与降级轮询由上层 composable 负责 */
  onError?: (error: unknown) => void
}

export interface BoardEventSourceController {
  close: () => void
}

/**
 * 订阅调度看板 SSE（GET /api/admin/delivery/board/stream）。
 *
 * 实现说明：原生 EventSource 无法携带 Authorization 请求头，而本项目令牌走请求头
 * （见 src/utils/http 拦截器），用 query 传 token 会把令牌泄漏进访问日志。
 * 因此这里用 fetch + ReadableStream 手写 SSE 解析（不引入新依赖）。
 * 本工具只负责单次连接的解析与派发，断线自动重连 + 全量刷新由调用方
 * （useDeliveryStream composable）实现。
 */
export function createBoardEventSource(handlers: BoardStreamHandlers): BoardEventSourceController {
  const abortController = new AbortController()
  let closed = false
  let reader: ReadableStreamDefaultReader<Uint8Array> | null = null

  const dispatchEvent = (rawEvent: string) => {
    let eventType = 'message'
    const dataLines: string[] = []
    for (const line of rawEvent.split(/\r\n|\r|\n/)) {
      if (line.startsWith('event:')) {
        eventType = line.slice(6).trim()
      } else if (line.startsWith('data:')) {
        dataLines.push(line.slice(5).trimStart())
      }
    }
    if (eventType === 'heartbeat') {
      handlers.onHeartbeat?.()
      return
    }
    const dataText = dataLines.join('\n')
    if (!dataText) return
    let payload: unknown
    try {
      payload = JSON.parse(dataText)
    } catch {
      return
    }
    switch (eventType) {
      case 'task-changed':
        handlers.onTaskChanged?.(payload as AdminTaskCard)
        break
      case 'rider-moved':
        handlers.onRiderMoved?.(payload as RiderBoardCard)
        break
      case 'exception-raised':
        handlers.onExceptionRaised?.(payload as DeliveryExceptionItem)
        break
      case 'summary-updated':
        handlers.onSummaryUpdated?.(payload as BoardSummary)
        break
    }
  }

  const connect = async () => {
    try {
      const { accessToken } = useUserStore()
      const baseUrl: string = import.meta.env.VITE_API_URL || ''
      const headers: Record<string, string> = { Accept: 'text/event-stream' }
      if (accessToken) {
        headers.Authorization = accessToken.startsWith('Bearer ')
          ? accessToken
          : `Bearer ${accessToken}`
      }
      const response = await fetch(`${baseUrl}/api/admin/delivery/board/stream`, {
        headers,
        signal: abortController.signal
      })
      if (!response.ok || !response.body) {
        throw new Error(`SSE 连接失败：HTTP ${response.status}`)
      }
      handlers.onOpen?.()

      reader = response.body.getReader()
      const decoder = new TextDecoder('utf-8')
      let buffer = ''
      for (;;) {
        const { done, value } = await reader.read()
        if (done) break
        buffer += decoder.decode(value, { stream: true })
        let separator = /\r\n\r\n|\n\n|\r\r/.exec(buffer)
        while (separator?.index !== undefined) {
          dispatchEvent(buffer.slice(0, separator.index))
          buffer = buffer.slice(separator.index + separator[0].length)
          separator = /\r\n\r\n|\n\n|\r\r/.exec(buffer)
        }
      }
      buffer += decoder.decode()
      if (buffer.trim()) dispatchEvent(buffer)
      if (!closed) {
        handlers.onError?.(new Error('SSE 连接已断开'))
      }
    } finally {
      reader?.releaseLock()
      reader = null
    }
  }

  connect().catch((error) => {
    if (!closed) {
      handlers.onError?.(error)
    }
  })

  return {
    close() {
      closed = true
      abortController.abort()
      void reader?.cancel().catch(() => undefined)
    }
  }
}
