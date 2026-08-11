<template>
  <div class="fresh-page">
    <div class="fresh-page__head">
      <div>
        <h1 class="fresh-page__title">配送任务</h1>
        <p class="fresh-page__desc">按状态、日期、骑手筛选配送任务，查看事件时间轴与凭证照片。</p>
      </div>
      <ElButton type="primary" :loading="loading" @click="loadTasks">刷新</ElButton>
    </div>

    <ElCard class="fresh-card" shadow="never">
      <div class="task-filter">
        <div class="task-filter__row">
          <span class="task-filter__label">任务状态</span>
          <ElSegmented v-model="status" :options="TASK_STATUS_OPTIONS" @change="reload" />
        </div>
        <div class="task-filter__fields">
          <ElInput
            v-model="keyword"
            clearable
            placeholder="搜索任务号、订单号、收货人或地址"
            @keyup.enter="reload"
          />
          <ElDatePicker
            v-model="date"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="全部日期"
            clearable
            @change="reload"
          />
          <ElSelect v-model="riderId" clearable placeholder="全部骑手" @change="reload">
            <ElOption
              v-for="rider in riders"
              :key="rider.id"
              :label="`${rider.name}（${rider.riderNo}）`"
              :value="rider.id"
            />
          </ElSelect>
          <ElSelect
            :model-value="undefined"
            disabled
            placeholder="区域筛选暂不可用"
            title="当前任务列表接口不支持区域筛选"
          />
          <ElCheckbox :model-value="false" disabled title="当前任务列表接口不支持超时风险筛选">
            仅超时/高风险（暂不可用）
          </ElCheckbox>
          <ElButton @click="resetFilters">重置筛选</ElButton>
        </div>
      </div>

      <ElTable
        v-loading="loading"
        :data="tasks"
        row-key="taskId"
        empty-text="没有符合条件的配送任务"
      >
        <ElTableColumn label="任务号" width="180">
          <template #default="{ row }">
            <div class="task-cell">
              <strong>{{ row.taskNo }}</strong>
              <div class="task-cell__tags">
                <ElTag v-if="isColdChain(row)" size="small" type="primary" effect="dark">
                  {{ row.coldChainText || '冷链' }}
                </ElTag>
                <ElTag v-if="isFarDelivery(row)" size="small" type="warning" effect="light">
                  远单
                </ElTag>
              </div>
            </div>
          </template>
        </ElTableColumn>
        <ElTableColumn label="订单号" prop="orderNo" width="180" />
        <ElTableColumn label="状态" width="100">
          <template #default="{ row }">
            <ElTag :type="taskStatusTag(row.status)" effect="light">
              {{ row.statusText || taskStatusText(row.status) }}
            </ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="收货地址" min-width="240">
          <template #default="{ row }">
            <div class="task-cell">
              <strong>
                {{
                  [row.areaLabel, row.buildingLabel, row.addressDetail].filter(Boolean).join(' ')
                }}
              </strong>
              <span class="muted"
                >{{ row.receiverName }} · {{ row.receiverPhone || row.receiverPhoneMasked }}</span
              >
            </div>
          </template>
        </ElTableColumn>
        <ElTableColumn label="时间窗" width="150">
          <template #default="{ row }">
            <div class="task-cell">
              <span>{{ row.slotLabel || '—' }}</span>
              <span class="muted">承诺 {{ clockText(row.promisedAt) }}</span>
            </div>
          </template>
        </ElTableColumn>
        <ElTableColumn label="ETA" width="90">
          <template #default="{ row }">{{ row.etaAt ? clockText(row.etaAt) : '—' }}</template>
        </ElTableColumn>
        <ElTableColumn label="超时" width="110">
          <template #default="{ row }">
            <ElTag :type="riskTag(row.overtimeRisk)" effect="plain" size="small">
              {{ riskText(row.overtimeRisk) }}
            </ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <ElButton size="small" @click="openDetail(row)">详情</ElButton>
          </template>
        </ElTableColumn>
      </ElTable>

      <div class="task-pager">
        <ElPagination
          v-model:current-page="page"
          v-model:page-size="pageSize"
          :total="total"
          :page-sizes="[20, 50, 100]"
          layout="total, sizes, prev, pager, next"
          background
          @current-change="loadTasks"
          @size-change="reload"
        />
      </div>
    </ElCard>

    <ElDialog v-model="detailVisible" :title="`任务详情 ${detail?.taskNo || ''}`" width="820px">
      <ElTabs v-model="activeTab">
        <ElTabPane label="基本信息" name="basic">
          <ElDescriptions v-if="detail" :column="2" border>
            <ElDescriptionsItem label="任务号">{{ detail.taskNo }}</ElDescriptionsItem>
            <ElDescriptionsItem label="订单号">{{ detail.orderNo }}</ElDescriptionsItem>
            <ElDescriptionsItem label="任务状态">
              {{ detail.statusText || taskStatusText(detail.status) }}
            </ElDescriptionsItem>
            <ElDescriptionsItem label="超时风险">{{
              riskText(detail.overtimeRisk)
            }}</ElDescriptionsItem>
            <ElDescriptionsItem label="站点顺序">
              {{
                detail.seqNo && detail.totalStops
                  ? `第 ${detail.seqNo}/${detail.totalStops} 站`
                  : '—'
              }}
            </ElDescriptionsItem>
            <ElDescriptionsItem label="收货人">
              {{ detail.receiverName }} {{ detail.receiverPhoneMasked }}
            </ElDescriptionsItem>
            <ElDescriptionsItem label="隐私号">
              {{ detail.callNumber || '—' }}
              <ElTag v-if="detail.phoneDegraded" size="small" type="warning">已降级</ElTag>
            </ElDescriptionsItem>
            <ElDescriptionsItem label="收货地址" :span="2">
              {{
                [detail.areaLabel, detail.buildingLabel, detail.addressDetail]
                  .filter(Boolean)
                  .join(' ')
              }}
            </ElDescriptionsItem>
            <ElDescriptionsItem label="货品">
              {{ detail.itemCount }} 件 / {{ Number(detail.totalWeightKg || 0).toFixed(1) }} kg /
              {{ detail.packageCount }} 包
            </ElDescriptionsItem>
            <ElDescriptionsItem label="温层">{{
              detail.coldChainText || detail.coldChainLevel
            }}</ElDescriptionsItem>
            <ElDescriptionsItem label="时间窗">{{ detail.slotLabel || '—' }}</ElDescriptionsItem>
            <ElDescriptionsItem label="承诺送达">{{
              fullTimeText(detail.promisedAt)
            }}</ElDescriptionsItem>
            <ElDescriptionsItem label="预计送达">{{
              detail.etaAt ? fullTimeText(detail.etaAt) : '—'
            }}</ElDescriptionsItem>
            <ElDescriptionsItem label="商品摘要" :span="2">{{
              detail.goodsSummary || '—'
            }}</ElDescriptionsItem>
            <ElDescriptionsItem label="顾客备注" :span="2">{{
              detail.customerRemark || '无'
            }}</ElDescriptionsItem>
            <ElDescriptionsItem label="配送要求" :span="2">
              {{ detail.deliveryInstruction || '无' }}
            </ElDescriptionsItem>
          </ElDescriptions>
          <div v-if="detailItems.length > 0" class="task-items">
            <strong>商品明细</strong>
            <ElTable :data="detailItems" border size="small">
              <ElTableColumn prop="productName" label="商品" min-width="180" />
              <ElTableColumn label="数量" width="100">
                <template #default="{ row }">{{ row.quantity }} {{ row.unit }}</template>
              </ElTableColumn>
              <ElTableColumn label="重量" width="100">
                <template #default="{ row }">
                  {{ row.weightKg === null ? '—' : `${Number(row.weightKg).toFixed(2)} kg` }}
                </template>
              </ElTableColumn>
              <ElTableColumn label="金额" width="110">
                <template #default="{ row }">{{
                  row.amount === null ? '—' : money(row.amount)
                }}</template>
              </ElTableColumn>
            </ElTable>
          </div>
          <div v-if="detail && detail.highlightNotes.length > 0" class="task-notes">
            <ElTag v-for="note in detail.highlightNotes" :key="note" type="warning" effect="light">
              {{ note }}
            </ElTag>
          </div>
        </ElTabPane>

        <ElTabPane label="事件时间轴" name="events">
          <TaskTimeline :events="events" :loading="eventsLoading" />
        </ElTabPane>

        <ElTabPane label="凭证照片" name="evidences">
          <div v-loading="evidencesLoading" class="evidence-grid">
            <ElEmpty
              v-if="!evidencesLoading && evidences.length === 0"
              description="该任务还没有上传凭证照片"
            />
            <figure v-for="item in evidences" :key="item.id" class="evidence-item">
              <ElImage
                class="evidence-item__img"
                :src="resolveFreshAssetUrl(item.fileUrl)"
                :preview-src-list="evidenceUrls"
                :initial-index="evidenceUrls.indexOf(resolveFreshAssetUrl(item.fileUrl))"
                preview-teleported
                fit="cover"
              />
              <figcaption>
                <strong>{{ evidenceTypeText(item.evidenceType) }}</strong>
                <span>{{ item.capturedAt ? dateTimeText(item.capturedAt) : '时间未记录' }}</span>
              </figcaption>
            </figure>
          </div>
        </ElTabPane>
      </ElTabs>
    </ElDialog>
  </div>
