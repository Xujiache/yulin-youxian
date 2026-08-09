<template>
  <div class="fresh-page">
    <div class="fresh-page__head">
      <div>
        <h1 class="fresh-page__title">备货总览</h1>
        <p class="fresh-page__desc">按配送日期汇总商品需求，适合次日达提前备货。</p>
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
          <ElTag type="success" effect="light">按商品汇总</ElTag>
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
        <ElTableColumn label="需备数量" min-width="190">
          <template #default="{ row }">
            <div class="quantity-cell">
              <strong class="stock-quantity">{{ row.quantity }}{{ row.saleUnit }}</strong>
              <ElPopover
                v-if="row.specDetails && row.specDetails.length > 0"
                placement="bottom-start"
                :width="320"
                trigger="click"
              >
                <template #reference>
                  <ElButton size="small" type="primary" plain class="spec-detail-btn">
                    详情
                  </ElButton>
                </template>
                <div class="spec-popover-content">
                  <div class="spec-popover-header">
                    <span class="spec-popover-title">{{ row.productName }}</span>
                    <ElTag type="success" size="small" effect="light">规格明细</ElTag>
                  </div>
                  <ElTable :data="row.specDetails" size="small" border class="spec-table">
                    <ElTableColumn prop="specificationText" label="规格" min-width="140" />
                    <ElTableColumn label="需备数量" width="110" align="right">
                      <template #default="spec">
                        <strong class="spec-quantity">{{ spec.row.quantity }}{{ spec.row.saleUnit || row.saleUnit }}</strong>
                      </template>
                    </ElTableColumn>
                  </ElTable>
                </div>
              </ElPopover>
            </div>
          </template>
        </ElTableColumn>
        <ElTableColumn prop="orderCount" label="订单数" width="110" />
        <ElTableColumn label="预计金额" width="130">
          <template #default="{ row }">
            <span class="money">{{ money(row.amount) }}</span>
          </template>
        </ElTableColumn>
        <ElTableColumn label="关联订单" min-width="260">
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
  import { getStockOverview, type StockOverviewItem } from '@/api/admin'
  import { resolveFreshAssetUrl } from '@/utils/fresh-assets'

  defineOptions({ name: 'FreshStockOverview' })

  const dateText = (offsetDays = 0) => {
    const date = new Date()
    date.setDate(date.getDate() + offsetDays)
    const offset = date.getTimezoneOffset() * 60000
    return new Date(date.getTime() - offset).toISOString().slice(0, 10)
  }

  const loading = ref(false)
  const selectedDate = ref(dateText(1))
  const items = ref<StockOverviewItem[]>([])
  const imageUrl = resolveFreshAssetUrl
  const money = (value: number) => `￥${(Number(value || 0) / 100).toFixed(2)}`

  const orderCount = computed(() => {
    const orderNos = new Set<string>()
    items.value.forEach((item) => (item.orderNos || []).forEach((orderNo) => orderNos.add(orderNo)))
    return orderNos.size
  })
  const totalAmount = computed(() => items.value.reduce((sum, item) => sum + Number(item.amount || 0), 0))

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

  onMounted(loadData)
</script>

<style scoped lang="scss">
  @use '../style.scss';

  .stock-actions {
    display: flex;
    align-items: center;
    gap: 10px;
  }

  .quantity-cell {
    display: flex;
    align-items: center;
    gap: 10px;
  }

  .stock-quantity {
    color: #007a39;
    font-size: 16px;
  }

  .spec-detail-btn {
    padding: 2px 8px;
    height: 24px;
    font-size: 12px;
  }

  .spec-popover-content {
    padding: 4px 0;
  }

  .spec-popover-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 10px;

    .spec-popover-title {
      font-weight: 600;
      color: #1a1a1a;
      font-size: 14px;
    }
  }

  .spec-table {
    margin-top: 6px;
  }

  .spec-quantity {
    color: #007a39;
    font-size: 14px;
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
