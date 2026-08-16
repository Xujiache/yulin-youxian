<template>
  <aside class="probability-preview">
    <div class="preview-head">
      <div class="preview-head__signal" :class="{ 'is-live': enabled }">
        <span></span>
        {{ enabled ? '活动已开启' : '活动未开启' }}
      </div>
      <ArtSvgIcon icon="ri:pie-chart-2-line" />
    </div>

    <div class="preview-title">
      <span>LIVE ODDS</span>
      <h2>四项概率预览</h2>
      <p>这里显示名义概率。未达满减门槛、百分比结果为 0 或预算不足时，该奖项会退出候选，剩余概率重新归一化。</p>
    </div>

    <div class="budget-strip" :class="{ 'has-risk': !sumIsValid }">
      <ArtSvgIcon :icon="sumIsValid ? 'ri:safe-2-line' : 'ri:alarm-warning-line'" />
      <div>
        <span>概率总和</span>
        <strong :class="{ 'is-bad': !sumIsValid }">{{ sumText }}</strong>
        <small>{{ sumCaption }}</small>
      </div>
    </div>

    <div class="odds-list">
      <div v-for="prize in prizes" :key="prize.localId" class="odds-row">
        <div class="odds-row__main">
          <div>
            <strong>{{ prize.name }}</strong>
            <span>{{ ruleSummary(prize) }}</span>
          </div>
          <b>{{ percentText(prize.probabilityBp) }}</b>
        </div>
        <ElProgress
          :percentage="Math.min(100, bpToPercent(prize.probabilityBp))"
          :stroke-width="5"
          :show-text="false"
          color="var(--el-color-primary)"
        />
      </div>
    </div>
  </aside>
</template>

<script setup lang="ts">
  import type { EditableLotteryPrize } from '@/api/marketing'

  const props = defineProps<{
    prizes: EditableLotteryPrize[]
    enabled: boolean
    dailyBudgetAmount: number | null
  }>()

  const bpToPercent = (value?: number | null) => Number((Number(value || 0) / 100).toFixed(2))
  const percentText = (value?: number | null) => `${bpToPercent(value).toFixed(2)}%`
  const money = (value?: number | null) => `￥${(Number(value || 0) / 100).toFixed(2)}`

  const totalBp = computed(() => props.prizes.reduce((sum, prize) => sum + Number(prize.probabilityBp || 0), 0))
  const sumIsValid = computed(() => totalBp.value === 10000)
  const sumText = computed(() => `${(totalBp.value / 100).toFixed(2)}%`)
  const sumCaption = computed(() => {
    if (sumIsValid.value) return '总和正好 100.00%，可以保存。'
    const delta = ((10000 - totalBp.value) / 100).toFixed(2)
    return Number(delta) > 0 ? `还差 ${delta}%` : `超出 ${Math.abs(Number(delta)).toFixed(2)}%`
  })

  const ruleSummary = (prize: EditableLotteryPrize) => {
    if (prize.prizeCode === 'NONE' || prize.discountMode === 'NONE') return '无减免'
    if (prize.discountMode === 'THRESHOLD') {
      return `满 ${money(prize.thresholdAmount)} 减 ${money(prize.fixedDiscountAmount)}`
    }
    return `${percentText(prize.discountRateBp)}，最高减 ${money(prize.maxDiscountAmount)}`
  }
</script>

<style scoped lang="scss">
  .probability-preview {
    display: grid;
    gap: 16px;
    padding: 18px;
    background: var(--el-bg-color);
    border: 1px solid var(--art-border-color);
    border-radius: 16px;
  }

  .preview-head {
    display: flex;
    justify-content: space-between;
    align-items: center;
  }

  .preview-head__signal {
    display: flex;
    align-items: center;
    gap: 6px;
    color: var(--el-text-color-secondary);
    font-size: 12px;

    span {
      width: 8px;
      height: 8px;
      border-radius: 50%;
      background: var(--el-text-color-disabled);
    }

    &.is-live span {
      background: var(--el-color-success);
    }
  }

  .preview-title {
    span {
      color: var(--el-color-primary);
      font-size: 11px;
      font-weight: 800;
      letter-spacing: 0.12em;
    }

    h2 {
      margin: 4px 0;
      font-size: 20px;
    }

    p {
      color: var(--el-text-color-secondary);
      font-size: 12px;
      line-height: 1.6;
    }
  }

  .budget-strip {
    display: flex;
    gap: 10px;
    padding: 12px;
    background: var(--el-fill-color-light);
    border-radius: 12px;

    &.has-risk {
      background: var(--el-color-danger-light-9);
    }

    strong.is-bad {
      color: var(--el-color-danger);
    }

    small {
      display: block;
      color: var(--el-text-color-secondary);
    }
  }

  .odds-list {
    display: grid;
    gap: 12px;
  }

  .odds-row__main {
    display: flex;
    justify-content: space-between;
    gap: 12px;
    margin-bottom: 6px;

    span {
      display: block;
      color: var(--el-text-color-secondary);
      font-size: 12px;
    }
  }
</style>
