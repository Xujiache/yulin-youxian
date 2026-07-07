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
      <div v-for="item in metrics" :key="item.label" class="metric-card">
        <div class="metric-card__label">{{ item.label }}</div>
        <div class="metric-card__value">{{ item.value }}</div>
      </div>
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
  import {
    exportDashboard,
    getDashboardSummary,
    type DashboardSummary
  } from '@/api/admin'

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
    { label: '选择日期订单', value: summary.value.todayOrderCount },
    { label: '选择日期销售额', value: money(summary.value.todaySalesAmount) },
    { label: '待处理订单', value: summary.value.pendingOrderCount },
    { label: '待审核退款', value: summary.value.refundPendingCount }
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

  @media (max-width: 960px) {
    .export-panel {
      align-items: flex-start;
      flex-direction: column;
    }
  }
</style>