</template>

<script setup lang="ts">
  import { ElMessage } from 'element-plus'
  import {
    getDeliveryTaskDetail,
    getDeliveryTasks,
    getRiders,
    type AdminRider,
    type DeliveryEvidence,
    type DeliveryTaskCard,
    type DeliveryTaskItem,
    type DeliveryTaskEvent
  } from '@/api/delivery'
  import { resolveFreshAssetUrl } from '@/utils/fresh-assets'
  import TaskTimeline from '../components/TaskTimeline.vue'
  import {
    clockText,
    dateTimeText,
    evidenceTypeText,
    fullTimeText,
    isColdChain,
    isFarDelivery,
    money,
    riskTag,
    riskText,
    TASK_STATUS_OPTIONS,
    taskStatusTag,
    taskStatusText
  } from '../utils'

  defineOptions({ name: 'FreshDeliveryTasks' })

  const route = useRoute()

  const status = ref('')
  const keyword = ref(String(route.query.keyword || ''))
  const date = ref('')
  const riderId = ref<number | undefined>()

  const loading = ref(false)
  const tasks = ref<DeliveryTaskCard[]>([])
  const riders = ref<AdminRider[]>([])
  const page = ref(1)
  const pageSize = ref(20)
  const total = ref(0)

  const detailVisible = ref(false)
  const detail = ref<DeliveryTaskCard | null>(null)
  const detailItems = ref<DeliveryTaskItem[]>([])
  const activeTab = ref('basic')
  const events = ref<DeliveryTaskEvent[]>([])
  const eventsLoading = ref(false)
  const evidences = ref<DeliveryEvidence[]>([])
  const evidencesLoading = ref(false)

  const evidenceUrls = computed(() =>
    evidences.value.map((item) => resolveFreshAssetUrl(item.fileUrl)).filter(Boolean)
  )

  const loadTasks = async () => {
    loading.value = true
    try {
      const result = await getDeliveryTasks({
        status: status.value || undefined,
        date: date.value || undefined,
        riderId: riderId.value,
        keyword: keyword.value.trim() || undefined,
        page: page.value,
        pageSize: pageSize.value
      })
      tasks.value = result.items || []
      total.value = result.total || 0
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '配送任务加载失败')
    } finally {
      loading.value = false
    }
  }

  const reload = async () => {
    page.value = 1
    await loadTasks()
  }

  const resetFilters = async () => {
    status.value = ''
    keyword.value = ''
    date.value = ''
    riderId.value = undefined
    await reload()
  }

  const loadRiders = async () => {
    try {
      const result = await getRiders({ pageSize: 200 })
      riders.value = result.items || []
    } catch {
      // 骑手下拉只影响筛选，失败时保持为空即可
    }
  }

  const openDetailById = async (taskId: number, fallback?: DeliveryTaskCard) => {
    detail.value = fallback || null
    detailItems.value = []
    activeTab.value = 'basic'
    detailVisible.value = true
    events.value = []
    evidences.value = []
    eventsLoading.value = true
    evidencesLoading.value = true
    try {
      const result = await getDeliveryTaskDetail(taskId)
      detail.value = result.card
      detailItems.value = result.items || []
      events.value = result.events || []
      evidences.value = result.evidences || []
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '任务详情加载失败')
      if (!fallback) detailVisible.value = false
    } finally {
      eventsLoading.value = false
      evidencesLoading.value = false
    }
  }

  const openDetail = (row: DeliveryTaskCard) => openDetailById(row.taskId, row)

  onMounted(async () => {
    await Promise.all([loadTasks(), loadRiders()])
    const queryTaskId = Number(route.query.taskId || 0)
    if (queryTaskId > 0) await openDetailById(queryTaskId)
  })
