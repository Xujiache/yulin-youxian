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
        <strong>{{ backendTotal }}</strong>
        <small>条抽奖记录</small>
      </div>
      <div class="record-metric">
        <span>现金减免</span>
        <strong>{{ money(discountTotal) }}</strong>
        <small>当前筛选结果合计</small>
      </div>
      <div class="record-metric record-metric--attention">
        <span>赠品待履约</span>
        <strong>{{ pendingGoodsCount }}</strong>
        <small>{{ pendingGoodsCount ? '请及时核销' : '当前已处理完毕' }}</small>
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
            @keyup.enter="loadRecords"
          >
            <template #prefix><ArtSvgIcon icon="ri:search-line" /></template>
          </ElInput>
          <ElSelect v-model="query.prizeType" clearable placeholder="全部奖项类型">
            <ElOption label="现金减免" value="DISCOUNT" />
            <ElOption label="实物赠品" value="GOODS" />
            <ElOption label="谢谢惠顾" value="NONE" />
          </ElSelect>
          <ElSelect v-model="query.status" clearable placeholder="全部履约状态">
            <ElOption label="待履约" value="PENDING" />
            <ElOption label="已履约" value="FULFILLED" />
            <ElOption label="无需履约" value="NOT_REQUIRED" />
            <ElOption label="履约异常" value="FAILED" />
          </ElSelect>
          <ElButton type="primary" :loading="loading" @click="loadRecords">查询</ElButton>
          <ElButton @click="resetFilters">重置</ElButton>
        </div>
      </div>

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

        <ElTableColumn label="订单" min-width="180">
          <template #default="{ row }">
            <div class="stack-cell">
              <strong>{{ row.orderNo || '未返回订单号' }}</strong>
              <span>订单 ID {{ displayId(row.orderId) }}</span>
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

        <ElTableColumn label="状态" width="126" align="center">
          <template #default="{ row }">
            <ElTag :type="statusTagType(row.status)" effect="light">
              {{ statusLabel(row.status) }}
            </ElTag>
          </template>
        </ElTableColumn>

        <ElTableColumn label="抽奖时间" min-width="172">
          <template #default="{ row }">{{ dateTime(row.drawnAt) }}</template>
        </ElTableColumn>

        <ElTableColumn label="履约时间" min-width="172">
          <template #default="{ row }">{{ dateTime(row.fulfilledAt) }}</template>
        </ElTableColumn>

        <ElTableColumn label="履约备注" min-width="220">
          <template #default="{ row }">
            <span :class="{ muted: !row.fulfillmentRemark }">
              {{ row.fulfillmentRemark || '—' }}
            </span>
          </template>
        </ElTableColumn>

        <ElTableColumn label="操作" width="116" align="center" fixed="right">
          <template #default="{ row }">
            <ElButton v-if="canFulfill(row)" type="primary" link @click="openFulfill(row)">
              履约核销
            </ElButton>
            <span v-else class="muted">—</span>
          </template>
        </ElTableColumn>
      </ElTable>
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
    type LotteryDrawListResponse,
    type LotteryDrawRecord,
    type LotteryId,
    type LotteryPrizeType
  } from '@/api/marketing'

  defineOptions({ name: 'FreshLuckyDrawRecords' })

  type TagType = TagProps['type']

  const router = useRouter()
  const loading = ref(false)
  const fulfilling = ref(false)
  const fulfillVisible = ref(false)
  const activeRecord = ref<LotteryDrawRecord | null>(null)
  const fulfillmentRemark = ref('')
  const records = ref<LotteryDrawRecord[]>([])
  const backendTotal = ref(0)
  const query = reactive<{
    keyword: string
    prizeType: LotteryPrizeType | ''
    status: string
  }>({
    keyword: '',
    prizeType: '',
    status: ''
  })

  const sourceRows = (response: LotteryDrawListResponse) => {
    if (Array.isArray(response)) return response
    if (!response || typeof response !== 'object') return []
    const candidates = [response.items, response.records, response.list, response.content]
    return candidates.find((items) => Array.isArray(items)) || []
  }

  const discountTotal = computed(() =>
    records.value.reduce(
      (sum, record) =>
        safePrizeType(record) === 'DISCOUNT'
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

  const statusLabel = (value?: string | null) => {
    const normalized = String(value || '').toUpperCase()
    const labels: Record<string, string> = {
      PENDING: '待履约',
      UNFULFILLED: '待履约',
      PENDING_FULFILLMENT: '待履约',
      WAITING_FULFILLMENT: '待履约',
      FULFILLED: '已履约',
      COMPLETED: '已履约',
      AUTO_FULFILLED: '自动履约',
      NOT_REQUIRED: '无需履约',
      NONE: '无需履约',
      FAILED: '履约异常',
      CANCELLED: '已取消'
    }
    return labels[normalized] || String(value || '未知状态')
  }
  const statusTagType = (value?: string | null): TagType => {
    const normalized = String(value || '').toUpperCase()
    if (['FULFILLED', 'COMPLETED', 'AUTO_FULFILLED'].includes(normalized)) return 'success'
    if (
      ['PENDING', 'UNFULFILLED', 'PENDING_FULFILLMENT', 'WAITING_FULFILLMENT'].includes(normalized)
    ) {
      return 'warning'
    }
    if (normalized === 'FAILED') return 'danger'
    return 'info'
  }
  const isPendingStatus = (value?: string | null) =>
    ['PENDING', 'UNFULFILLED', 'PENDING_FULFILLMENT', 'WAITING_FULFILLMENT', '待履约'].includes(
      String(value || '').toUpperCase()
    )
  const canFulfill = (record: LotteryDrawRecord) =>
    safePrizeType(record) === 'GOODS' && isPendingStatus(record.status) && record.id != null

  const loadRecords = async () => {
    loading.value = true
    try {
      const response = await getLotteryDraws({
        keyword: query.keyword,
        prizeType: query.prizeType,
        status: query.status
      })
      records.value = sourceRows(response).filter((record): record is LotteryDrawRecord =>
        Boolean(record && typeof record === 'object')
      )
      backendTotal.value =
        !Array.isArray(response) && response && typeof response.total === 'number'
          ? response.total
          : records.value.length
    } catch (error) {
      records.value = []
      backendTotal.value = 0
      ElMessage.error(error instanceof Error ? error.message : '中奖记录加载失败')
    } finally {
      loading.value = false
    }
  }

  const resetFilters = async () => {
    query.keyword = ''
    query.prizeType = ''
    query.status = ''
    await loadRecords()
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
      ElMessage.error(error instanceof Error ? error.message : '赠品履约核销失败')
    } finally {
      fulfilling.value = false
    }
  }

  const goConfig = () => {
    router.push({ name: 'FreshLuckyDraw' })
  }

  onMounted(loadRecords)
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
    grid-template-columns: minmax(260px, 1.7fr) minmax(150px, 0.8fr) minmax(150px, 0.8fr) auto auto;
    gap: 9px;
  }

  .records-table {
    :deep(.el-table__cell) {
      vertical-align: middle;
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
