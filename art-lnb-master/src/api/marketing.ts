import request from '@/utils/http'
import type { Product } from '@/api/admin'

export type LotteryId = number | string
export type LotteryPrizeType = 'DISCOUNT' | 'GOODS' | 'NONE'

/**
 * 营销接口中的金额统一使用“分”，页面只在输入和展示时换算为元。
 * 响应字段允许缺省或为 null，便于兼容活动尚未初始化以及后端扩展。
 */
export interface LotteryPrize {
  [key: string]: unknown
  id?: LotteryId | null
  type?: LotteryPrizeType | string | null
  name?: string | null
  discountAmount?: number | null
  productId?: number | null
  skuId?: number | null
  imageUrl?: string | null
  weight?: number | null
  stockTotal?: number | null
  stockRemaining?: number | null
  enabled?: boolean | null
  sortOrder?: number | null
}

export interface LotteryTier {
  [key: string]: unknown
  id?: LotteryId | null
  name?: string | null
  minProductAmount?: number | null
  maxProductAmount?: number | null
  enabled?: boolean | null
  sortOrder?: number | null
  prizes?: Array<LotteryPrize | null> | null
}

export interface LotteryCampaign {
  [key: string]: unknown
  id?: LotteryId | null
  enabled?: boolean | null
  name?: string | null
  startAt?: string | null
  endAt?: string | null
  dailyUserLimit?: number | null
  /** null 表示不限每日现金预算，后端以此判定是否按预算裁剪现金奖项 */
  dailyBudgetAmount?: number | null
  shareTitle?: string | null
  shareDescription?: string | null
  shareImageUrl?: string | null
  tiers?: Array<LotteryTier | null> | null
}

let localIdSequence = 0

/**
 * 生成随对象一起复制的本地标识。表格行 key 依赖它而不是对象引用，
 * 否则编辑时生成的新对象会拿到新 key，正在输入的输入框会被卸载重建。
 */
export function createLocalId(prefix: string) {
  localIdSequence += 1
  return `${prefix}-local-${localIdSequence}`
}

export interface EditableLotteryPrize {
  /** 仅前端使用，不提交后端 */
  localId: string
  id: LotteryId | null
  type: LotteryPrizeType
  name: string
  discountAmount: number
  productId: number | null
  skuId: number | null
  imageUrl: string
  weight: number
  stockTotal: number | null
  stockRemaining: number | null
  enabled: boolean
  sortOrder: number
}

export interface EditableLotteryTier {
  /** 仅前端使用，不提交后端 */
  localId: string
  id: LotteryId | null
  name: string
  minProductAmount: number
  maxProductAmount: number | null
  enabled: boolean
  sortOrder: number
  prizes: EditableLotteryPrize[]
}

export interface EditableLotteryCampaign {
  id: LotteryId | null
  enabled: boolean
  name: string
  startAt: string
  endAt: string
  dailyUserLimit: number
  /** null 表示不限预算，保存时如实回传 null */
  dailyBudgetAmount: number | null
  shareTitle: string
  shareDescription: string
  shareImageUrl: string
  tiers: EditableLotteryTier[]
}

/** 提交给后端的结构：去掉本地标识，并保持与响应类型结构兼容 */
export interface LotteryPrizePayload extends Omit<EditableLotteryPrize, 'localId'> {
  [key: string]: unknown
}

export interface LotteryTierPayload extends Omit<EditableLotteryTier, 'localId' | 'prizes'> {
  [key: string]: unknown
  prizes: LotteryPrizePayload[]
}

export interface LotteryCampaignPayload extends Omit<EditableLotteryCampaign, 'tiers'> {
  [key: string]: unknown
  tiers: LotteryTierPayload[]
}

export interface LotteryStatusOption {
  value: string
  label: string
}

/**
 * 后端 AdminDraw.status（履约状态）的全部取值。
 * 这两组值合起来就是 /draws 的 status 参数接受的全部取值，服务端对每一个都下推到 SQL。
 */
export const LOTTERY_FULFILLMENT_OPTIONS: LotteryStatusOption[] = [
  { value: 'PENDING', label: '待履约' },
  { value: 'FULFILLED', label: '已履约' },
  { value: 'RELEASED', label: '已释放' },
  { value: 'NOT_REQUIRED', label: '无需履约' },
  { value: 'VOIDED', label: '已作废' }
]

