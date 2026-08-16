<template>
  <div class="fresh-page">
    <div class="fresh-page__head">
      <div>
        <h1 class="fresh-page__title">备货总览</h1>
        <p class="fresh-page__desc">按配送日期汇总商品和规格，适合次日达提前备货。</p>
      </div>
      <div class="stock-actions">
        <ElDatePicker
          v-model="selectedDate"
          type="date"
          value-format="YYYY-MM-DD"
          placeholder="选择配送日期"
          @change="loadData"
        />
        <ElButton type="primary" :loading="loading" @click="loadData">刷新</ElButton>
        <ElButton type="success" plain :loading="exporting" :disabled="!selectedDate" @click="exportSheet">
          导出当天备货表
        </ElButton>
      </div>
    </div>

    <div class="metric-grid">
      <div class="metric-card">
        <div class="metric-card__label">需备商品</div>
        <div class="metric-card__value">{{ items.length }}</div>
      </div>
      <div class="metric-card">
        <div class="metric-card__label">关联订单</div>
        <div class="metric-card__value">{{ orderCount }}</div>
      </div>
      <div class="metric-card">
        <div class="metric-card__label">预计销售额</div>
        <div class="metric-card__value">{{ money(totalAmount) }}</div>
      </div>
      <div class="metric-card">
        <div class="metric-card__label">配送日期</div>
        <div class="metric-card__value date-value">{{ selectedDate }}</div>
      </div>
    </div>

    <ElCard class="fresh-card" shadow="never">
      <template #header>
        <div class="fresh-toolbar">
          <strong>当日备货清单</strong>
          <ElTag type="success" effect="light">按商品汇总，规格直接展示</ElTag>
        </div>
      </template>
      <ElTable v-loading="loading" :data="items" border empty-text="所选日期暂无需备货订单">
        <ElTableColumn label="图片" width="86">
          <template #default="{ row }">
            <ElImage
              v-if="row.imageUrl"
              class="image-thumb"
              :src="imageUrl(row.imageUrl)"
              fit="cover"
              :preview-src-list="[imageUrl(row.imageUrl)]"
              preview-teleported
            />
            <div v-else class="image-thumb empty-thumb">无图</div>
          </template>
        </ElTableColumn>
        <ElTableColumn prop="productName" label="商品" min-width="180" />
        <ElTableColumn label="规格明细" min-width="240">
          <template #default="{ row }">
            <div v-if="specLines(row).length" class="spec-list">
              <div v-for="spec in specLines(row)" :key="`${row.productId}-${spec.skuId || 'nosku'}-${spec.specificationText}`" class="spec-line">
                <span class="spec-name">{{ spec.specificationText }}</span>
                <strong class="spec-quantity">{{ quantityText(spec.quantity, spec.saleUnit || row.saleUnit) }}</strong>
              </div>
            </div>
            <span v-else class="muted">默认规格</span>
          </template>
        </ElTableColumn>
        <ElTableColumn label="需备数量" width="120">
          <template #default="{ row }">
            <strong class="stock-quantity">{{ quantityText(row.quantity, row.saleUnit) }}</strong>
          </template>
        </ElTableColumn>
        <ElTableColumn prop="orderCount" label="订单数" width="90" />
        <ElTableColumn label="预计金额" width="120">
          <template #default="{ row }">
            <span class="money">{{ money(row.amount) }}</span>
          </template>
        </ElTableColumn>
        <ElTableColumn label="关联订单" min-width="240">
          <template #default="{ row }">
            <div class="order-tags">
              <ElTag v-for="orderNo in row.orderNos" :key="orderNo" effect="plain">
                {{ orderNo }}
              </ElTag>
            </div>
          </template>
        </ElTableColumn>
      </ElTable>
    </ElCard>
  </div>
</template>

