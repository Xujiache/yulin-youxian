/**
 * 配送域管理端页面共用的格式化与字典
 *
 * 后端下发的时间是「Asia/Shanghai 本地时间、无时区后缀」的 ISO-8601 字符串，
 * 这里统一按本地时间解析；金额一律是分，展示时除以 100。
 */
import type {
  DeliveryTaskCard,
  DeliveryTaskStatus,
  FatigueLevel,
  OvertimeRisk,
  RiderBoardCard
} from '@/api/delivery'

export type TagType = 'primary' | 'success' | 'info' | 'warning' | 'danger'

const pick = <T>(dict: Record<string, T>, key: string | null | undefined, fallback: T): T => {
  if (!key) return fallback
  const hit = dict[key]
  return hit === undefined ? fallback : hit
}

/** 分 → ￥0.00 */
export const money = (fen?: number | null) => `￥${(Number(fen || 0) / 100).toFixed(2)}`

/** 0.9532 → 95.3% */
export const percent = (rate?: number | null, digits = 1) => {
  const value = Number(rate || 0)
  return `${(value * 100).toFixed(digits)}%`
}

/** 健康证是日期字段，按客户端本地“今天”计算剩余自然日，避免把日期当 UTC 时间解析。 */
export const healthCertRemainingDays = (expireAt?: string | null, today = new Date()) => {
  const match = String(expireAt || '').match(/^(\d{4})-(\d{2})-(\d{2})$/)
  if (!match) return null
  const expireUtc = Date.UTC(Number(match[1]), Number(match[2]) - 1, Number(match[3]))
  const todayUtc = Date.UTC(today.getFullYear(), today.getMonth(), today.getDate())
  return Math.round((expireUtc - todayUtc) / 86_400_000)
}

export const isHealthCertExpiringSoon = (expireAt?: string | null, warningDays = 30) => {
  const days = healthCertRemainingDays(expireAt)
  return days !== null && days <= warningDays
}

export const distanceText = (meters?: number | null) => {
  const value = Number(meters || 0)
  return value >= 1000 ? `${(value / 1000).toFixed(2)} km` : `${Math.round(value)} m`
}

/** 解析后端时间字符串，失败返回 NaN */
export const parseTime = (value?: string | null): number => {
  if (!value) return Number.NaN
  const normalized = value.trim().replace(' ', 'T')
  return new Date(normalized).getTime()
}

const pad2 = (value: number) => String(value).padStart(2, '0')

/** 15:40 */
export const clockText = (value?: string | null) => {
  const time = parseTime(value)
  if (Number.isNaN(time)) return '--:--'
  const date = new Date(time)
  return `${pad2(date.getHours())}:${pad2(date.getMinutes())}`
}

/** 08-11 15:40 */
export const dateTimeText = (value?: string | null) => {
  const time = parseTime(value)
  if (Number.isNaN(time)) return '—'
  const date = new Date(time)
  return `${pad2(date.getMonth() + 1)}-${pad2(date.getDate())} ${pad2(date.getHours())}:${pad2(date.getMinutes())}`
}

/** 2026-08-11 15:40:22 */
export const fullTimeText = (value?: string | null) => {
  const time = parseTime(value)
  if (Number.isNaN(time)) return '—'
  const date = new Date(time)
  return `${date.getFullYear()}-${pad2(date.getMonth() + 1)}-${pad2(date.getDate())} ${pad2(date.getHours())}:${pad2(date.getMinutes())}:${pad2(date.getSeconds())}`
}

/** 秒 → 02:15 / 1:02:15，负数返回 -02:15 */
export const durationText = (seconds?: number | null) => {
  const total = Math.round(Number(seconds || 0))
  const sign = total < 0 ? '-' : ''
  const abs = Math.abs(total)
  const hours = Math.floor(abs / 3600)
  const minutes = Math.floor((abs % 3600) / 60)
  const rest = abs % 60
  return hours > 0
    ? `${sign}${hours}:${pad2(minutes)}:${pad2(rest)}`
    : `${sign}${pad2(minutes)}:${pad2(rest)}`
}

