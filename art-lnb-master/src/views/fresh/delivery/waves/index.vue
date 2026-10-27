<template>
  <div class="fresh-page">
    <div class="fresh-page__head">
      <div>
        <h1 class="fresh-page__title">配送波次</h1>
        <p class="fresh-page__desc">查看每日配送波次、站点顺序与完成进度。</p>
      </div>
      <ElButton type="primary" :loading="loading" @click="loadWaves">刷新</ElButton>
    </div>

    <ElCard class="fresh-card" shadow="never">
      <div class="fresh-toolbar">
        <div class="fresh-toolbar__left">
          <ElDatePicker
            v-model="date"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="全部日期"
            clearable
            @change="reload"
          />
          <ElSelect
            v-model="status"
            clearable
            placeholder="全部状态"
            class="filter-item"
            @change="reload"
          >
            <ElOption
              v-for="item in WAVE_STATUS_OPTIONS"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </ElSelect>
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
            v-model="slotLabel"
            clearable
            placeholder="全部配送时段"
            class="filter-item"
            @change="reload"
          >
            <ElOption v-for="item in slotOptions" :key="item" :label="item" :value="item" />
          </ElSelect>
          <ElButton @click="resetFilters">重置筛选</ElButton>
        </div>
        <div class="fresh-toolbar__right">
          <span class="muted">共 {{ total }} 个波次</span>
        </div>
      </div>

      <ElTable v-loading="loading" :data="waves" row-key="waveId" empty-text="所选条件下没有波次">
        <ElTableColumn label="波次号" width="160">
          <template #default="{ row }">
            <div class="wave-cell">
              <strong>{{ row.waveNo }}</strong>
            </div>
          </template>
        </ElTableColumn>
        <ElTableColumn label="状态" width="100">
          <template #default="{ row }">
            <ElTag :type="waveStatusTag(row.status)" effect="light" size="small">
              {{ waveStatusText(row.status) }}
            </ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="配送时段" width="150">
          <template #default="{ row }">{{ row.slotLabel || '—' }}</template>
        </ElTableColumn>
        <ElTableColumn label="骑手" width="120">
          <template #default="{ row }">{{ row.riderName || '未指派' }}</template>
        </ElTableColumn>
        <ElTableColumn label="进度" width="160">
          <template #default="{ row }">
            <div class="wave-progress">
              <ElProgress
                :percentage="progressOf(row)"
                :stroke-width="8"
                :show-text="false"
                class="wave-progress__bar"
              />
              <span>{{ row.completedCount }}/{{ row.taskCount }}</span>
            </div>
          </template>
        </ElTableColumn>
        <ElTableColumn label="规划里程" width="110">
          <template #default="{ row }">{{ distanceText(row.planDistanceMeters) }}</template>
        </ElTableColumn>
        <ElTableColumn label="规划时长" width="110">
          <template #default="{ row }">{{ humanDuration(row.planDurationSeconds) }}</template>
        </ElTableColumn>
        <ElTableColumn label="预计返店" width="110">
          <template #default="{ row }">
            {{ row.planReturnAt ? clockText(row.planReturnAt) : '—' }}
          </template>
        </ElTableColumn>
        <ElTableColumn label="温层" width="100">
          <template #default="{ row }">
            <ElTag
              v-if="row.maxColdChainLevel && row.maxColdChainLevel !== 'NORMAL'"
              size="small"
              type="primary"
              effect="dark"
            >
              {{ row.maxColdChainLevel }}
            </ElTag>
            <span v-else class="muted">常温</span>
          </template>
        </ElTableColumn>
        <ElTableColumn label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <div class="wave-actions">
              <ElButton size="small" @click="goDetail(row)">详情</ElButton>
              <ElButton
                v-if="canCancel(row)"
                size="small"
                type="danger"
                plain
                @click="handleCancel(row)"
              >
                取消
              </ElButton>
            </div>
          </template>
        </ElTableColumn>
      </ElTable>

      <div class="wave-pager">
        <ElPagination
          v-model:current-page="page"
          v-model:page-size="pageSize"
          :total="total"
          :page-sizes="[20, 50, 100]"
          layout="total, sizes, prev, pager, next"
          background
          @current-change="loadWaves"
          @size-change="reload"
        />
      </div>
    </ElCard>
  </div>
</template>

