<template>
  <div v-loading="loading" class="suggest-panel">
    <div v-if="task" class="suggest-panel__task">
      <strong>{{ task.taskNo }}</strong>
      <span>{{
        [task.areaLabel, task.buildingLabel, task.addressDetail].filter(Boolean).join(' ')
      }}</span>
      <span>
        {{ task.itemCount }} 件 · {{ Number(task.totalWeightKg || 0).toFixed(1) }} kg · 承诺
        {{ clockText(task.promisedAt) }}
      </span>
    </div>

    <ElAlert
      v-if="suggestion?.batchingHint"
      class="suggest-panel__hint"
      type="success"
      :closable="false"
      show-icon
      :title="`可并单：与 ${suggestion.batchingHint.mergeWithTaskIds.length} 个任务合并（${suggestion.batchingHint.reason}）`"
    />

    <ElEmpty
      v-if="!loading && candidates.length === 0"
      description="暂无可用候选骑手，请检查在岗运力或稍后重试"
    />

    <div v-for="(candidate, index) in candidates" :key="candidate.riderId" class="candidate">
      <div class="candidate__head">
        <span class="candidate__rank">{{ index + 1 }}</span>
        <strong>{{ candidate.riderName }}</strong>
        <ElTag
          v-if="candidate.riderId === suggestion?.recommendedRiderId"
          size="small"
          type="success"
        >
          系统推荐
        </ElTag>
        <span class="candidate__score">得分 {{ candidate.score.toFixed(4) }}</span>
      </div>

      <div class="candidate__meta">
        <span>新增里程 {{ distanceText(candidate.addedDistanceMeters) }}</span>
        <span>新增用时 {{ humanDuration(candidate.addedDurationSeconds) }}</span>
        <span v-if="candidate.estimatedArriveAt">
          预计送达 {{ clockText(candidate.estimatedArriveAt) }}
        </span>
        <ElTag size="small" :type="riskTag(candidate.overtimeRiskAfter)" effect="plain">
          派后风险 {{ riskText(candidate.overtimeRiskAfter) }}
        </ElTag>
      </div>

      <div class="candidate__bars">
        <div v-for="bar in breakdownBars(candidate)" :key="bar.label" class="candidate__bar">
          <span class="candidate__bar-label">{{ bar.label }}</span>
          <ElProgress
            :percentage="bar.percentage"
            :stroke-width="10"
            :show-text="false"
            :color="bar.color"
            class="candidate__bar-track"
          />
          <b>{{ bar.value.toFixed(2) }}</b>
        </div>
      </div>

      <div class="candidate__reason">推荐理由：{{ reasonText(candidate) }}</div>

      <div v-if="candidate.blockers.length > 0" class="candidate__blockers">
        <ElTag
          v-for="blocker in candidate.blockers"
          :key="blocker"
          size="small"
          type="danger"
          effect="light"
        >
          {{ blockerText(blocker) }}
        </ElTag>
        <span>存在阻断项，不能派单</span>
      </div>

      <div class="candidate__actions">
        <ElButton
          size="small"
          type="primary"
          :disabled="candidate.blockers.length > 0"
          @click="emit('adopt', candidate)"
        >
          采纳并派单
        </ElButton>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
  import type { AdminTaskCard, DispatchCandidate, DispatchSuggestion } from '@/api/delivery'
  import { blockerText, clockText, distanceText, humanDuration, riskTag, riskText } from '../utils'

  defineOptions({ name: 'DispatchSuggestPanel' })

  const props = withDefaults(
    defineProps<{
      suggestion?: DispatchSuggestion | null
      task?: AdminTaskCard | null
      loading?: boolean
    }>(),
    { suggestion: null, task: null, loading: false }
  )

  const emit = defineEmits<{
    (event: 'adopt', candidate: DispatchCandidate): void
  }>()

  const BAR_META = [
    { key: 'addedDistance', label: '顺路度', color: '#00843D' },
    { key: 'overtimeRisk', label: '超时风险', color: '#E6A23C' },
    { key: 'loadBalance', label: '负载均衡', color: '#409EFF' },
    { key: 'coldChain', label: '冷链匹配', color: '#7B61FF' },
    { key: 'riderLevel', label: '骑手等级', color: '#909399' }
  ] as const

  const candidates = computed(() => props.suggestion?.candidates || [])

  const breakdownBars = (candidate: DispatchCandidate) => {
    const values = BAR_META.map((meta) => Math.max(0, Number(candidate.breakdown[meta.key] || 0)))
    const max = Math.max(...values, 0.0001)
    return BAR_META.map((meta, index) => ({
      label: meta.label,
      color: meta.color,
      value: values[index],
      percentage: Math.round((values[index] / max) * 100)
    }))
  }

  const reasonText = (candidate: DispatchCandidate) => {
    const sorted = BAR_META.map((meta) => ({
      label: meta.label,
      value: Number(candidate.breakdown[meta.key] || 0)
    }))
      .sort((a, b) => b.value - a.value)
      .slice(0, 2)
      .filter((item) => item.value > 0)
    if (sorted.length === 0) return '各维度得分接近，按总分排序'
    return `${sorted.map((item) => item.label).join('、')}贡献最高，合计 ${sorted
      .reduce((sum, item) => sum + item.value, 0)
      .toFixed(2)} 分`
  }
</script>

<style scoped lang="scss">
  .suggest-panel {
    display: grid;
    gap: 12px;
    min-height: 120px;
  }

  .suggest-panel__task {
    display: grid;
    gap: 4px;
    padding: 12px;
    border-radius: 10px;
    background: var(--el-fill-color-light);

    strong {
      color: var(--art-gray-900);
    }

    span {
      color: var(--art-gray-600);
      font-size: 12px;
    }
  }

  .candidate {
    display: grid;
    gap: 8px;
    padding: 12px;
    border: 1px solid var(--art-border-color);
    border-radius: 10px;
  }

  .candidate__head {
    display: flex;
    align-items: center;
    gap: 8px;
  }

  .candidate__rank {
    display: inline-grid;
    width: 22px;
    height: 22px;
    place-items: center;
    border-radius: 8px;
    color: var(--el-color-primary);
    font-size: 12px;
    font-weight: 700;
    background: var(--el-color-primary-light-9);
  }

  .candidate__score {
    margin-left: auto;
    color: #00843d;
    font-weight: 700;
  }

  .candidate__meta {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 4px 12px;
    color: var(--art-gray-600);
    font-size: 12px;
  }

  .candidate__bars {
    display: grid;
    gap: 6px;
  }

  .candidate__bar {
    display: flex;
    align-items: center;
    gap: 10px;
    font-size: 12px;

    b {
      min-width: 40px;
      color: var(--art-gray-800);
      text-align: right;
      font-variant-numeric: tabular-nums;
    }
  }

  .candidate__bar-label {
    min-width: 60px;
    color: var(--art-gray-600);
  }

  .candidate__bar-track {
    flex: 1;
    min-width: 0;
  }

  .candidate__reason {
    color: var(--art-gray-600);
    font-size: 12px;
  }

  .candidate__blockers {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 6px;
    color: var(--el-color-danger);
    font-size: 12px;
  }

  .candidate__actions {
    display: flex;
    justify-content: flex-end;
  }
</style>
