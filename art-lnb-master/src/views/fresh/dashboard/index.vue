<template>
  <div class="fresh-page">
    <div class="fresh-page__head">
      <div>
        <h1 class="fresh-page__title">数据概览</h1>
        <p class="fresh-page__desc">门店订单、销售额和售后待办集中看板。</p>
      </div>
      <ElButton type="primary" :loading="loading" @click="loadSummary">刷新数据</ElButton>
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
          <strong>今日待处理</strong>
          <ElTag type="success" effect="light">禹邻优鲜</ElTag>
        </div>
      </template>
      <ElAlert
        title="待接单、备货中订单和待审核退款会在这里汇总，建议营业时间内保持后台在线。"
        type="success"
        :closable="false"
      />
    </ElCard>
  </div>
</template>

<script setup lang="ts">
  import { ElMessage } from 'element-plus'
  import { getDashboardSummary, type DashboardSummary } from '@/api/admin'

  defineOptions({ name: 'FreshDashboard' })

  const loading = ref(false)
  const summary = ref<DashboardSummary>({
    todayOrderCount: 0,
    todaySalesAmount: 0,
    pendingOrderCount: 0,
    refundPendingCount: 0
  })

  const money = (value: number) => `￥${(Number(value || 0) / 100).toFixed(2)}`

  const metrics = computed(() => [
    { label: '今日订单', value: summary.value.todayOrderCount },
    { label: '今日销售额', value: money(summary.value.todaySalesAmount) },
    { label: '待处理订单', value: summary.value.pendingOrderCount },
    { label: '待审核退款', value: summary.value.refundPendingCount }
  ])

  const loadSummary = async () => {
    loading.value = true
    try {
      summary.value = await getDashboardSummary()
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '数据概览加载失败')
    } finally {
      loading.value = false
    }
  }

  onMounted(loadSummary)
</script>

<style scoped lang="scss">
  @use '../style.scss';
</style>