/** 秒 → 4小时10分 */
export const humanDuration = (seconds?: number | null) => {
  const total = Math.max(0, Math.round(Number(seconds || 0)))
  const hours = Math.floor(total / 3600)
  const minutes = Math.floor((total % 3600) / 60)
  if (hours > 0) return `${hours}小时${minutes}分`
  if (minutes > 0) return `${minutes}分钟`
  return `${total}秒`
}

/** 距离目标时刻还剩多少秒，无目标返回 null */
export const secondsUntil = (value: string | null | undefined, now: number): number | null => {
  const time = parseTime(value)
  if (Number.isNaN(time)) return null
  return Math.round((time - now) / 1000)
}

// ==================== 字典 ====================

const TASK_STATUS_TEXT: Record<string, string> = {
  PENDING: '待派单',
  ASSIGNED: '已指派',
  ACCEPTED: '已接单',
  PICKED_UP: '已取货',
  DELIVERING: '配送中',
  ARRIVED: '已到达',
  DELIVERED: '已送达',
  RETURNED: '已退回',
  CANCELLED: '已取消'
}

const TASK_STATUS_TAG: Record<string, TagType> = {
  PENDING: 'warning',
  ASSIGNED: 'primary',
  ACCEPTED: 'primary',
  PICKED_UP: 'primary',
  DELIVERING: 'primary',
  ARRIVED: 'success',
  DELIVERED: 'success',
  RETURNED: 'danger',
  CANCELLED: 'info'
}

export const TASK_STATUS_OPTIONS: Array<{ label: string; value: string }> = [
  { label: '全部', value: '' },
  ...Object.entries(TASK_STATUS_TEXT).map(([value, label]) => ({ label, value }))
]

export const taskStatusText = (status?: string | null) => pick(TASK_STATUS_TEXT, status, '未知')
export const taskStatusTag = (status?: string | null) => pick(TASK_STATUS_TAG, status, 'info')

/** 在途（已派出但未终结） */
export const isOnRoadStatus = (status: DeliveryTaskStatus) =>
  ['ASSIGNED', 'ACCEPTED', 'PICKED_UP', 'DELIVERING', 'ARRIVED'].includes(status)

const RISK_TEXT: Record<string, string> = {
  LOW: '正常',
  MEDIUM: '需关注',
  HIGH: '高风险',
  OVERTIME: '已超时'
}

const RISK_TAG: Record<string, TagType> = {
  LOW: 'success',
  MEDIUM: 'warning',
  HIGH: 'danger',
  OVERTIME: 'danger'
}

export const riskText = (risk?: OvertimeRisk | null) => pick(RISK_TEXT, risk, '正常')
export const riskTag = (risk?: OvertimeRisk | null) => pick(RISK_TAG, risk, 'info')

const FATIGUE_TEXT: Record<string, string> = {
  NONE: '状态正常',
  WARN_4H: '连续在岗 4 小时',
  CONFIRM_8H: '连续在岗 8 小时',
  FORCE_12H: '满 12 小时强制休息'
}

export const fatigueText = (level?: FatigueLevel | null) => pick(FATIGUE_TEXT, level, '状态正常')

const EXCEPTION_TYPE_TEXT: Record<string, string> = {
  CUSTOMER_UNREACHABLE: '联系不上顾客',
  WRONG_ADDRESS: '地址有误',
  CUSTOMER_REFUSED: '顾客拒收',
  GOODS_DAMAGED: '商品破损',
  GOODS_LEAKING: '商品洒漏',
  WEIGHT_DISPUTE: '重量争议',
  ITEM_MISSING: '缺件少件',
  ACCESS_DENIED: '无法进入小区',
  VEHICLE_FAILURE: '车辆故障',
  RIDER_UNWELL: '骑手身体不适',
  BAD_WEATHER: '恶劣天气',
  STORE_SLOW: '门店出货慢',
  OTHER: '其他'
}