<script setup lang="ts">
  import { ElMessage, ElMessageBox } from 'element-plus'
  import { getDeliverySlots } from '@/api/admin'
  import {
    cancelWave,
    getRiders,
    getWaves,
    type AdminRider,
    type WaveSummary
  } from '@/api/delivery'
  import {
    clockText,
    distanceText,
    humanDuration,
    WAVE_STATUS_OPTIONS,
    waveStatusTag,
    waveStatusText
  } from '../utils'

  defineOptions({ name: 'FreshDeliveryWaves' })

  const router = useRouter()

  const date = ref('')
  const status = ref('')
  const riderId = ref<number | undefined>()
  const slotLabel = ref('')
  const loading = ref(false)
  const waves = ref<WaveSummary[]>([])
  const riders = ref<AdminRider[]>([])
  const slotDict = ref<string[]>([])
  const page = ref(1)
  const pageSize = ref(20)
  const total = ref(0)

  const progressOf = (row: WaveSummary) =>
    row.taskCount > 0 ? Math.round((row.completedCount / row.taskCount) * 100) : 0

  /**
   * 服务端按 slot_label 精确匹配，手打「14:00」永远查不到「14:00-16:00」，
   * 所以候选值只能取真实存在过的字面量：时段字典 + 当前页出现过的历史时段。
   */
  const slotOptions = computed(() => {
    const labels = new Set(slotDict.value)
    waves.value.forEach((wave) => {
      const label = (wave.slotLabel || '').trim()
      if (label) labels.add(label)
    })
    // 选中值可能来自别的日期或翻页前的结果，留住它才不会一刷新就从下拉里消失
    if (slotLabel.value) labels.add(slotLabel.value)
    return [...labels].sort((left, right) => left.localeCompare(right))
  })

  /**
   * RETURNING 表示单已全部送达、只差骑手回店。此时取消会把已送达任务从波次摘下、
   * 删掉站点记录，骑手再也无法确认回店，而且「未完成的任务会退回待派队列」也不成立。
   */
  const canCancel = (row: WaveSummary) =>
    row.status !== 'COMPLETED' && row.status !== 'CANCELLED' && row.status !== 'RETURNING'

  const loadWaves = async () => {
    loading.value = true
    try {
      const result = await getWaves({
        date: date.value || undefined,
        status: status.value || undefined,
        riderId: riderId.value,
        slotLabel: slotLabel.value || undefined,
        page: page.value,
        pageSize: pageSize.value
      })
      waves.value = result.items || []
      total.value = result.total || 0
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '波次列表加载失败')
    } finally {
      loading.value = false
    }
  }

  const loadRiders = async () => {
    try {
      const result = await getRiders({ pageSize: 200 })
      riders.value = result.items || []
    } catch {
      riders.value = []
    }
  }

  const loadSlotDict = async () => {
    try {
      const result = await getDeliverySlots()
      // 停用的时段仍可能挂着历史波次，筛选场景不按 available 过滤
      slotDict.value = (result.items || []).map((slot) => slot.label).filter(Boolean)
    } catch {
      // 字典取不到就只靠当前列表出现过的时段兜底，不影响波次列表本身
      slotDict.value = []
    }
  }

  const resetFilters = async () => {
    date.value = ''
    status.value = ''
    riderId.value = undefined
    slotLabel.value = ''
    await reload()
  }

  const reload = async () => {
    page.value = 1
    await loadWaves()
  }

  const handleCancel = async (row: WaveSummary) => {
    try {
      const { value } = await ElMessageBox.prompt(
        `取消波次 ${row.waveNo} 后，其中未完成的任务会退回待派队列，请填写原因。`,
        '取消波次',
        {
          type: 'warning',
          confirmButtonText: '确认取消',
          cancelButtonText: '返回',
          inputValidator: (input: string) => (input && input.trim() ? true : '请填写原因')
        }
      )
      await cancelWave(row.waveId, value.trim())
      ElMessage.success('波次已取消')
      await loadWaves()
    } catch (error) {
      if (error !== 'cancel' && error !== 'close') {
        ElMessage.error(error instanceof Error ? error.message : '操作失败')
      }
    }
  }

  const goDetail = (row: WaveSummary) => {
    router.push(`/fresh/delivery/waves/${row.waveId}`)
  }

  onMounted(async () => {
    await Promise.all([loadWaves(), loadRiders(), loadSlotDict()])
  })
</script>

<style scoped lang="scss">
  @use '../../style.scss';

  .filter-item {
    width: 180px;
  }

  .wave-cell {
    display: grid;
    gap: 4px;

    strong {
      color: var(--art-gray-900);
    }

    span {
      font-size: 12px;
    }
  }

  .wave-progress {
    display: flex;
    align-items: center;
    gap: 8px;
    font-size: 12px;
  }

  .wave-progress__bar {
    flex: 1;
    min-width: 0;
  }

  .wave-actions {
    display: flex;
    gap: 6px;

    :deep(.el-button + .el-button) {
      margin-left: 0;
    }
  }

  .wave-pager {
    display: flex;
    justify-content: flex-end;
    margin-top: 16px;
  }
</style>
