import request from '@/utils/http'

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
  dailyBudgetAmount?: number | null
  shareTitle?: string | null
  shareDescription?: string | null
  shareImageUrl?: string | null
  tiers?: Array<LotteryTier | null> | null
}

export interface EditableLotteryPrize {
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
  dailyBudgetAmount: number
  shareTitle: string
  shareDescription: string
  shareImageUrl: string
  tiers: EditableLotteryTier[]
}

export interface LotteryDrawRecord {
  [key: string]: unknown
  id?: LotteryId | null
  orderId?: LotteryId | null
  orderNo?: string | null
  userId?: LotteryId | null
  prizeType?: LotteryPrizeType | string | null
  prizeName?: string | null
  discountAmount?: number | null
  status?: string | null
  drawnAt?: string | null
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
}

export function getLotteryCampaign() {
  return request.get<LotteryCampaign | null>({
    url: '/api/admin/marketing/lottery'
  })
}

export function updateLotteryCampaign(data: EditableLotteryCampaign) {
  return request.put<LotteryCampaign | null>({
    url: '/api/admin/marketing/lottery',
    data
  })
}

export function getLotteryDraws(query: LotteryDrawQuery = {}) {
  const params = {
    ...(query.keyword?.trim() ? { keyword: query.keyword.trim() } : {}),
    ...(query.prizeType ? { prizeType: query.prizeType } : {}),
    ...(query.status ? { status: query.status } : {})
  }
  return request.get<LotteryDrawListResponse>({
    url: '/api/admin/marketing/lottery/draws',
    params
  })
}

export function fulfillLotteryDraw(id: LotteryId, remark: string) {
  return request.post<LotteryDrawRecord | null>({
    url: `/api/admin/marketing/lottery/draws/${encodeURIComponent(String(id))}/fulfill`,
    data: { remark }
  })
}
