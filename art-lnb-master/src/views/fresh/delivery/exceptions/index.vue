<template>
  <div class="fresh-page">
    <div class="fresh-page__head">
      <div>
        <h1 class="fresh-page__title">异常处理</h1>
        <p class="fresh-page__desc">处理配送异常上报，查看凭证照片并选择处理方式。</p>
      </div>
      <ElButton type="primary" :loading="loading" @click="loadExceptions">刷新</ElButton>
    </div>

    <ElCard class="fresh-card" shadow="never">
      <div class="fresh-toolbar">
        <div class="fresh-toolbar__left">
          <ElSelect
            v-model="status"
            clearable
            placeholder="全部状态"
            class="filter-item"
            @change="reload"
          >
            <ElOption
              v-for="item in EXCEPTION_STATUS_OPTIONS"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </ElSelect>
          <ElSelect
            v-model="type"
            clearable
            placeholder="全部类型"
            class="filter-item"
            @change="reload"
          >
            <ElOption
              v-for="item in EXCEPTION_TYPE_OPTIONS"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </ElSelect>
          <ElSelect
            v-model="severity"
            clearable
            placeholder="全部严重度"
            class="filter-item"
            @change="reload"
          >
            <ElOption
              v-for="item in SEVERITY_OPTIONS"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </ElSelect>
          <ElButton @click="resetFilters">重置筛选</ElButton>
        </div>
        <div class="fresh-toolbar__right">
          <span class="muted">未关闭异常需要在挂起到期前处理，超时会影响顾客体验</span>
        </div>
      </div>

      <ElTable
        v-loading="loading"
        :data="items"
        row-key="exceptionId"
        empty-text="没有符合条件的异常记录"
      >
        <ElTableColumn label="异常号" width="170">
          <template #default="{ row }">
            <div class="exception-cell">
              <strong>{{ row.exceptionNo }}</strong>
              <span class="muted">{{ dateTimeText(row.createdAt) }}</span>
            </div>
          </template>
        </ElTableColumn>
        <ElTableColumn label="类型" width="130">
          <template #default="{ row }">{{ exceptionTypeText(row.exceptionType) }}</template>
        </ElTableColumn>
        <ElTableColumn label="严重度" width="90">
          <template #default="{ row }">
            <ElTag :type="severityTag(row.severity)" effect="dark" size="small">
              {{ severityText(row.severity) }}
            </ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="状态" width="90">
          <template #default="{ row }">
            <ElTag :type="exceptionStatusTag(row.status)" effect="light" size="small">
              {{ exceptionStatusText(row.status) }}
            </ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="任务" width="130">
          <template #default="{ row }">
            <strong v-if="row.taskId">ID {{ row.taskId }}</strong>
            <span v-else class="muted">未关联</span>
          </template>
        </ElTableColumn>
        <ElTableColumn label="描述与处理" min-width="240">
          <template #default="{ row }">
            <div class="exception-cell">
              <span>{{ row.description || '无描述' }}</span>
              <span v-if="row.resolutionType" class="muted">
                处理：{{ resolutionLabel(row.resolutionType) }}
                {{ row.riderExempt ? '（已免除骑手责任）' : '（判定骑手责任）' }}
              </span>
            </div>
          </template>
        </ElTableColumn>
        <ElTableColumn label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <div class="exception-actions">
              <ElButton
                v-if="row.status === 'OPEN' || row.status === 'PROCESSING'"
                size="small"
                type="primary"
                @click="openHandle(row)"
              >
                处理
              </ElButton>
              <ElButton size="small" :disabled="!row.taskId" @click="goTask(row)">
                查看任务
              </ElButton>
            </div>
          </template>
        </ElTableColumn>
      </ElTable>

      <div class="exception-pager">
        <ElPagination
          v-model:current-page="page"
          v-model:page-size="pageSize"
          :total="total"
          :page-sizes="[20, 50, 100]"
          layout="total, sizes, prev, pager, next"
          background
          @current-change="loadExceptions"
          @size-change="reload"
        />
      </div>
    </ElCard>

    <ExceptionHandleDialog v-model="handleVisible" :exception="active" @done="loadExceptions" />
  </div>
</template>

<script setup lang="ts">
  import { ElMessage } from 'element-plus'
  import { getExceptions, type DeliveryExceptionItem } from '@/api/delivery'
  import ExceptionHandleDialog from '../components/ExceptionHandleDialog.vue'
  import {
    dateTimeText,
    EXCEPTION_STATUS_OPTIONS,
    EXCEPTION_TYPE_OPTIONS,
    exceptionStatusTag,
    exceptionStatusText,
    exceptionTypeText,
    RESOLUTION_OPTIONS,
    SEVERITY_OPTIONS,
    severityTag,
    severityText
  } from '../utils'

  defineOptions({ name: 'FreshDeliveryExceptions' })

  const router = useRouter()

  const status = ref('OPEN')
  const type = ref('')
  const severity = ref('')
  const loading = ref(false)
  const items = ref<DeliveryExceptionItem[]>([])
  const page = ref(1)
  const pageSize = ref(20)
  const total = ref(0)

  const handleVisible = ref(false)
  const active = ref<DeliveryExceptionItem | null>(null)

  const resolutionLabel = (value: string) =>
    RESOLUTION_OPTIONS.find((item) => item.value === value)?.label || value

  const loadExceptions = async () => {
    loading.value = true
    try {
      const result = await getExceptions({
        status: status.value || undefined,
        type: type.value || undefined,
        severity: severity.value || undefined,
        page: page.value,
        pageSize: pageSize.value
      })
      items.value = result.items || []
      total.value = result.total || 0
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '异常列表加载失败')
    } finally {
      loading.value = false
    }
  }

  const reload = async () => {
    page.value = 1
    await loadExceptions()
  }

  const resetFilters = async () => {
    status.value = ''
    type.value = ''
    severity.value = ''
    await reload()
  }

  const openHandle = (row: DeliveryExceptionItem) => {
    active.value = row
    handleVisible.value = true
  }

  const goTask = (row: DeliveryExceptionItem) => {
    if (!row.taskId) return
    router.push({ path: '/fresh/delivery/tasks', query: { taskId: String(row.taskId) } })
  }

  onMounted(loadExceptions)
</script>

<style scoped lang="scss">
  @use '../../style.scss';

  .filter-item {
    width: 180px;
  }

  .exception-cell {
    display: grid;
    gap: 4px;

    strong {
      color: var(--art-gray-900);
    }

    span {
      font-size: 12px;
      overflow-wrap: anywhere;
    }
  }

  .exception-actions {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;

    :deep(.el-button + .el-button) {
      margin-left: 0;
    }
  }

  .exception-pager {
    display: flex;
    justify-content: flex-end;
    margin-top: 16px;
  }
</style>
