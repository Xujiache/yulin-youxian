<template>
  <div class="fresh-page draw-records-page">
    <div class="fresh-page__head records-head">
      <div>
        <ElButton text class="back-button" @click="goConfig">
          <ArtSvgIcon icon="ri:arrow-left-line" />
          返回随机减免配置
        </ElButton>
        <h1 class="fresh-page__title">中奖记录与赠品履约</h1>
        <p class="fresh-page__desc">查询每次抽奖结果，并对待履约的实物赠品完成核销留痕。</p>
      </div>
      <ElButton type="primary" :loading="loading" @click="loadRecords">
        <ArtSvgIcon icon="ri:refresh-line" />
        刷新记录
      </ElButton>
    </div>

    <div class="record-metrics">
      <div class="record-metric">
        <span>当前结果</span>
        <strong>{{ total }}</strong>
        <small>条抽奖记录</small>
      </div>
      <div class="record-metric">
        <span>现金减免</span>
        <strong>{{ money(discountTotal) }}</strong>
        <small>本页合计，已排除作废记录</small>
      </div>
      <div class="record-metric record-metric--attention">
        <span>赠品待履约</span>
        <strong>{{ pendingGoodsCount }}</strong>
        <small>{{ pendingGoodsCount ? '本页待核销，请及时处理' : '本页已处理完毕' }}</small>
      </div>
    </div>

    <ElCard class="fresh-card records-card" shadow="never">
      <div class="record-filter">
        <div class="record-filter__copy">
          <strong>筛选记录</strong>
          <span>关键词由后端匹配订单号、用户或奖项等可检索字段。</span>
        </div>
        <div class="record-filter__fields">
          <ElInput
            v-model.trim="query.keyword"
            clearable
            placeholder="订单号、用户 ID、记录 ID 或奖项"
            @keyup.enter="reload"
          >
            <template #prefix><ArtSvgIcon icon="ri:search-line" /></template>
          </ElInput>
          <ElDatePicker
            v-model="query.range"
            type="datetimerange"
            value-format="YYYY-MM-DDTHH:mm:ss"
            start-placeholder="开始时间"
            end-placeholder="结束时间"
            range-separator="至"
            class="record-filter__range"
          />
          <ElSelect v-model="query.prizeType" clearable placeholder="全部奖项类型">
            <ElOption label="现金减免" value="DISCOUNT" />
            <ElOption label="实物赠品" value="GOODS" />
            <ElOption label="谢谢惠顾" value="NONE" />
          </ElSelect>
          <ElSelect v-model="query.status" clearable placeholder="全部状态">
            <ElOptionGroup label="履约状态">
              <ElOption
                v-for="option in fulfillmentOptions"
                :key="`fulfillment-${option.value}`"
                :label="option.label"
                :value="option.value"
              />
            </ElOptionGroup>
            <ElOptionGroup label="流水关联状态">
              <ElOption
                v-for="option in relationFilterOptions"
                :key="`relation-${option.value}`"
                :label="option.label"
                :value="option.value"
              />
            </ElOptionGroup>
          </ElSelect>
          <ElButton type="primary" :loading="loading" @click="reload">查询</ElButton>
          <ElButton @click="resetFilters">重置</ElButton>
        </div>
      </div>

      <ElAlert
        v-if="fallbackHint"
        :title="fallbackHint"
        type="info"
        :closable="false"
        show-icon
        class="records-hint"
      />

      <ElTable
        v-loading="loading"
        :data="records"
        row-key="id"
        border
        empty-text="没有符合条件的抽奖记录"
        class="records-table"
      >
        <ElTableColumn label="记录 ID" min-width="110" fixed="left">
          <template #default="{ row }">
            <span class="mono-cell">{{ displayId(row.id) }}</span>
          </template>
        </ElTableColumn>

        <ElTableColumn label="订单" min-width="196">
          <template #default="{ row }">
            <div class="stack-cell">
              <strong>{{ row.orderNo || '未返回订单号' }}</strong>
              <span>订单 ID {{ displayId(row.orderId) }}</span>
              <ElTag v-if="row.orderStatus" size="small" effect="plain" type="info">
                {{ row.orderStatus }}
              </ElTag>
            </div>
          </template>
        </ElTableColumn>

        <ElTableColumn label="用户" min-width="112">
          <template #default="{ row }">
            <span class="mono-cell">#{{ displayId(row.userId) }}</span>
          </template>
        </ElTableColumn>

        <ElTableColumn label="奖项" min-width="190">
          <template #default="{ row }">
            <div class="prize-cell">
              <span class="prize-cell__icon" :class="`type-${safePrizeType(row).toLowerCase()}`">
                <ArtSvgIcon :icon="prizeIcon(safePrizeType(row))" />
              </span>
              <div>
                <strong>{{ row.prizeName || prizeTypeLabel(row.prizeType) }}</strong>
                <ElTag size="small" effect="plain" :type="prizeTagType(row.prizeType)">
                  {{ prizeTypeLabel(row.prizeType) }}
                </ElTag>
              </div>
            </div>
          </template>
        </ElTableColumn>

        <ElTableColumn label="减免金额" width="126" align="right">
          <template #default="{ row }">
            <strong v-if="safePrizeType(row) === 'DISCOUNT'" class="money">
              {{ money(row.discountAmount) }}
            </strong>
            <span v-else class="muted">—</span>
          </template>
        </ElTableColumn>

        <ElTableColumn label="状态" width="150" align="center">
          <template #default="{ row }">
            <div class="status-cell">
              <ElTag :type="statusTagType(row.status)" effect="light">
                {{ statusLabel(row.status) }}
              </ElTag>
              <ElTag
                v-if="relationOf(row)"
                :type="relationTagType(row.relationStatus)"
                size="small"
                effect="plain"
              >
                {{ relationLabel(row.relationStatus) }}
              </ElTag>
              <small v-if="row.voidReason">{{ voidReasonText(row.voidReason) }}</small>
            </div>
          </template>
        </ElTableColumn>

        <ElTableColumn label="关键时间" min-width="212">
          <template #default="{ row }">
            <div class="time-cell">
              <span><i>分享</i>{{ dateTime(row.shareTriggeredAt) }}</span>
              <span><i>抽奖</i>{{ dateTime(row.drawnAt || row.createdAt) }}</span>
              <span><i>支付</i>{{ dateTime(row.paidAt) }}</span>
            </div>
          </template>
        </ElTableColumn>

        <ElTableColumn label="履约时间" min-width="172">
          <template #default="{ row }">{{ dateTime(row.fulfilledAt) }}</template>
        </ElTableColumn>

        <ElTableColumn label="履约备注" min-width="200">
          <template #default="{ row }">
            <span :class="{ muted: !row.fulfillmentRemark }">
              {{ row.fulfillmentRemark || '—' }}
            </span>
          </template>
        </ElTableColumn>

        <ElTableColumn label="操作" width="122" align="center" fixed="right">
          <template #default="{ row }">
            <ElButton v-if="canFulfill(row)" type="primary" link @click="openFulfill(row)">
              履约核销
            </ElButton>
            <ElTooltip
              v-else-if="fulfillBlockReason(row)"
              :content="fulfillBlockReason(row)"
              placement="top"
            >
              <span class="muted">暂不可核销</span>
            </ElTooltip>
            <span v-else class="muted">—</span>
          </template>
        </ElTableColumn>
      </ElTable>

      <div class="records-pager">
        <ElPagination
          v-model:current-page="page"
          v-model:page-size="pageSize"
          :total="total"
          :page-sizes="[20, 50, 100]"
          layout="total, sizes, prev, pager, next"
          background
          @current-change="loadRecords"
          @size-change="reload"
        />
      </div>
    </ElCard>

    <ElDialog v-model="fulfillVisible" title="实物赠品履约核销" width="520px" destroy-on-close>
      <div v-if="activeRecord" class="fulfill-dialog">
        <div class="fulfill-dialog__notice">
          <ArtSvgIcon icon="ri:gift-line" />
          <div>
            <strong>{{ activeRecord.prizeName || '实物赠品' }}</strong>
            <span>核销后将记录履约时间与操作备注，请确认赠品已交付。</span>
          </div>
        </div>
        <ElDescriptions :column="1" border size="small">
          <ElDescriptionsItem label="记录 ID">{{ displayId(activeRecord.id) }}</ElDescriptionsItem>
          <ElDescriptionsItem label="订单号">
            {{ activeRecord.orderNo || displayId(activeRecord.orderId) }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="用户 ID">{{
            displayId(activeRecord.userId)
          }}</ElDescriptionsItem>
          <ElDescriptionsItem v-if="activeRecord.orderStatus" label="订单状态">
            {{ activeRecord.orderStatus }}
          </ElDescriptionsItem>
          <ElDescriptionsItem v-if="relationOf(activeRecord)" label="关联状态">
            {{ relationLabel(activeRecord.relationStatus) }}
          </ElDescriptionsItem>
        </ElDescriptions>
        <ElForm label-position="top">
          <ElFormItem label="履约备注" required>
            <ElInput
              v-model.trim="fulfillmentRemark"
              type="textarea"
              :rows="4"
              maxlength="200"
              show-word-limit
              resize="none"
              placeholder="例如：2026-08-12 门店自提，核对手机号后交付"
              @keyup.ctrl.enter="confirmFulfill"
            />
            <span class="dialog-tip">可按 Ctrl + Enter 快速确认。</span>
          </ElFormItem>
        </ElForm>
      </div>

      <template #footer>
        <ElButton @click="fulfillVisible = false">取消</ElButton>
        <ElButton type="primary" :loading="fulfilling" @click="confirmFulfill">
          确认已履约
        </ElButton>
      </template>
    </ElDialog>
  </div>
</template>

<script setup lang="ts">
  import { ElMessage, type TagProps } from 'element-plus'
  import {
    fulfillLotteryDraw,
    getLotteryDraws,
    LOTTERY_FULFILLMENT_OPTIONS,
    LOTTERY_RELATION_OPTIONS,
    type LotteryDrawListResponse,
    type LotteryDrawRecord,
    type LotteryId,
    type LotteryPrizeType
  } from '@/api/marketing'
  import { isHttpError } from '@/utils/http/error'

  defineOptions({ name: 'FreshLuckyDrawRecords' })

  type TagType = TagProps['type']

  /** 后端在订单未支付、流水已作废时返回 409 */
  const CONFLICT_STATUS = 409
  const DEFAULT_RANGE_DAYS = 7

  const router = useRouter()
  const loading = ref(false)
  const fulfilling = ref(false)
  const fulfillVisible = ref(false)
  const activeRecord = ref<LotteryDrawRecord | null>(null)
  const fulfillmentRemark = ref('')
  const records = ref<LotteryDrawRecord[]>([])
  const total = ref(0)
  const page = ref(1)
  const pageSize = ref(20)
  /** 后端返回 PageResult 时由服务端分页，否则本地兜底分页 */
  const serverPaged = ref(true)
  const serverIgnoredRange = ref(false)

  const pad = (value: number) => String(value).padStart(2, '0')
  const toLocalDateTime = (date: Date) =>
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(
      date.getHours()
    )}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
  /** 默认只看最近 7 天（含今天全天），避免活动上线后一次性拉取全部流水 */
  const defaultRange = (): string[] => {
    const end = new Date()
    end.setHours(23, 59, 59, 0)
    const start = new Date(end)
    start.setDate(start.getDate() - (DEFAULT_RANGE_DAYS - 1))
    start.setHours(0, 0, 0, 0)
    return [toLocalDateTime(start), toLocalDateTime(end)]
  }

  const query = reactive<{
    keyword: string
    prizeType: LotteryPrizeType | ''
    status: string
    range: string[] | null
  }>({
    keyword: '',
    prizeType: '',
    status: '',
    range: defaultRange()
  })

  const fulfillmentOptions = LOTTERY_FULFILLMENT_OPTIONS
  /** VOIDED 在两组里语义一致，只保留履约状态组里的那一个，避免下拉出现重复值 */
  const relationFilterOptions = LOTTERY_RELATION_OPTIONS.filter(
    (option) => !LOTTERY_FULFILLMENT_OPTIONS.some((item) => item.value === option.value)
  )

  const rangeStart = computed(() => query.range?.[0] || '')
  const rangeEnd = computed(() => query.range?.[1] || '')
  const fallbackHint = computed(() => {
    if (!serverPaged.value) return '当前服务端尚未支持分页，已在浏览器按时间窗筛选并分页展示。'
    if (serverIgnoredRange.value) return '当前服务端尚未按时间筛选，结果包含所选时间窗以外的记录。'
    return ''
  })

  const sourceRows = (response: LotteryDrawListResponse) => {
    if (Array.isArray(response)) return response
    if (!response || typeof response !== 'object') return []
    const candidates = [response.items, response.records, response.list, response.content]
    return candidates.find((items) => Array.isArray(items)) || []
  }

  const normalizedStatus = (value?: string | null) =>
    String(value || '')
      .trim()
      .toUpperCase()
  const relationOf = (record: LotteryDrawRecord) => normalizedStatus(record.relationStatus)

  /** 与后端每日预算口径一致：RESERVED / APPLIED / SETTLED 的减免都占用预算，只有作废的不算 */
  const countsTowardBudget = (record: LotteryDrawRecord) => {
    const relation = relationOf(record)
    if (relation) return ['RESERVED', 'APPLIED', 'SETTLED'].includes(relation)
    return normalizedStatus(record.status) !== 'VOIDED'
  }

  const discountTotal = computed(() =>
    records.value.reduce(
      (sum, record) =>
        safePrizeType(record) === 'DISCOUNT' && countsTowardBudget(record)
          ? sum + Math.max(0, Number(record.discountAmount || 0))
          : sum,
      0
    )
  )
  const pendingGoodsCount = computed(
    () =>
      records.value.filter(
        (record) => safePrizeType(record) === 'GOODS' && isPendingStatus(record.status)
      ).length
  )

  const safePrizeType = (record: LotteryDrawRecord): LotteryPrizeType =>
    record.prizeType === 'DISCOUNT' || record.prizeType === 'GOODS' || record.prizeType === 'NONE'
      ? record.prizeType
      : 'NONE'
  const displayId = (value?: LotteryId | null) =>
    value === null || value === undefined || value === '' ? '—' : String(value)
  const money = (value?: number | null) => `￥${(Number(value || 0) / 100).toFixed(2)}`
  const dateTime = (value?: string | null) => {
    if (!value) return '—'
    const timestamp = Date.parse(value)
    if (!Number.isFinite(timestamp)) return String(value)
    return new Intl.DateTimeFormat('zh-CN', {
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
      hour12: false
    }).format(new Date(timestamp))
  }

  const prizeTypeLabels: Record<string, string> = {
    DISCOUNT: '现金减免',
    GOODS: '实物赠品',
    NONE: '谢谢惠顾'
  }
  const prizeTypeLabel = (value?: string | null) =>
    prizeTypeLabels[String(value || '').toUpperCase()] || String(value || '未知类型')
  const prizeIcon = (type: LotteryPrizeType) =>
    ({
      DISCOUNT: 'ri:coupon-3-line',
      GOODS: 'ri:gift-line',
      NONE: 'ri:emotion-normal-line'
    })[type]
  const prizeTagType = (value?: string | null): TagType => {
    if (value === 'DISCOUNT') return 'success'
    if (value === 'GOODS') return 'primary'
    return 'info'
  }

  const statusLabels = new Map(
    LOTTERY_FULFILLMENT_OPTIONS.map((option) => [option.value, option.label])
  )
  const relationLabels = new Map(
    LOTTERY_RELATION_OPTIONS.map((option) => [option.value, option.label])
  )
  const statusTagTypes: Record<string, TagType> = {
    PENDING: 'warning',
    FULFILLED: 'success',
    RELEASED: 'info',
    NOT_REQUIRED: 'info',
    VOIDED: 'danger'
  }
  const relationTagTypes: Record<string, TagType> = {
    RESERVED: 'warning',
    APPLIED: 'success',
    SETTLED: 'success',
    VOIDED: 'danger'
  }

  /** 后端的作废原因是英文枚举，店主看不懂，逐个翻成中文；未知取值原样显示 */
  const voidReasonLabels: Record<string, string> = {
    USER_CANCELLED: '用户取消订单',
    ADMIN_CANCELLED: '后台取消订单',
    CONSISTENCY_CANCELLED: '对账发现订单已取消',
    RESERVED_WITHOUT_STOREFRONT_PROJECTION: '奖项未写入订单，已回滚',
    PROJECTION_REPAIR_FAILED: '订单促销修复失败，已作废',
    STOREFRONT_PROJECTION_VOIDED: '订单促销已失效',
    FULL_REFUND_BEFORE_FULFILLMENT: '赠品交付前全额退款',
    RESTART_MARKETING_STOCK_UNAVAILABLE: '重启支付时奖品库存不足',
    RESTART_GIFT_STOCK_UNAVAILABLE: '重启支付时赠品库存不足'
  }
  const voidReasonText = (value?: string | null) =>
    voidReasonLabels[normalizedStatus(value)] || String(value || '')

  const statusLabel = (value?: string | null) =>
    statusLabels.get(normalizedStatus(value)) || String(value || '未知状态')
  const statusTagType = (value?: string | null): TagType =>
    statusTagTypes[normalizedStatus(value)] || 'info'
  const relationLabel = (value?: string | null) =>
    relationLabels.get(normalizedStatus(value)) || String(value || '—')
  const relationTagType = (value?: string | null): TagType =>
    relationTagTypes[normalizedStatus(value)] || 'info'

  const isPendingStatus = (value?: string | null) => normalizedStatus(value) === 'PENDING'
  /** 只有促销已应用（订单已支付）的赠品才允许核销，否则后端流水不会落履约时间 */
  const canFulfill = (record: LotteryDrawRecord) =>
    safePrizeType(record) === 'GOODS' &&
    isPendingStatus(record.status) &&
    record.id != null &&
    !['RESERVED', 'VOIDED'].includes(relationOf(record))
  const fulfillBlockReason = (record: LotteryDrawRecord) => {
    if (safePrizeType(record) !== 'GOODS' || !isPendingStatus(record.status)) return ''
    const relation = relationOf(record)
    if (relation === 'RESERVED') return '顾客尚未支付，促销仍是预占状态，核销不会生效'
    if (relation === 'VOIDED') return '流水已作废（订单取消或全额退款），无需核销'
    return record.id == null ? '记录缺少 ID，无法核销' : ''
  }

  // 服务端的时间窗筛的是流水创建时间，这里也按 createdAt 校验，否则 drawnAt（奖项生效时间）
  // 跨过时间窗边界的记录会被误判成“服务端忽略了时间筛选”。
  const recordTime = (record: LotteryDrawRecord) =>
    Date.parse(String(record.createdAt || record.drawnAt || record.shareTriggeredAt || ''))
  const withinRange = (record: LotteryDrawRecord) => {
    const start = rangeStart.value ? Date.parse(rangeStart.value) : Number.NaN
    const end = rangeEnd.value ? Date.parse(rangeEnd.value) : Number.NaN
    const time = recordTime(record)
    if (!Number.isFinite(time)) return true
    if (Number.isFinite(start) && time < start) return false
    if (Number.isFinite(end) && time > end) return false
    return true
  }
  const isRecord = (record: unknown): record is LotteryDrawRecord =>
    Boolean(record && typeof record === 'object')

  const loadRecords = async () => {
    loading.value = true
    try {
      const response = await getLotteryDraws({
        keyword: query.keyword,
        prizeType: query.prizeType,
        status: query.status,
        startAt: rangeStart.value,
        endAt: rangeEnd.value,
        page: page.value,
        size: pageSize.value
      })
      if (Array.isArray(response)) {
        // 旧版服务端返回全量数组，本地按时间窗筛选并切片，避免一次性渲染上万行
        serverPaged.value = false
        serverIgnoredRange.value = false
        const filtered = response.filter(isRecord).filter(withinRange)
        total.value = filtered.length
        page.value = Math.min(page.value, Math.max(1, Math.ceil(filtered.length / pageSize.value)))
        const offset = (page.value - 1) * pageSize.value
        records.value = filtered.slice(offset, offset + pageSize.value)
        return
      }
      serverPaged.value = true
      const rows = sourceRows(response).filter(isRecord)
      records.value = rows
      total.value =
        response && typeof response.total === 'number'
          ? response.total
          : (page.value - 1) * pageSize.value + rows.length
      serverIgnoredRange.value = rows.some((record) => !withinRange(record))
    } catch (error) {
      records.value = []
      total.value = 0
      ElMessage.error(error instanceof Error ? error.message : '中奖记录加载失败')
    } finally {
      loading.value = false
    }
  }

  const reload = async () => {
    page.value = 1
    await loadRecords()
  }

  const resetFilters = async () => {
    query.keyword = ''
    query.prizeType = ''
    query.status = ''
    query.range = defaultRange()
    await reload()
  }

  const openFulfill = (record: LotteryDrawRecord) => {
    activeRecord.value = record
    fulfillmentRemark.value = ''
    fulfillVisible.value = true
  }

  const confirmFulfill = async () => {
    const record = activeRecord.value
    if (!record?.id) return
    if (!fulfillmentRemark.value.trim()) {
      ElMessage.warning('请填写赠品交付或核销备注')
      return
    }
    fulfilling.value = true
    try {
      await fulfillLotteryDraw(record.id, fulfillmentRemark.value.trim())
      ElMessage.success('实物赠品已完成履约核销')
      fulfillVisible.value = false
      await loadRecords()
    } catch (error) {
      const conflict = isHttpError(error) && error.code === CONFLICT_STATUS
      if (conflict) await loadRecords()
      const message = error instanceof Error ? error.message : '赠品履约核销失败'
      ElMessage.error(conflict ? `${message}；记录状态可能已变化，列表已刷新` : message)
    } finally {
      fulfilling.value = false
    }
  }

  const goConfig = () => {
    router.push({ name: 'FreshLuckyDraw' })
  }

  let activated = false

  onMounted(loadRecords)
  // 路由配置了 keepAlive，返回该页要重新拉取而不是沿用缓存快照；
  // KeepAlive 首次挂载也会触发 onActivated，跳过它以免和 onMounted 重复请求
  onActivated(() => {
    if (!activated) {
      activated = true
      return
    }
    void loadRecords()
  })