export const EXCEPTION_TYPE_OPTIONS = Object.entries(EXCEPTION_TYPE_TEXT).map(([value, label]) => ({
  label,
  value
}))

export const exceptionTypeText = (type?: string | null) =>
  pick(EXCEPTION_TYPE_TEXT, type, type || '其他')

const SEVERITY_TEXT: Record<string, string> = {
  LOW: '低',
  NORMAL: '一般',
  HIGH: '高',
  URGENT: '紧急'
}

const SEVERITY_TAG: Record<string, TagType> = {
  LOW: 'info',
  NORMAL: 'primary',
  HIGH: 'warning',
  URGENT: 'danger'
}

const SEVERITY_WEIGHT: Record<string, number> = {
  URGENT: 4,
  HIGH: 3,
  NORMAL: 2,
  LOW: 1
}

export const SEVERITY_OPTIONS = Object.entries(SEVERITY_TEXT).map(([value, label]) => ({
  label,
  value
}))

export const severityText = (value?: string | null) => pick(SEVERITY_TEXT, value, '一般')
export const severityTag = (value?: string | null) => pick(SEVERITY_TAG, value, 'info')
export const severityWeight = (value?: string | null) => pick(SEVERITY_WEIGHT, value, 0)

const EXCEPTION_STATUS_TEXT: Record<string, string> = {
  OPEN: '待处理',
  PROCESSING: '处理中',
  RESOLVED: '已解决',
  CLOSED: '已关闭'
}

const EXCEPTION_STATUS_TAG: Record<string, TagType> = {
  OPEN: 'danger',
  PROCESSING: 'warning',
  RESOLVED: 'success',
  CLOSED: 'info'
}

export const EXCEPTION_STATUS_OPTIONS = Object.entries(EXCEPTION_STATUS_TEXT).map(
  ([value, label]) => ({ label, value })
)

export const exceptionStatusText = (value?: string | null) =>
  pick(EXCEPTION_STATUS_TEXT, value, '待处理')
export const exceptionStatusTag = (value?: string | null) =>
  pick(EXCEPTION_STATUS_TAG, value, 'info')

/** 异常处理方式，与 delivery_exception.resolution_type 一致 */
export const RESOLUTION_OPTIONS = [
  { value: 'CONTINUE', label: '继续配送', hint: '顾客已联系上，骑手继续本次配送' },
  { value: 'RETURN', label: '退回门店', hint: '商品带回门店，后续再约配送' },
  { value: 'REASSIGN', label: '改派骑手', hint: '当前骑手无法完成，转交其他骑手' },
  { value: 'REFUND', label: '发起退款', hint: '进入售后退款流程' },
  { value: 'CANCEL', label: '取消配送', hint: '订单取消，任务终止' },
  { value: 'IGNORE', label: '忽略', hint: '误报或无需处理，直接关闭' }
]

const EVIDENCE_TYPE_TEXT: Record<string, string> = {
  PICKUP: '取货凭证',
  DELIVERED: '送达凭证',
  EXCEPTION: '异常照片',
  WEIGHT_SCALE: '电子秤照片',
  RETURN: '退回凭证',
  SIGNATURE: '签名'
}

export const evidenceTypeText = (value?: string | null) =>
  pick(EVIDENCE_TYPE_TEXT, value, value || '凭证')

const WAVE_STATUS_TEXT: Record<string, string> = {
  PLANNING: '规划中',
  ASSIGNED: '已指派',
  PICKING: '取货中',
  DELIVERING: '配送中',
  // 单送完了但骑手还没回店。这一步没走完不能给他发下一个时段
  RETURNING: '待回店',
  COMPLETED: '已完成',
  CANCELLED: '已取消'
}

