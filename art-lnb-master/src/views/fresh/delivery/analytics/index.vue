<template>
  <div class="fresh-page">
    <div class="fresh-page__head">
      <div>
        <h1 class="fresh-page__title">配送分析</h1>
        <p class="fresh-page__desc">准时率、配送时长、骑手人效、异常分布与楼栋难度榜。</p>
      </div>
      <div class="fresh-toolbar__right">
        <ElDatePicker
          v-model="range"
          type="daterange"
          value-format="YYYY-MM-DD"
          start-placeholder="开始日期"
          end-placeholder="结束日期"
          @change="loadAll"
        />
        <ElButton type="primary" :loading="loading" @click="loadAll">刷新</ElButton>
      </div>
    </div>

    <div class="metric-grid">
      <div v-for="item in metrics" :key="item.label" class="metric-card">
        <div class="metric-card__label">{{ item.label }}</div>
        <div class="metric-card__value">{{ item.value }}</div>
      </div>
    </div>

    <div v-loading="loading" class="analytics-grid">
      <ElCard class="fresh-card analytics-card--wide" shadow="never">
        <template #header><strong>日单量与准时率</strong></template>
        <div ref="trendRef" class="analytics-chart"></div>
      </ElCard>

      <ElCard class="fresh-card" shadow="never">
        <template #header><strong>平均配送时长趋势</strong></template>
        <div ref="durationRef" class="analytics-chart"></div>
      </ElCard>

      <ElCard class="fresh-card" shadow="never">
        <template #header><strong>异常类型分布</strong></template>
        <div ref="exceptionRef" class="analytics-chart"></div>
      </ElCard>

      <ElCard class="fresh-card analytics-card--wide" shadow="never">
        <template #header><strong>骑手人效对比</strong></template>
        <div ref="riderRef" class="analytics-chart"></div>
      </ElCard>

      <ElCard class="fresh-card" shadow="never">
        <template #header>
          <div class="fresh-toolbar">
            <strong>楼栋难度榜 Top 20</strong>
            <span class="muted">按平均交付时长排序</span>
          </div>
        </template>
        <div ref="buildingRef" class="analytics-chart analytics-chart--tall"></div>
      </ElCard>

      <ElCard class="fresh-card" shadow="never">
        <template #header><strong>时段单量热力</strong></template>
        <div ref="hourRef" class="analytics-chart"></div>
      </ElCard>
    </div>
  </div>
</template>