</script>

<style scoped lang="scss">
  @use '../../style.scss';

  .records-head {
    align-items: flex-end;
  }

  .back-button {
    padding: 0;
    margin-bottom: 9px;
    color: var(--el-color-primary);
  }

  .record-metrics {
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 12px;
  }

  .record-metric {
    position: relative;
    display: grid;
    gap: 4px;
    padding: 16px 18px;
    overflow: hidden;
    border: 1px solid var(--el-border-color);
    border-radius: 12px;
    background: var(--el-bg-color);

    &::after {
      position: absolute;
      top: 0;
      right: 0;
      width: 48px;
      height: 100%;
      content: '';
      background: linear-gradient(90deg, transparent, var(--el-color-primary-light-9));
    }

    span,
    small {
      color: var(--el-text-color-secondary);
      font-size: 11px;
    }

    strong {
      color: var(--el-color-primary);
      font-size: 23px;
      font-variant-numeric: tabular-nums;
    }

    &--attention strong {
      color: var(--el-color-warning);
    }
  }

  .records-card :deep(.el-card__body) {
    padding: 18px;
  }

  .record-filter {
    display: grid;
    gap: 13px;
    padding: 14px;
    margin-bottom: 16px;
    border: 1px solid var(--el-border-color);
    border-radius: 11px;
    background: var(--el-fill-color-lighter);
  }

  .record-filter__copy {
    display: flex;
    align-items: baseline;
    gap: 9px;

    strong {
      color: var(--el-text-color-primary);
      font-size: 14px;
    }

    span {
      color: var(--el-text-color-secondary);
      font-size: 11px;
    }
  }

  .record-filter__fields {
    display: grid;
    grid-template-columns:
      minmax(220px, 1.4fr) minmax(300px, 1.5fr) minmax(140px, 0.7fr)
      minmax(140px, 0.7fr) auto auto;
    gap: 9px;
  }

  .record-filter__range {
    width: 100%;
  }

  .records-hint {
    margin-bottom: 14px;
  }

  .records-table {
    :deep(.el-table__cell) {
      vertical-align: middle;
    }
  }

  .records-pager {
    display: flex;
    justify-content: flex-end;
    margin-top: 16px;
  }

  .status-cell {
    display: grid;
    gap: 4px;
    justify-items: center;

    small {
      overflow: hidden;
      max-width: 128px;
      color: var(--el-text-color-secondary);
      font-size: 10px;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
  }

  .time-cell {
    display: grid;
    gap: 3px;

    span {
      display: flex;
      gap: 6px;
      align-items: center;
      color: var(--el-text-color-regular);
      font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
      font-size: 11px;
    }

    i {
      flex: none;
      padding: 1px 5px;
      border-radius: 5px;
      color: var(--el-text-color-secondary);
      font-family: inherit;
      font-size: 10px;
      font-style: normal;
      background: var(--el-fill-color-light);
    }
  }

  .mono-cell {
    color: var(--el-text-color-regular);
    font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
    font-size: 12px;
  }

  .stack-cell {
    display: grid;
    gap: 4px;
    justify-items: start;

    strong {
      color: var(--el-text-color-primary);
      font-size: 13px;
    }

    span {
      color: var(--el-text-color-secondary);
      font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
      font-size: 10px;
    }
  }

  .prize-cell {
    display: flex;
    min-width: 0;
    align-items: center;
    gap: 9px;

    &__icon {
      display: grid;
      width: 34px;
      height: 34px;
      flex: none;
      place-items: center;
      border-radius: 10px;
      color: var(--el-color-primary);
      background: var(--el-color-primary-light-9);

      &.type-none {
        color: var(--el-text-color-secondary);
        background: var(--el-fill-color);
      }
    }

    > div {
      display: grid;
      min-width: 0;
      justify-items: start;
      gap: 4px;

      strong {
        overflow: hidden;
        max-width: 128px;
        color: var(--el-text-color-primary);
        font-size: 12px;
        text-overflow: ellipsis;
        white-space: nowrap;
      }
    }
  }

  .fulfill-dialog {
    display: grid;
    gap: 16px;
  }

  .fulfill-dialog__notice {
    display: flex;
    align-items: center;
    gap: 11px;
    padding: 13px;
    border: 1px solid var(--el-color-primary-light-7);
    border-radius: 11px;
    background: var(--el-color-primary-light-9);

    > svg {
      flex: none;
      color: var(--el-color-primary);
      font-size: 25px;
    }

    > div {
      display: grid;
      gap: 3px;

      strong {
        color: var(--el-text-color-primary);
      }

      span {
        color: var(--el-text-color-secondary);
        font-size: 12px;
      }
    }
  }

  .dialog-tip {
    margin-top: 5px;
    color: var(--el-text-color-secondary);
    font-size: 11px;
  }

  @media (max-width: 900px) {
    .record-filter__fields {
      grid-template-columns: repeat(2, minmax(0, 1fr));

      :deep(.el-input:first-child) {
        grid-column: 1 / -1;
      }
    }
  }

  @media (max-width: 680px) {
    .records-head {
      align-items: flex-start;
      flex-direction: column;
    }

    .record-metrics,
    .record-filter__fields {
      grid-template-columns: 1fr;
    }

    .record-filter__copy {
      align-items: flex-start;
      flex-direction: column;
    }

    .record-filter__fields :deep(.el-input:first-child) {
      grid-column: auto;
    }
  }
</style>