const WAVE_STATUS_TAG: Record<string, TagType> = {
  PLANNING: 'info',
  ASSIGNED: 'primary',
  PICKING: 'warning',
  DELIVERING: 'primary',
  RETURNING: 'warning',
  COMPLETED: 'success',
  CANCELLED: 'info'
}

export const WAVE_STATUS_OPTIONS = Object.entries(WAVE_STATUS_TEXT).map(([value, label]) => ({
  label,
  value
}))

export const waveStatusText = (value?: string | null) => pick(WAVE_STATUS_TEXT, value, '未知')
export const waveStatusTag = (value?: string | null) => pick(WAVE_STATUS_TAG, value, 'info')

const SETTLEMENT_STATUS_TEXT: Record<string, string> = {
  DRAFT: '待确认',
  CONFIRMED: '已确认',
  PAID: '已支付',
  VOID: '已作废'
}

const SETTLEMENT_STATUS_TAG: Record<string, TagType> = {
  DRAFT: 'warning',
  CONFIRMED: 'primary',
  PAID: 'success',
  VOID: 'info'
}

export const SETTLEMENT_STATUS_OPTIONS = Object.entries(SETTLEMENT_STATUS_TEXT).map(
  ([value, label]) => ({ label, value })
)

export const settlementStatusText = (value?: string | null) =>
  pick(SETTLEMENT_STATUS_TEXT, value, '待确认')
export const settlementStatusTag = (value?: string | null) =>
  pick(SETTLEMENT_STATUS_TAG, value, 'info')

export const PERIOD_TYPE_OPTIONS = [
  { label: '日结', value: 'DAILY' },
  { label: '周结', value: 'WEEKLY' },
  { label: '月结', value: 'MONTHLY' }
]

const PERIOD_TYPE_TEXT: Record<string, string> = {
  DAILY: '日结',
  WEEKLY: '周结',
  MONTHLY: '月结'
}

export const periodTypeText = (value?: string | null) => pick(PERIOD_TYPE_TEXT, value, value || '—')

const WORK_STATUS_TEXT: Record<string, string> = {
  OFF_DUTY: '已下班',
  ON_DUTY: '在岗',
  RESTING: '休息中',
  BUSY: '忙碌'
}

const WORK_STATUS_TAG: Record<string, TagType> = {
  OFF_DUTY: 'info',
  ON_DUTY: 'success',
  RESTING: 'warning',
  BUSY: 'primary'
}

export const workStatusText = (value?: string | null) => pick(WORK_STATUS_TEXT, value, '未知')
export const workStatusTag = (value?: string | null) => pick(WORK_STATUS_TAG, value, 'info')

const ACCOUNT_STATUS_TEXT: Record<string, string> = {
  ACTIVE: '正常',
  SUSPENDED: '已停用',
  RESIGNED: '已离职'
}

const ACCOUNT_STATUS_TAG: Record<string, TagType> = {
  ACTIVE: 'success',
  SUSPENDED: 'danger',
  RESIGNED: 'info'
}

export const ACCOUNT_STATUS_OPTIONS = Object.entries(ACCOUNT_STATUS_TEXT).map(([value, label]) => ({
  label,
  value
}))

export const accountStatusText = (value?: string | null) => pick(ACCOUNT_STATUS_TEXT, value, '正常')
export const accountStatusTag = (value?: string | null) => pick(ACCOUNT_STATUS_TAG, value, 'info')

export const VEHICLE_TYPE_OPTIONS = [
  { label: '电动车', value: 'EBIKE' },
  { label: '自行车', value: 'BICYCLE' },
  { label: '步行', value: 'WALK' },
  { label: '汽车', value: 'CAR' }
]

const VEHICLE_TYPE_TEXT: Record<string, string> = {
  EBIKE: '电动车',
  BICYCLE: '自行车',
  WALK: '步行',
  CAR: '汽车'
}

