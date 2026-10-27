<template>
  <div class="fresh-page">
    <div class="fresh-page__head">
      <div>
        <h1 class="fresh-page__title">骑手结算</h1>
        <p class="fresh-page__desc">生成、确认与支付骑手结算单，支持人工调整与 CSV 导出。</p>
      </div>
      <div class="fresh-toolbar__right">
        <ElButton :loading="loading" @click="loadSettlements">刷新</ElButton>
        <ElButton type="primary" @click="openGenerate">生成结算单</ElButton>
      </div>
    </div>

    <ElCard class="fresh-card" shadow="never">
      <div class="fresh-toolbar">
        <div class="fresh-toolbar__left">
          <ElSelect
            v-model="riderId"
            clearable
            placeholder="全部骑手"
            class="filter-item"
            @change="reload"
          >
            <ElOption
              v-for="rider in riders"
              :key="rider.id"
              :label="`${rider.name}（${rider.riderNo}）`"
              :value="rider.id"
            />
          </ElSelect>
          <ElSelect
            v-model="status"
            clearable
            placeholder="全部状态"
            class="filter-item"
            @change="reload"
          >
            <ElOption
              v-for="item in SETTLEMENT_STATUS_OPTIONS"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </ElSelect>
          <ElDatePicker
            v-model="period"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="按周期开始日筛选"
            clearable
            @change="reload"
          />
          <ElButton @click="resetFilters">重置筛选</ElButton>
        </div>
        <div class="fresh-toolbar__right">
          <span class="muted">金额单位为元，由后端「分」换算</span>
        </div>
      </div>

      <ElTable v-loading="loading" :data="items" row-key="id" empty-text="还没有结算单">
        <ElTableColumn label="结算单号" width="180">
          <template #default="{ row }">
            <div class="settlement-cell">
              <strong>{{ row.settlementNo }}</strong>
              <span class="muted">{{ dateTimeText(row.createdAt) }}</span>
            </div>
          </template>
        </ElTableColumn>
        <ElTableColumn label="骑手" width="110">
          <template #default="{ row }">{{ row.riderName }}</template>
        </ElTableColumn>
        <ElTableColumn label="周期" width="200">
          <template #default="{ row }">
            <div class="settlement-cell">
              <span>{{ periodTypeText(row.periodType) }}</span>
              <span class="muted">{{ row.periodStart }} ~ {{ row.periodEnd }}</span>
            </div>
          </template>
        </ElTableColumn>
        <ElTableColumn label="任务数" width="80" prop="taskCount" />
        <ElTableColumn label="基础" width="100">
          <template #default="{ row }">{{ money(row.baseAmount) }}</template>
        </ElTableColumn>
        <ElTableColumn label="奖励" width="100">
          <template #default="{ row }">{{ money(row.bonusAmount) }}</template>
        </ElTableColumn>
        <ElTableColumn label="扣款" width="100">
          <template #default="{ row }">{{ money(row.deductionAmount) }}</template>
        </ElTableColumn>
        <ElTableColumn label="调整" width="100">
          <template #default="{ row }">{{ money(row.adjustAmount) }}</template>
        </ElTableColumn>
        <ElTableColumn label="合计" width="120">
          <template #default="{ row }">
            <span class="money">{{ money(row.totalAmount) }}</span>
          </template>
        </ElTableColumn>
        <ElTableColumn label="状态" width="100">
          <template #default="{ row }">
            <ElTag :type="settlementStatusTag(row.status)" effect="light" size="small">
              {{ settlementStatusText(row.status) }}
            </ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="操作" width="270" fixed="right">
          <template #default="{ row }">
            <div class="settlement-actions">
              <ElButton
                v-if="row.status === 'DRAFT'"
                size="small"
                type="primary"
                @click="handleConfirm(row)"
              >
                确认
              </ElButton>
              <ElButton
                v-if="row.status === 'CONFIRMED'"
                size="small"
                type="success"
                @click="handlePay(row)"
              >
                标记已支付
              </ElButton>
              <ElButton
                v-if="row.status !== 'PAID' && row.status !== 'VOID'"
                size="small"
                plain
                @click="openAdjust(row)"
              >
                调整
              </ElButton>
              <ElButton
                v-if="row.status !== 'PAID' && row.status !== 'VOID'"
                size="small"
                type="danger"
                plain
                @click="handleVoid(row)"
              >
                作废
              </ElButton>
              <ElButton size="small" text type="primary" @click="handleExport(row)"
                >导出 CSV</ElButton
              >
            </div>
          </template>
        </ElTableColumn>
      </ElTable>

      <div class="settlement-pager">
        <ElPagination
          v-model:current-page="page"
          v-model:page-size="pageSize"
          :total="total"
          :page-sizes="[20, 50, 100]"
          layout="total, sizes, prev, pager, next"
          background
          @current-change="loadSettlements"
          @size-change="reload"
        />
      </div>
    </ElCard>

    <ElDialog v-model="generateVisible" title="生成结算单" width="520px">
      <ElForm label-width="100px">
        <ElFormItem label="周期类型">
          <ElRadioGroup v-model="generateForm.periodType">
            <ElRadioButton
              v-for="item in PERIOD_TYPE_OPTIONS"
              :key="item.value"
              :value="item.value"
            >
              {{ item.label }}
            </ElRadioButton>
          </ElRadioGroup>
        </ElFormItem>
        <ElFormItem label="结算区间">
          <ElDatePicker
            v-model="generateRange"
            type="daterange"
            value-format="YYYY-MM-DD"
            start-placeholder="开始日期"
            end-placeholder="结束日期"
            class="form-full"
          />
        </ElFormItem>
        <ElFormItem label="骑手范围">
          <ElSelect
            v-model="generateForm.riderIds"
            multiple
            collapse-tags
            collapse-tags-tooltip
            clearable
            placeholder="留空表示全部骑手"
            class="form-full"
          >
            <ElOption
              v-for="rider in riders"
              :key="rider.id"
              :label="`${rider.name}（${rider.riderNo}）`"
              :value="rider.id"
            />
          </ElSelect>
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="generateVisible = false">取消</ElButton>
        <ElButton type="primary" :loading="generating" @click="submitGenerate">生成</ElButton>
      </template>
    </ElDialog>

    <ElDialog v-model="adjustVisible" title="人工调整" width="480px">
      <ElForm v-if="adjustTarget" label-width="100px">
        <ElFormItem label="结算单">
          <span>{{ adjustTarget.settlementNo }} · {{ adjustTarget.riderName }}</span>
        </ElFormItem>
        <ElFormItem label="当前合计">
          <span class="money">{{ money(adjustTarget.totalAmount) }}</span>
        </ElFormItem>
        <ElFormItem label="调整金额">
          <ElInputNumber v-model="adjustYuan" :step="1" :precision="2" controls-position="right" />
          <span class="form-hint">单位元，可为负数（扣减）</span>
        </ElFormItem>
        <ElFormItem label="调整原因">
          <ElInput
            v-model="adjustRemark"
            type="textarea"
            :rows="3"
            maxlength="200"
            show-word-limit
            placeholder="必填，会写入结算单备注"
          />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="adjustVisible = false">取消</ElButton>
        <ElButton type="primary" :loading="adjusting" @click="submitAdjust">保存调整</ElButton>
      </template>
    </ElDialog>
  </div>