<script setup lang="ts">
  import { ElMessage } from 'element-plus'
  import { HeatmapChart } from 'echarts/charts'
  import { echarts, type EChartsOption } from '@/plugins/echarts'
  import { useChart } from '@/hooks/core/useChart'
  import {
    getDeliveryAnalyticsOverview,
    getDeliveryAnalyticsRiders,
    type DeliveryAnalyticsOverview,
    type RiderAnalyticsItem
  } from '@/api/delivery'
  import { humanDuration, percent } from '../utils'

  defineOptions({ name: 'FreshDeliveryAnalytics' })

  // 模板的 echarts 按需注册里没有热力图，这里补注册（只影响本页引入的图表类型）
  echarts.use([HeatmapChart])

  const COLORS = ['#00843D', '#60C041', '#409EFF', '#E6A23C', '#F56C6C', '#7B61FF', '#909399']

  const shiftDate = (days: number) => {
    const date = new Date()
    date.setDate(date.getDate() + days)
    const offset = date.getTimezoneOffset() * 60000
    return new Date(date.getTime() - offset).toISOString().slice(0, 10)
  }

  const range = ref<string[]>([shiftDate(-6), shiftDate(0)])
  const loading = ref(false)
  const overview = ref<DeliveryAnalyticsOverview | null>(null)
  const riderStats = ref<RiderAnalyticsItem[]>([])

  const { chartRef: trendRef, initChart: initTrend } = useChart()
  const { chartRef: durationRef, initChart: initDuration } = useChart()
  const { chartRef: exceptionRef, initChart: initException } = useChart()
  const { chartRef: riderRef, initChart: initRider } = useChart()
  const { chartRef: buildingRef, initChart: initBuilding } = useChart()
  const { chartRef: hourRef, initChart: initHour } = useChart()

  const metrics = computed(() => [
    { label: '准时率', value: percent(overview.value?.onTimeRate) },
    {
      label: '平均配送时长',
      value: `${Number(overview.value?.avgDeliveryMinutes || 0).toFixed(1)} 分钟`
    },
    { label: '总任务数', value: String(overview.value?.totalTaskCount ?? 0) },
    {
      label: '异常总数',
      value: String(
        (overview.value?.exceptionDistribution || []).reduce((sum, item) => sum + item.count, 0)
      )
    }
  ])

  const baseGrid = { top: 34, left: 12, right: 16, bottom: 40, containLabel: true }

  const renderTrend = () => {
    const trend = overview.value?.dailyTrend || []
    if (trend.length === 0) {
      initTrend({}, true)
      return
    }
    const option: EChartsOption = {
      tooltip: { trigger: 'axis' },
      legend: { bottom: 0, data: ['单量', '送达', '准时率'] },
      grid: baseGrid,
      xAxis: { type: 'category', data: trend.map((item) => item.date) },
      yAxis: [
        { type: 'value', name: '单量' },
        {
          type: 'value',
          name: '准时率',
          min: 0,
          max: 1,
          axisLabel: { formatter: (value: number) => `${Math.round(value * 100)}%` }
        }
      ],
      series: [
        {
          name: '单量',
          type: 'line',
          smooth: true,
          data: trend.map((item) => item.taskCount),
          itemStyle: { color: COLORS[0] }
        },
        {
          name: '送达',
          type: 'line',
          smooth: true,
          data: trend.map((item) => item.deliveredCount),
          itemStyle: { color: COLORS[1] }
        },
        {
          name: '准时率',
          type: 'line',
          smooth: true,
          yAxisIndex: 1,
          data: trend.map((item) => Number(item.onTimeRate || 0)),
          itemStyle: { color: COLORS[3] }
        }
      ]
    }
    initTrend(option)
  }

  const renderDuration = () => {
    const trend = overview.value?.dailyTrend || []
    if (trend.length === 0) {
      initDuration({}, true)
      return
    }
    const option: EChartsOption = {
      tooltip: { trigger: 'axis', valueFormatter: (value) => `${value} 分钟` },
      grid: baseGrid,
      xAxis: { type: 'category', data: trend.map((item) => item.date) },
      yAxis: { type: 'value', name: '分钟' },
      series: [
        {
          name: '平均配送时长',
          type: 'line',
          smooth: true,
          areaStyle: { opacity: 0.15 },
          data: trend.map((item) => Number(item.avgDeliveryMinutes || 0)),
          itemStyle: { color: COLORS[2] }
        }
      ]
    }
    initDuration(option)
  }

  const renderException = () => {
    const slices = overview.value?.exceptionDistribution || []
    if (slices.length === 0) {
      initException({}, true)
      return
    }
    const option: EChartsOption = {
      tooltip: { trigger: 'item', formatter: '{b}：{c} 单（{d}%）' },
      legend: { bottom: 0, type: 'scroll' },
      color: COLORS,
      series: [
        {
          name: '异常类型',
          type: 'pie',
          radius: ['42%', '70%'],
          center: ['50%', '45%'],
          avoidLabelOverlap: true,
          label: { formatter: '{b} {d}%' },
          data: slices.map((item) => ({
            name: exceptionLabel(item.exceptionType),
            value: item.count
          }))
        }
      ]
    }
    initException(option)
  }

  const renderRiders = () => {
    const list = riderStats.value.slice(0, 20)
    if (list.length === 0) {
      initRider({}, true)
      return
    }
    const option: EChartsOption = {
      tooltip: { trigger: 'axis' },
      legend: { bottom: 0, data: ['送达单量', '准时率'] },
      grid: baseGrid,
      xAxis: {
        type: 'category',
        data: list.map((item) => item.riderName),
        axisLabel: { interval: 0, rotate: list.length > 8 ? 30 : 0 }
      },
      yAxis: [
        { type: 'value', name: '单量' },
        {
          type: 'value',
          name: '准时率',
          min: 0,
          max: 1,
          axisLabel: { formatter: (value: number) => `${Math.round(value * 100)}%` }
        }
      ],
      series: [
        {
          name: '送达单量',
          type: 'bar',
          barMaxWidth: 28,
          data: list.map((item) => item.deliveredCount),
          itemStyle: { color: COLORS[0], borderRadius: [6, 6, 0, 0] }
        },
        {
          name: '准时率',
          type: 'line',
          yAxisIndex: 1,
          data: list.map((item) => Number(item.onTimeRate || 0)),
          itemStyle: { color: COLORS[3] }
        }
      ]
    }
    initRider(option)
  }

  const renderBuildings = () => {
    const list = (overview.value?.buildingDifficultyTop || []).slice(0, 20)
    if (list.length === 0) {
      initBuilding({}, true)
      return
    }
    const sorted = list.slice().sort((a, b) => a.avgHandoffSeconds - b.avgHandoffSeconds)
    const option: EChartsOption = {
      tooltip: {
        trigger: 'axis',
        axisPointer: { type: 'shadow' },
        valueFormatter: (value) => humanDuration(Number(value))
      },
      grid: { top: 16, left: 12, right: 40, bottom: 20, containLabel: true },
      xAxis: { type: 'value', name: '秒' },
      yAxis: {
        type: 'category',
        data: sorted.map((item) => `${item.areaLabel} ${item.buildingLabel}`),
        axisLabel: { width: 140, overflow: 'truncate' }
      },
      series: [
        {
          name: '平均交付时长',
          type: 'bar',
          barMaxWidth: 16,
          data: sorted.map((item) => Math.round(item.avgHandoffSeconds)),
          itemStyle: { color: COLORS[4], borderRadius: [0, 6, 6, 0] },
          label: { show: true, position: 'right', formatter: '{c}s' }
        }
      ]
    }
    initBuilding(option)
  }

  const renderHours = () => {
    const points = overview.value?.hourlyHeatmap || []
    if (points.length === 0) {
      initHour({}, true)
      return
    }
    const sorted = points.slice().sort((a, b) => a.hour - b.hour)
    const max = Math.max(...sorted.map((item) => item.taskCount), 1)
    const option: EChartsOption = {
      tooltip: {
        position: 'top',
        formatter: (params: any) => `${params.name} 时：${params.value[2]} 单`
      },
      grid: { top: 20, left: 12, right: 16, bottom: 60, containLabel: true },
      xAxis: {
        type: 'category',
        data: sorted.map((item) => `${item.hour}`),
        splitArea: { show: true }
      },
      yAxis: { type: 'category', data: ['单量'], splitArea: { show: true } },
      visualMap: {
        min: 0,
        max,
        calculable: true,
        orient: 'horizontal',
        left: 'center',
        bottom: 8,
        inRange: { color: ['#EAF7EF', '#60C041', '#00843D'] }
      },
      series: [
        {
          name: '时段单量',
          type: 'heatmap',
          data: sorted.map((item, index) => [index, 0, item.taskCount]),
          label: { show: true },
          emphasis: { itemStyle: { shadowBlur: 8, shadowColor: 'rgba(0,0,0,0.3)' } }
        }
      ]
    }
    initHour(option)
  }

  const EXCEPTION_LABELS: Record<string, string> = {
    CUSTOMER_UNREACHABLE: '联系不上顾客',
    WRONG_ADDRESS: '地址有误',
    CUSTOMER_REFUSED: '顾客拒收',
    GOODS_DAMAGED: '商品破损',
    GOODS_LEAKING: '商品洒漏',
    WEIGHT_DISPUTE: '重量争议',
    ITEM_MISSING: '缺件少件',
    ACCESS_DENIED: '无法进入小区',
    VEHICLE_FAILURE: '车辆故障',
    RIDER_UNWELL: '骑手不适',
    BAD_WEATHER: '恶劣天气',
    STORE_SLOW: '门店出货慢',
    OTHER: '其他'
  }

  function exceptionLabel(type: string) {
    return EXCEPTION_LABELS[type] || type
  }

  const renderAll = () => {
    renderTrend()
    renderDuration()
    renderException()
    renderRiders()
    renderBuildings()
    renderHours()
  }

  const loadAll = async () => {
    loading.value = true
    const params =
      range.value && range.value.length === 2
        ? { from: range.value[0], to: range.value[1] }
        : undefined
    try {
      const [overviewResult, riderResult] = await Promise.all([
        getDeliveryAnalyticsOverview(params),
        getDeliveryAnalyticsRiders(params)
      ])
      overview.value = overviewResult
      riderStats.value = riderResult || []
    } catch (error) {
      overview.value = null
      riderStats.value = []
      ElMessage.error(error instanceof Error ? error.message : '配送分析加载失败')
    } finally {
      loading.value = false
      await nextTick()
      renderAll()
    }
  }

  onMounted(loadAll)
</script>

<style scoped lang="scss">
  @use '../../style.scss';

  .analytics-grid {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 16px;
  }

  .analytics-card--wide {
    grid-column: 1 / -1;
  }

  .analytics-chart {
    width: 100%;
    height: 300px;

    &--tall {
      height: 480px;
    }
  }

  @media (max-width: 1100px) {
    .analytics-grid {
      grid-template-columns: 1fr;
    }
  }
</style>