export const vehicleTypeText = (value?: string | null) =>
  pick(VEHICLE_TYPE_TEXT, value, value || '—')

/** 轨迹点的运动状态，由骑手端采样时判定后随定位一起上报 */
const MOTION_STATE_TEXT: Record<string, string> = {
  STILL: '静止',
  WALKING: '步行',
  RIDING: '骑行'
}

export const motionStateText = (value?: string | null) =>
  pick(MOTION_STATE_TEXT, value, value || '—')

const EVENT_TYPE_TEXT: Record<string, string> = {
  STATUS_CHANGE: '状态流转',
  ASSIGN: '派单',
  REASSIGN: '改派',
  EXCEPTION: '异常',
  ETA_UPDATE: 'ETA 更新',
  NOTE: '备注'
}

export const eventTypeText = (value?: string | null) =>
  pick(EVENT_TYPE_TEXT, value, value || '事件')

const OPERATOR_TYPE_TEXT: Record<string, string> = {
  RIDER: '骑手',
  ADMIN: '管理员',
  SYSTEM: '系统',
  CUSTOMER: '顾客'
}

export const operatorTypeText = (value?: string | null) =>
  pick(OPERATOR_TYPE_TEXT, value, value || '系统')

const BLOCKER_TEXT: Record<string, string> = {
  FATIGUE_PAUSED: '疲劳停派',
  OVER_CAPACITY: '超出并发上限',
  OVER_WEIGHT: '超出载重',
  COLD_CHAIN_UNSUPPORTED: '无冷链装备',
  OFF_DUTY: '骑手不在岗',
  LOCATION_STALE: '定位陈旧',
  PROBATION: '试用期限制'
}

export const blockerText = (value?: string | null) => pick(BLOCKER_TEXT, value, value || '不可派单')

// ==================== 业务判定 ====================

/** 冷链任务（非常温） */
export const isColdChain = (task: Pick<DeliveryTaskCard, 'coldChainLevel'>) =>
  Boolean(task.coldChainLevel) && task.coldChainLevel !== 'NORMAL'

/**
 * 远单标签：后端把「远单」放在 highlightNotes 里下发（04 §1.3），
 * 兜底再用与骑手的距离（> 3 km）判断。
 */
export const isFarDelivery = (
  task: Pick<DeliveryTaskCard, 'highlightNotes' | 'distanceFromRiderMeters'>
) =>
  (task.highlightNotes || []).some((note) => note.includes('远单')) ||
  Number(task.distanceFromRiderMeters || 0) > 3000

/** 疲劳停派中（合规硬门禁，不提供绕过入口） */
export const isFatiguePaused = (rider: RiderBoardCard, now: number) => {
  if (rider.fatigueLevel === 'FORCE_12H') return true
  const until = parseTime(rider.dispatchPausedUntil)
  return !Number.isNaN(until) && until > now
}

/** 返回不可派单的原因；可派单返回 null */
export const riderBlockReason = (rider: RiderBoardCard, now: number): string | null => {
  if (isFatiguePaused(rider, now)) {
    const until = rider.dispatchPausedUntil
      ? `，${clockText(rider.dispatchPausedUntil)} 前不派单`
      : ''
    return `${fatigueText(rider.fatigueLevel)}${until}`
  }
  if (rider.workStatus === 'OFF_DUTY') return '骑手已下班'
  if (rider.workStatus === 'RESTING') return '骑手休息中'
  if (Number(rider.loadRatio || 0) >= 1) return '已达并发上限'
  return null
}

export const loadPercent = (rider: RiderBoardCard) =>
  Math.min(100, Math.round(Number(rider.loadRatio || 0) * 100))

export const weightPercent = (rider: RiderBoardCard) => {
  const capacity = Number(rider.capacityWeightKg || 0)
  if (capacity <= 0) return 0
  return Math.min(100, Math.round((Number(rider.currentWeightKg || 0) / capacity) * 100))
}