</script>

<style scoped lang="scss">
  @use '../../style.scss';

  .task-filter {
    display: grid;
    gap: 14px;
    padding: 16px;
    margin-bottom: 16px;
    border: 1px solid var(--el-border-color);
    border-radius: 12px;
    background: var(--el-fill-color-lighter);
  }

  .task-filter__row {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 12px;
  }

  .task-filter__label {
    color: var(--el-text-color-secondary);
    font-size: 13px;
  }

  .task-filter__fields {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 12px;

    :deep(.el-input),
    :deep(.el-select),
    :deep(.el-date-editor) {
      width: 220px;
    }
  }

  .task-cell {
    display: grid;
    gap: 4px;

    strong {
      color: var(--art-gray-900);
      overflow-wrap: anywhere;
    }

    span {
      font-size: 12px;
    }
  }

  .task-cell__tags {
    display: flex;
    flex-wrap: wrap;
    gap: 4px;
  }

  .task-pager {
    display: flex;
    justify-content: flex-end;
    margin-top: 16px;
  }

  .task-notes {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    margin-top: 12px;
  }

  .task-items {
    display: grid;
    gap: 8px;
    margin-top: 14px;
  }

  .evidence-grid {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(160px, 1fr));
    gap: 14px;
    min-height: 140px;
  }

  .evidence-item {
    margin: 0;

    figcaption {
      display: grid;
      gap: 2px;
      margin-top: 6px;

      strong {
        color: var(--art-gray-900);
        font-size: 13px;
      }

      span {
        color: var(--art-gray-600);
        font-size: 12px;
      }
    }
  }

  .evidence-item__img {
    width: 100%;
    height: 140px;
    overflow: hidden;
    border: 1px solid var(--art-border-color);
    border-radius: 10px;
  }
</style>