<script setup lang="ts">
  import { ElMessage } from 'element-plus'
  import * as XLSX from 'xlsx'
  import {
    exportStockOverview,
    getStockOverview,
    type StockOverviewItem,
    type StockOverviewSpecItem
  } from '@/api/admin'
  import { resolveFreshAssetUrl } from '@/utils/fresh-assets'

  defineOptions({ name: 'FreshStockOverview' })

  const dateText = (offsetDays = 0) => {
    const now = new Date()
    const parts = new Intl.DateTimeFormat('en-US', {
      timeZone: 'Asia/Shanghai',
      year: 'numeric',
      month: '2-digit',
      day: '2-digit'
    }).formatToParts(now)
    const year = Number(parts.find((part) => part.type === 'year')?.value)
    const month = Number(parts.find((part) => part.type === 'month')?.value)
    const day = Number(parts.find((part) => part.type === 'day')?.value)
    const date = new Date(Date.UTC(year, month - 1, day + offsetDays))
    return date.toISOString().slice(0, 10)
  }

  const loading = ref(false)
  const exporting = ref(false)
  const selectedDate = ref(dateText(1))
  const items = ref<StockOverviewItem[]>([])
  const imageUrl = resolveFreshAssetUrl
  const money = (value: number) => `￥${(Number(value || 0) / 100).toFixed(2)}`

  const quantityText = (quantity: number | string, unit?: string) => {
    const raw = String(quantity ?? '0')
    const normalized = raw.replace(/(\.\d*?)0+$/, '$1').replace(/\.$/, '')
    return `${normalized || '0'}${unit || ''}`
  }

  const specLines = (row: StockOverviewItem): StockOverviewSpecItem[] => row.specDetails || []

  const orderCount = computed(() => {
    const orderNos = new Set<string>()
    items.value.forEach((item) => (item.orderNos || []).forEach((orderNo) => orderNos.add(orderNo)))
    return orderNos.size
  })
  const totalAmount = computed(() =>
    items.value.reduce((sum, item) => sum + Number(item.amount || 0), 0)
  )

  const appendSheet = (workbook: XLSX.WorkBook, rows: string[][], name: string, widths: number[]) => {
    const sheet = XLSX.utils.aoa_to_sheet(rows || [])
    sheet['!cols'] = widths.map((width) => ({ wch: width }))
    XLSX.utils.book_append_sheet(workbook, sheet, name)
  }

  const loadData = async () => {
    loading.value = true
    try {
      items.value = await getStockOverview(selectedDate.value)
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '备货清单加载失败')
    } finally {
      loading.value = false
    }
  }

  const exportSheet = async () => {
    if (!selectedDate.value) {
      ElMessage.warning('请先选择配送日期')
      return
    }
    exporting.value = true
    try {
      const result = await exportStockOverview(selectedDate.value)
      const workbook = XLSX.utils.book_new()
      appendSheet(workbook, result.productSheet, '商品汇总', [12, 10, 22, 12, 8, 36, 8, 14, 36])
      appendSheet(workbook, result.specSheet, '规格明细', [12, 10, 22, 22, 10, 16, 12, 8, 8, 14, 36])
      appendSheet(workbook, result.orderSheet, '订单明细', [12, 22, 14, 28, 10, 14, 36, 10, 22, 18, 14, 8, 8, 12, 12, 8, 20, 20, 12])
      XLSX.writeFile(workbook, result.filename || `备货总览_${selectedDate.value}.xlsx`)
      ElMessage.success(`已导出 ${selectedDate.value} 备货表`)
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '备货表导出失败')
    } finally {
      exporting.value = false
    }
  }

  onMounted(loadData)
</script>

<style scoped lang="scss">
  @use '../style.scss';

  .stock-actions {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: 10px;
  }

  .stock-quantity {
    color: #007a39;
    font-size: 16px;
  }

  .spec-list {
    display: flex;
    flex-direction: column;
    gap: 6px;
  }

  .spec-line {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    padding: 4px 8px;
    background: #f6faf7;
    border-radius: 6px;
  }

  .spec-name {
    color: #1f2a24;
    font-size: 13px;
    line-height: 20px;
  }

  .spec-quantity {
    color: #007a39;
    font-size: 13px;
    white-space: nowrap;
  }

  .muted {
    color: #8a938d;
  }

  .date-value {
    font-size: 20px;
  }

  .order-tags {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
  }
</style>