/** 后端 AdminDraw.relationStatus（流水与订单的关联状态）的全部取值 */
export const LOTTERY_RELATION_OPTIONS: LotteryStatusOption[] = [
  { value: 'RESERVED', label: '待支付预占' },
  { value: 'APPLIED', label: '已应用' },
  { value: 'SETTLED', label: '已结算' },
  { value: 'VOIDED', label: '已作废' }
]

export interface LotteryDrawRecord {
  [key: string]: unknown
  id?: LotteryId | null
  orderId?: LotteryId | null
  orderNo?: string | null
  userId?: LotteryId | null
  prizeType?: LotteryPrizeType | string | null
  prizeName?: string | null
  discountAmount?: number | null
  /** 履约状态：PENDING / FULFILLED / RELEASED / NOT_REQUIRED / VOIDED */
  status?: string | null
  /** 关联状态：RESERVED / APPLIED / SETTLED / VOIDED，只有 APPLIED 才允许人工核销 */
  relationStatus?: string | null
  giftStockStatus?: string | null
  voidReason?: string | null
  /** 以下审计字段由后端补充，旧版本服务端可能缺省 */
  shareTriggeredAt?: string | null
  drawnAt?: string | null
  createdAt?: string | null
  paidAt?: string | null
  orderStatus?: string | null
  fulfilledAt?: string | null
  fulfillmentRemark?: string | null
}

export interface LotteryDrawListEnvelope {
  [key: string]: unknown
  items?: LotteryDrawRecord[] | null
  records?: LotteryDrawRecord[] | null
  list?: LotteryDrawRecord[] | null
  content?: LotteryDrawRecord[] | null
  total?: number | null
  page?: number | null
  pageSize?: number | null
  size?: number | null
}

export type LotteryDrawListResponse =
  | LotteryDrawRecord[]
  | LotteryDrawListEnvelope
  | null
  | undefined

export interface LotteryDrawQuery {
  keyword?: string
  prizeType?: LotteryPrizeType | ''
  status?: string
  /** 页码从 1 开始 */
  page?: number
  size?: number
  /** yyyy-MM-ddTHH:mm:ss */
  startAt?: string
  endAt?: string
}

export function getLotteryCampaign() {
  return request.get<LotteryCampaign | null>({
    url: '/api/admin/marketing/lottery'
  })
}

export function updateLotteryCampaign(data: LotteryCampaignPayload) {
  return request.put<LotteryCampaign | null>({
    url: '/api/admin/marketing/lottery',
    data
  })
}

export function getLotteryDraws(query: LotteryDrawQuery = {}) {
  const params = {
    ...(query.keyword?.trim() ? { keyword: query.keyword.trim() } : {}),
    ...(query.prizeType ? { prizeType: query.prizeType } : {}),
    ...(query.status ? { status: query.status } : {}),
    ...(query.startAt ? { startAt: query.startAt } : {}),
    ...(query.endAt ? { endAt: query.endAt } : {}),
    ...(query.page && query.page > 0 ? { page: query.page } : {}),
    // size 为抽奖流水接口的分页参数，pageSize 兼容项目内其它列表接口的命名
    ...(query.size && query.size > 0 ? { size: query.size, pageSize: query.size } : {})
  }
  return request.get<LotteryDrawListResponse>({
    url: '/api/admin/marketing/lottery/draws',
    params
  })
}

export function fulfillLotteryDraw(id: LotteryId, remark: string) {
  return request.post<LotteryDrawRecord | null>({
    url: `/api/admin/marketing/lottery/draws/${encodeURIComponent(String(id))}/fulfill`,
    data: { remark },
    // 履约失败由页面按状态码给出可操作提示，避免和全局提示重复
    showErrorMessage: false
  })
}

/**
 * 概率预览用的赠品可售性探针：请求失败不弹全局提示，由预览面板内联标注。
 */
export function getLotteryGiftProduct(productId: number) {
  return request.get<Product | null>({
    url: `/api/admin/products/${productId}`,
    showErrorMessage: false
  })
}