</template>

<script setup lang="ts">
  import { ElMessage, ElMessageBox } from 'element-plus'
  import {
    adjustSettlement,
    confirmSettlement,
    exportSettlement,
    generateSettlements,
    getRiders,
    getSettlements,
    paySettlement,
    voidSettlement,
    type AdminRider,
    type RiderSettlement,
    type SettlementGeneratePayload
  } from '@/api/delivery'
  import {
    dateTimeText,
    money,
    PERIOD_TYPE_OPTIONS,
    periodTypeText,
    SETTLEMENT_STATUS_OPTIONS,
    settlementStatusTag,
    settlementStatusText
  } from '../utils'

  defineOptions({ name: 'FreshDeliverySettlements' })

  const route = useRoute()

  const queryRiderId = Number(route.query.riderId || 0)
  const riderId = ref<number | undefined>(queryRiderId || undefined)
  const status = ref('')
  const period = ref('')
  const loading = ref(false)
  const items = ref<RiderSettlement[]>([])
  const riders = ref<AdminRider[]>([])
  const page = ref(1)
  const pageSize = ref(20)
  const total = ref(0)

  const generateVisible = ref(false)
  const generating = ref(false)
  const generateRange = ref<string[]>([])
  const generateForm = ref<SettlementGeneratePayload>({
    periodType: 'WEEKLY',
    periodStart: '',
    periodEnd: '',
    riderIds: []
  })

  const adjustVisible = ref(false)
  const adjusting = ref(false)
  const adjustTarget = ref<RiderSettlement | null>(null)
  const adjustYuan = ref(0)
  const adjustRemark = ref('')

  const loadSettlements = async () => {
    loading.value = true
    try {
      const result = await getSettlements({
        riderId: riderId.value,
        status: status.value || undefined,
        period: period.value || undefined,
        page: page.value,
        pageSize: pageSize.value
      })
      items.value = result.items || []
      total.value = result.total || 0
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '结算单加载失败')
    } finally {
      loading.value = false
    }
  }

  const reload = async () => {
    page.value = 1
    await loadSettlements()
  }

  const resetFilters = async () => {
    riderId.value = undefined
    status.value = ''
    period.value = ''
    await reload()
  }

  const loadRiders = async () => {
    try {
      const result = await getRiders({ pageSize: 200 })
      riders.value = result.items || []
    } catch {
      riders.value = []
    }
  }

  const openGenerate = () => {
    generateForm.value = { periodType: 'WEEKLY', periodStart: '', periodEnd: '', riderIds: [] }
    generateRange.value = []
    generateVisible.value = true
  }

  const submitGenerate = async () => {
    const range = generateRange.value
    if (!range || range.length !== 2) {
      ElMessage.warning('请选择结算区间')
      return
    }
    try {
      const scope = generateForm.value.riderIds?.length
        ? `${generateForm.value.riderIds.length} 名骑手`
        : '全部骑手'
      await ElMessageBox.confirm(
        `确认按 ${range[0]} ~ ${range[1]} 为 ${scope} 生成结算单？同周期重复生成会被后端幂等拦截。`,
        '生成结算单',
        { type: 'warning', confirmButtonText: '确认生成', cancelButtonText: '取消' }
      )
      generating.value = true
      const result = await generateSettlements({
        periodType: generateForm.value.periodType,
        periodStart: range[0],
        periodEnd: range[1],
        riderIds: generateForm.value.riderIds?.length ? generateForm.value.riderIds : undefined
      })
      ElMessage.success(`已生成 ${result.generatedCount} 张结算单`)
      generateVisible.value = false
      await reload()
    } catch (error) {
      if (error !== 'cancel' && error !== 'close') {
        ElMessage.error(error instanceof Error ? error.message : '生成失败')
      }
    } finally {
      generating.value = false
    }
  }

  const runAction = async (
    row: RiderSettlement,
    label: string,
    action: (id: number) => Promise<RiderSettlement>,
    tip: string
  ) => {
    try {
      await ElMessageBox.confirm(tip, label, {
        type: 'warning',
        confirmButtonText: `确认${label}`,
        cancelButtonText: '取消'
      })
      await action(row.id)
      ElMessage.success(`${label}成功`)
      await loadSettlements()
    } catch (error) {
      if (error !== 'cancel' && error !== 'close') {
        ElMessage.error(error instanceof Error ? error.message : '操作失败')
      }
    }
  }

  const handleConfirm = (row: RiderSettlement) =>
    runAction(
      row,
      '确认',
      confirmSettlement,
      `确认结算单 ${row.settlementNo}（${money(row.totalAmount)}）？确认后金额不可再自动重算。`
    )

  const handlePay = (row: RiderSettlement) =>
    runAction(
      row,
      '标记已支付',
      paySettlement,
      `确认 ${row.riderName} 的 ${money(row.totalAmount)} 已经打款？标记后不可撤销。`
    )

  const handleVoid = (row: RiderSettlement) =>
    runAction(row, '作废', voidSettlement, `作废结算单 ${row.settlementNo}？作废后需要重新生成。`)

  const openAdjust = (row: RiderSettlement) => {
    adjustTarget.value = row
    adjustYuan.value = Number((Number(row.adjustAmount || 0) / 100).toFixed(2))
    adjustRemark.value = row.remark || ''
    adjustVisible.value = true
  }

  const submitAdjust = async () => {
    const target = adjustTarget.value
    if (!target) return
    if (!adjustRemark.value.trim()) {
      ElMessage.warning('请填写调整原因')
      return
    }
    const amount = Math.round(Number(adjustYuan.value || 0) * 100)
    try {
      await ElMessageBox.confirm(
        `确认把 ${target.settlementNo} 的调整金额设为 ${money(amount)}？`,
        '人工调整',
        { type: 'warning', confirmButtonText: '确认调整', cancelButtonText: '取消' }
      )
      adjusting.value = true
      await adjustSettlement(target.id, amount, adjustRemark.value.trim())
      ElMessage.success('调整已保存')
      adjustVisible.value = false
      await loadSettlements()
    } catch (error) {
      if (error !== 'cancel' && error !== 'close') {
        ElMessage.error(error instanceof Error ? error.message : '调整失败')
      }
    } finally {
      adjusting.value = false
    }
  }

  const handleExport = async (row: RiderSettlement) => {
    try {
      const result = await exportSettlement(row.id)
      const blob = new Blob([`\uFEFF${result.content || ''}`], { type: 'text/csv;charset=utf-8;' })
      const url = URL.createObjectURL(blob)
      const link = document.createElement('a')
      link.href = url
      link.download = result.filename || `骑手结算_${row.settlementNo}.csv`
      link.click()
      URL.revokeObjectURL(url)
      ElMessage.success('结算单已导出')
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '导出失败')
    }
  }

  onMounted(async () => {
    await Promise.all([loadSettlements(), loadRiders()])
  })
</script>

<style scoped lang="scss">
  @use '../../style.scss';

  .filter-item {
    width: 190px;
  }

  .settlement-cell {
    display: grid;
    gap: 4px;

    strong {
      color: var(--art-gray-900);
    }

    span {
      font-size: 12px;
    }
  }

  .settlement-actions {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;

    :deep(.el-button + .el-button) {
      margin-left: 0;
    }

    :deep(.el-button) {
      padding-right: 8px;
      padding-left: 8px;
    }
  }

  .settlement-pager {
    display: flex;
    justify-content: flex-end;
    margin-top: 16px;
  }

  .form-hint {
    margin-left: 10px;
    color: var(--art-gray-600);
    font-size: 12px;
  }
</style>
