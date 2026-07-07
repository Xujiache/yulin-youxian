<template>
  <div class="fresh-page">
    <div class="fresh-page__head">
      <div>
        <h1 class="fresh-page__title">数据概览</h1>
        <p class="fresh-page__desc">按日期查看门店订单、销售额和待处理事项，并导出明细表格。</p>
      </div>
      <div class="dashboard-actions">
        <ElDatePicker
          v-model="selectedDate"
          type="date"
          value-format="YYYY-MM-DD"
          placeholder="选择日期"
          @change="loadSummary"
        />
        <ElButton type="primary" :loading="loading" @click="loadSummary">刷新数据</ElButton>
      </div>
    </div>

    <div class="metric-grid">
      <ElPopover
        v-for="item in metrics"
        :key="item.label"
        trigger="hover"
        placement="bottom-start"
        :width="280"
        :teleported="false"
      >
        <template #reference>
          <div class="metric-card metric-card--hoverable">
            <div class="metric-card__label">{{ item.label }}</div>
            <div class="metric-card__value">{{ item.value }}</div>
            <div class="metric-card__hint">悬浮查看详情</div>
          </div>
        </template>
        <div class="metric-detail">
          <div class="metric-detail__title">{{ item.detailTitle }}</div>
          <div class="metric-detail__value">{{ item.value }}</div>
          <div v-for="line in item.details" :key="line" class="metric-detail__line">
            {{ line }}
          </div>
        </div>
      </ElPopover>
    </div>

    <ElCard class="fresh-card" shadow="never">
      <template #header>
        <div class="fresh-toolbar">
          <strong>明细导出</strong>
          <ElTag type="success" effect="light">禹邻优鲜</ElTag>
        </div>
      </template>
      <div class="export-panel">
        <div>
          <h3>导出订单明细表</h3>
          <p>可选择任意时间范围，导出订单、商品、配送、退款和实付金额等详细数据。</p>
        </div>
        <div class="export-actions">
          <ElDatePicker
            v-model="exportRange"
            type="daterange"
            value-format="YYYY-MM-DD"
            start-placeholder="开始日期"
            end-placeholder="结束日期"
          />
          <ElButton type="primary" plain :loading="exporting" @click="downloadExport">
            导出表格
          </ElButton>
        </div>
      </div>
    </ElCard>

    <ElCard class="fresh-card" shadow="never">
      <template #header>
        <div class="fresh-toolbar">
          <strong>运营提示</strong>
          <ElButton text type="primary" @click="goStockOverview">查看备货总览</ElButton>
        </div>
      </template>
      <ElAlert
        title="待接单、备货中订单和待审核退款会在这里汇总；次日达备货请进入备货总览按配送日期查看。"
        type="success"
        :closable="false"
      />
    </ElCard>
  </div>
</template>

<script setup lang="ts">
  import { ElMessage } from 'element-plus'
  import { exportDashboard, getDashboardSummary, type DashboardSummary } from '@/api/admin'

  defineOptions({ name: 'FreshDashboard' })

  const router = useRouter()
  const todayText = () => {
    const date = new Date()
    const offset = date.getTimezoneOffset() * 60000
    return new Date(date.getTime() - offset).toISOString().slice(0, 10)
  }

  const loading = ref(false)
  const exporting = ref(false)
  const selectedDate = ref(todayText())
  const exportRange = ref<string[]>([todayText(), todayText()])
  const summary = ref<DashboardSummary>({
    todayOrderCount: 0,
    todaySalesAmount: 0,
    pendingOrderCount: 0,
    refundPendingCount: 0
  })

  const money = (value: number) => `￥${(Number(value || 0) / 100).toFixed(2)}`

  const metrics = computed(() => [
    {
      label: '选择日期订单',
      value: summary.value.todayOrderCount,
      detailTitle: '订单统计详情',
      details: [
        `统计日期：${selectedDate.value}`,
        '口径：该日期创建的全部订单。',
        '用途：判断当天客流和下单活跃度。'
      ]
    },
    {
      label: '选择日期销售额',
      value: money(summary.value.todaySalesAmount),
      detailTitle: '销售额统计详情',
      details: [
        `统计日期：${selectedDate.value}`,
        '口径：该日期已支付订单的实付金额汇总。',
        '退款金额请以导出的订单明细核对。'
      ]
    },
    {
      label: '待处理订单',
      value: summary.value.pendingOrderCount,
      detailTitle: '待处理订单详情',
      details: [
        `统计日期：${selectedDate.value}`,
        '包含：待支付、已支付/待接单、备货中订单。',
        '建议：营业时段内优先处理待接单和备货中订单。'
      ]
    },
    {
      label: '待审核退款',
      value: summary.value.refundPendingCount,
      detailTitle: '退款审核详情',
      details: [
        `统计日期：${selectedDate.value}`,
        '口径：该日期订单关联的待审核退款申请。',
        '建议：进入售后退款页面查看凭证并审核。'
      ]
    }
  ])

  const loadSummary = async () => {
    loading.value = true
    try {
      summary.value = await getDashboardSummary(selectedDate.value)
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '数据概览加载失败')
    } finally {
      loading.value = false
    }
  }

  const downloadExport = async () => {
    if (!exportRange.value || exportRange.value.length !== 2) {
      ElMessage.warning('请选择导出时间范围')
      return
    }
    exporting.value = true
    try {
      const result = await exportDashboard(exportRange.value[0], exportRange.value[1])
      const blob = new Blob([`\uFEFF${result.content || ''}`], {
        type: 'text/csv;charset=utf-8;'
      })
      const url = URL.createObjectURL(blob)
      const link = document.createElement('a')
      link.href = url
      link.download = result.filename || `订单明细_${exportRange.value[0]}_${exportRange.value[1]}.csv`
      link.click()
      URL.revokeObjectURL(url)
      ElMessage.success('表格已导出')
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '表格导出失败')
    } finally {
      exporting.value = false
    }
  }

  const goStockOverview = () => {
    router.push('/fresh/stock-overview')
  }

  onMounted(loadSummary)
</script>

<style scoped lang="scss">
  @use '../style.scss';

  .dashboard-actions,
  .export-actions {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 10px;
  }

  .export-panel {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 24px;

    h3 {
      margin: 0;
      color: var(--art-gray-900);
      font-size: 18px;
    }

    p {
      margin: 8px 0 0;
      color: var(--art-gray-600);
      font-size: 13px;
    }
  }

  .metric-card--hoverable {
    position: relative;
    cursor: pointer;
    transition:
      border-color 0.2s ease,
      box-shadow 0.2s ease,
      transform 0.2s ease;

    &:hover {
      border-color: rgba(0, 132, 61, 0.28);
      box-shadow: 0 14px 32px rgba(0, 132, 61, 0.1);
      transform: translateY(-2px);
    }
  }

  .metric-card__hint {
    margin-top: 8px;
    color: #7a8a82;
    font-size: 12px;
  }

  .metric-detail {
    display: flex;
    flex-direction: column;
    gap: 8px;
    padding: 4px 2px;
  }

  .metric-detail__title {
    color: var(--art-gray-900);
    font-size: 15px;
    font-weight: 700;
  }

  .metric-detail__value {
    color: #007a39;
    font-size: 24px;
    font-weight: 800;
    line-height: 1.2;
  }

  .metric-detail__line {
    color: var(--art-gray-600);
    font-size: 13px;
    line-height: 20px;
  }

  @media (max-width: 960px) {
    .export-panel {
      align-items: flex-start;
      flex-direction: column;
    }
  }
</style>
