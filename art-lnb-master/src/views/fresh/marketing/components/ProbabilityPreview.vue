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
      <h2>实时概率预览</h2>
      <p>每个金额阶梯独立计算；禁用、零权重、库存耗尽、超预算和商品不可售项自动退出分母。</p>
    </div>

    <div class="budget-strip" :class="{ 'has-risk': budgetRisk }">
      <ArtSvgIcon :icon="budgetRisk ? 'ri:alarm-warning-line' : 'ri:safe-2-line'" />
      <div>
        <span>每日现金预算</span>
        <strong>{{ budgetText }}</strong>
        <small>{{ budgetCaption }}</small>
      </div>
    </div>

    <div v-if="previewTiers.length" class="tier-preview-list">
      <section
        v-for="(tier, tierIndex) in previewTiers"
        :key="tierKey(tier)"
        class="tier-preview"
        :class="{ 'is-disabled': !tier.enabled }"
      >
        <header>
          <div>
            <span>阶梯 {{ tierIndex + 1 }}</span>
            <strong>{{ tier.name || '未命名阶梯' }}</strong>
          </div>
          <ElTag :type="tier.enabled ? 'success' : 'info'" size="small" effect="plain">
            {{ tier.enabled ? rangeText(tier) : '已停用' }}
          </ElTag>
        </header>

        <div class="tier-preview__meta">
          <span>有效奖项 {{ activePrizeCount(tier) }} 项</span>
          <span>总权重 {{ denominator(tier) }}</span>
          <span>期望减免 {{ money(expectedDiscount(tier)) }}/次</span>
        </div>

        <div v-if="tier.prizes.length" class="odds-list">
          <div
            v-for="prize in sortedPrizes(tier)"
            :key="prizeKey(prize)"
            class="odds-row"
            :class="{ 'is-excluded': isExcluded(prize) || !tier.enabled }"
          >
            <div class="odds-row__main">
              <span class="odds-row__icon" :class="`type-${prize.type.toLowerCase()}`">
                <ArtSvgIcon :icon="prizeIcon(prize.type)" />
              </span>
              <div>
                <strong>{{ prize.name || prizeTypeLabel(prize.type) }}</strong>
                <span>{{ prizeDetail(prize) }}</span>
              </div>
              <b>{{ probabilityText(tier, prize) }}</b>
            </div>
            <ElProgress
              :percentage="tier.enabled ? probability(tier, prize) : 0"
              :stroke-width="5"
              :show-text="false"
              color="var(--el-color-primary)"
            />
          </div>
        </div>
        <div v-else class="tier-preview__empty">尚未添加奖项</div>

        <ElAlert
          v-if="tier.enabled && denominator(tier) === 0"
          title="当前没有可参与抽取的奖项"
          type="warning"
          :closable="false"
          show-icon
        />
      </section>
    </div>

    <div v-else class="preview-empty">
      <ArtSvgIcon icon="ri:stack-line" />
      <strong>添加金额阶梯后查看概率</strong>
      <span>预览会随着权重、库存和启停状态实时更新。</span>
    </div>

    <footer class="preview-foot">
      <ArtSvgIcon icon="ri:braces-line" />
      <span>区间规则：productAmount ∈ [min, max)；赠品可售性以抽奖时服务端校验为准</span>
    </footer>
  </aside>
</template>

<script setup lang="ts">
  import type { Product } from '@/api/admin'
  import {
    getLotteryGiftProduct,
    type EditableLotteryPrize,
    type EditableLotteryTier,
    type LotteryPrizeType
  } from '@/api/marketing'

  const props = defineProps<{
    tiers: EditableLotteryTier[]
    enabled: boolean
    dailyBudgetAmount: number | null
  }>()

  const previewTiers = computed(() =>
    [...props.tiers].sort(
      (left, right) =>
        Number(left.sortOrder || 0) - Number(right.sortOrder || 0) ||
        Number(left.minProductAmount || 0) - Number(right.minProductAmount || 0)
    )
  )

  const money = (value?: number | null) => `￥${(Number(value || 0) / 100).toFixed(2)}`
  const isExhausted = (prize: EditableLotteryPrize) =>
    prize.type === 'GOODS' && Number(prize.stockRemaining || 0) <= 0

  /**
   * 与后端 eligiblePrizes 对齐：单笔减免超过每日预算的现金奖项永远无法被抽中。
   */
  const isOverBudget = (prize: EditableLotteryPrize) =>
    prize.type === 'DISCOUNT' &&
    props.dailyBudgetAmount !== null &&
    Number(prize.discountAmount || 0) > Number(props.dailyBudgetAmount)

  const giftProducts = ref(new Map<number, Product | null>())
  const inflightProducts = new Set<number>()

  const giftBindings = computed(() =>
    props.tiers.flatMap((tier) =>
      tier.prizes
        .filter((prize) => prize.type === 'GOODS' && prize.enabled && prize.productId)
        .map((prize) => Number(prize.productId))
    )
  )

  /**
   * 与后端 canReserveLotteryGift 对齐：商品下架、SKU 停用或实际库存不足时赠品不进奖池。
   * 返回空串表示暂时无法判定（尚未拉到商品），此时不改变分母。
   */
  const giftIssue = (prize: EditableLotteryPrize) => {
    if (prize.type !== 'GOODS') return ''
    if (!prize.productId) return '未绑定商品'
    const productId = Number(prize.productId)
    if (!giftProducts.value.has(productId)) return ''
    const product = giftProducts.value.get(productId)
    if (!product) return '关联商品不存在或读取失败'
    if (Number(product.status) !== 1) return '关联商品已下架'
    if (product.skuEnabled) {
      if (!prize.skuId) return '多规格商品必须绑定 SKU'
      const sku = (product.skus || []).find((item) => Number(item.id) === Number(prize.skuId))
      if (!sku) return '绑定的 SKU 不属于该商品'
      if (Number(sku.status) !== 1) return '绑定的 SKU 已停用'
      if (Number(sku.stockQty || 0) < 1) return '绑定 SKU 商品库存不足'
      return ''
    }
    if (prize.skuId) return '单规格商品不能绑定 SKU'
    if (Number(product.stockQty || 0) < 1) return '关联商品库存不足'
    return ''
  }

  const loadGiftProducts = async () => {
    const pendingIds = [...new Set(giftBindings.value)].filter(
      (id) => !giftProducts.value.has(id) && !inflightProducts.has(id)
    )
    if (pendingIds.length === 0) return
    pendingIds.forEach((id) => inflightProducts.add(id))
    await Promise.all(
      pendingIds.map(async (id) => {
        try {
          giftProducts.value.set(id, (await getLotteryGiftProduct(id)) || null)
        } catch {
          giftProducts.value.set(id, null)
        } finally {
          inflightProducts.delete(id)
        }
      })
    )
  }

  watch(() => [...new Set(giftBindings.value)].join(','), loadGiftProducts, { immediate: true })

  const isExcluded = (prize: EditableLotteryPrize) =>
    !prize.enabled ||
    isExhausted(prize) ||
    !Number.isInteger(prize.weight) ||
    prize.weight <= 0 ||
    isOverBudget(prize) ||
    Boolean(giftIssue(prize))

  const sortedPrizes = (tier: EditableLotteryTier) =>
    [...tier.prizes].sort(
      (left, right) => Number(left.sortOrder || 0) - Number(right.sortOrder || 0)
    )
  const denominator = (tier: EditableLotteryTier) =>
    tier.prizes.reduce((sum, prize) => sum + (isExcluded(prize) ? 0 : Math.max(0, prize.weight)), 0)
  const activePrizeCount = (tier: EditableLotteryTier) =>
    tier.prizes.filter((prize) => !isExcluded(prize)).length
  const probability = (tier: EditableLotteryTier, prize: EditableLotteryPrize) => {
    const total = denominator(tier)
    if (!tier.enabled || total <= 0 || isExcluded(prize)) return 0
    return Number(((prize.weight / total) * 100).toFixed(2))
  }
  const probabilityText = (tier: EditableLotteryTier, prize: EditableLotteryPrize) =>
    `${probability(tier, prize).toFixed(2)}%`
  const expectedDiscount = (tier: EditableLotteryTier) => {
    const total = denominator(tier)
    if (total <= 0) return 0
    return Math.round(
      tier.prizes.reduce((sum, prize) => {
        if (isExcluded(prize) || prize.type !== 'DISCOUNT') return sum
        return sum + (prize.discountAmount * prize.weight) / total
      }, 0)
    )
  }

  const maxDiscount = computed(() =>
    props.tiers.reduce(
      (maximum, tier) =>
        tier.enabled
          ? Math.max(
              maximum,
              ...tier.prizes
                .filter((prize) => prize.enabled && prize.type === 'DISCOUNT')
                .map((prize) => Number(prize.discountAmount || 0))
            )
          : maximum,
      0
    )
  )
  const budgetRisk = computed(
    () =>
      props.enabled &&
      props.dailyBudgetAmount !== null &&
      maxDiscount.value > 0 &&
      props.dailyBudgetAmount < maxDiscount.value
  )
  const budgetText = computed(() =>
    props.dailyBudgetAmount === null ? '不限预算' : money(props.dailyBudgetAmount)
  )
  const budgetCaption = computed(() => {
    if (props.dailyBudgetAmount === null) return '现金奖项不会因为当日累计减免退出奖池'
    if (maxDiscount.value <= 0) return '当前奖池没有现金减免'
    if (props.dailyBudgetAmount <= 0) return '启用现金减免前需设置预算'
    if (budgetRisk.value) return `低于最高单笔减免 ${money(maxDiscount.value)}`
    return `至少可覆盖 ${Math.floor(props.dailyBudgetAmount / maxDiscount.value)} 次最高额减免`
  })

  const rangeText = (tier: EditableLotteryTier) =>
    `[${money(tier.minProductAmount)}, ${
      tier.maxProductAmount === null ? '+∞' : money(tier.maxProductAmount)
    })`
  const prizeTypeLabel = (type: LotteryPrizeType) =>
    ({ DISCOUNT: '现金减免', GOODS: '实物赠品', NONE: '谢谢惠顾' })[type]
  const prizeIcon = (type: LotteryPrizeType) =>
    ({
      DISCOUNT: 'ri:coupon-3-line',
      GOODS: 'ri:gift-line',
      NONE: 'ri:emotion-normal-line'
    })[type]
  const prizeDetail = (prize: EditableLotteryPrize) => {
    if (!prize.enabled) return '已停用'
    if (isExhausted(prize)) return '库存耗尽'
    if (prize.weight <= 0) return '权重为 0'
    if (isOverBudget(prize)) return `超出每日预算 ${budgetText.value}，抽奖时会退出奖池`
    const issue = giftIssue(prize)
    if (issue) return `${issue}，抽奖时会退出奖池`
    if (prize.type === 'DISCOUNT') return `减免 ${money(prize.discountAmount)}`
    if (prize.type === 'GOODS') {
      const stock = `剩余 ${prize.stockRemaining || 0}`
      return `${stock}${prize.skuId ? ` · SKU ${prize.skuId}` : ''}`
    }
    return `权重 ${prize.weight}`
  }

  const tierKey = (tier: EditableLotteryTier) =>
    tier.id === null ? `tier-preview-${tier.localId}` : `tier-preview-${tier.id}`
  const prizeKey = (prize: EditableLotteryPrize) =>
    prize.id === null ? `prize-preview-${prize.localId}` : `prize-preview-${prize.id}`
</script>

<style scoped lang="scss">
  .probability-preview {
    position: relative;
    display: grid;
    gap: 16px;
    padding: 20px;
    overflow: hidden;
    border: 1px solid color-mix(in srgb, var(--el-color-primary) 22%, var(--el-border-color));
    border-radius: 14px;
    background:
      linear-gradient(
        90deg,
        transparent 23px,
        color-mix(in srgb, var(--el-color-primary) 7%, transparent) 24px,
        transparent 25px
      ),
      var(--el-bg-color);
    box-shadow: 0 16px 36px color-mix(in srgb, var(--el-color-primary) 8%, transparent);
  }

  .preview-head {
    display: flex;
    align-items: center;
    justify-content: space-between;

    > svg {
      color: var(--el-color-primary);
      font-size: 24px;
    }
  }

  .preview-head__signal {
    display: inline-flex;
    align-items: center;
    gap: 7px;
    color: var(--el-text-color-secondary);
    font-size: 11px;
    font-weight: 700;
    letter-spacing: 0.08em;

    > span {
      width: 8px;
      height: 8px;
      border-radius: 50%;
      background: var(--el-text-color-disabled);
    }

    &.is-live {
      color: var(--el-color-primary);

      > span {
        background: var(--el-color-primary);
        box-shadow: 0 0 0 5px color-mix(in srgb, var(--el-color-primary) 14%, transparent);
      }
    }
  }

  .preview-title {
    display: grid;
    gap: 5px;

    > span {
      color: var(--el-color-primary);
      font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
      font-size: 10px;
      font-weight: 800;
      letter-spacing: 0.16em;
    }

    h2,
    p {
      margin: 0;
    }

    h2 {
      color: var(--el-text-color-primary);
      font-size: 21px;
      font-weight: 800;
      letter-spacing: -0.02em;
    }

    p {
      color: var(--el-text-color-secondary);
      font-size: 12px;
      line-height: 1.65;
    }
  }

  .budget-strip {
    display: flex;
    align-items: center;
    gap: 11px;
    padding: 12px;
    border: 1px solid var(--el-color-primary-light-7);
    border-radius: 11px;
    background: var(--el-color-primary-light-9);

    > svg {
      flex: none;
      color: var(--el-color-primary);
      font-size: 22px;
    }

    > div {
      display: grid;
      min-width: 0;
      grid-template-columns: 1fr auto;
      flex: 1;
      align-items: center;
      gap: 2px 8px;
    }

    span,
    small {
      color: var(--el-text-color-secondary);
      font-size: 10px;
    }

    strong {
      color: var(--el-color-primary);
      font-size: 15px;
      font-variant-numeric: tabular-nums;
    }

    small {
      grid-column: 1 / -1;
    }

    &.has-risk {
      border-color: var(--el-color-warning-light-5);
      background: var(--el-color-warning-light-9);

      > svg,
      strong {
        color: var(--el-color-warning);
      }
    }
  }

  .tier-preview-list {
    display: grid;
    gap: 12px;
  }

  .tier-preview {
    display: grid;
    gap: 10px;
    padding: 13px;
    border: 1px solid var(--el-border-color-lighter);
    border-radius: 11px;
    background: color-mix(in srgb, var(--el-fill-color-lighter) 74%, transparent);

    &.is-disabled {
      opacity: 0.62;
    }

    > header {
      display: flex;
      align-items: flex-start;
      justify-content: space-between;
      gap: 8px;

      > div {
        display: grid;
        min-width: 0;
        gap: 2px;

        span {
          color: var(--el-text-color-secondary);
          font-size: 9px;
          font-weight: 700;
          letter-spacing: 0.1em;
          text-transform: uppercase;
        }

        strong {
          overflow: hidden;
          color: var(--el-text-color-primary);
          font-size: 14px;
          text-overflow: ellipsis;
          white-space: nowrap;
        }
      }
    }
  }

  .tier-preview__meta {
    display: flex;
    flex-wrap: wrap;
    gap: 5px 10px;
    padding-bottom: 9px;
    border-bottom: 1px dashed var(--el-border-color);
    color: var(--el-text-color-secondary);
    font-size: 10px;
  }

  .odds-list {
    display: grid;
    gap: 9px;
  }

  .odds-row {
    display: grid;
    gap: 5px;

    &.is-excluded {
      opacity: 0.48;
    }
  }

  .odds-row__main {
    display: grid;
    grid-template-columns: 27px minmax(0, 1fr) auto;
    align-items: center;
    gap: 7px;

    > div {
      display: grid;
      min-width: 0;
      gap: 1px;

      strong,
      span {
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }

      strong {
        color: var(--el-text-color-primary);
        font-size: 11px;
      }

      span {
        color: var(--el-text-color-secondary);
        font-size: 9px;
      }
    }

    > b {
      color: var(--el-color-primary);
      font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
      font-size: 11px;
      font-variant-numeric: tabular-nums;
    }
  }

  .odds-row__icon {
    display: grid;
    width: 27px;
    height: 27px;
    place-items: center;
    border-radius: 8px;
    color: var(--el-color-primary);
    background: var(--el-color-primary-light-9);

    &.type-none {
      color: var(--el-text-color-secondary);
      background: var(--el-fill-color);
    }
  }

  .tier-preview__empty,
  .preview-empty {
    color: var(--el-text-color-secondary);
    font-size: 11px;
    text-align: center;
  }

  .preview-empty {
    display: grid;
    justify-items: center;
    gap: 6px;
    padding: 26px 14px;
    border: 1px dashed var(--el-border-color);
    border-radius: 11px;

    > svg {
      color: var(--el-color-primary);
      font-size: 28px;
    }

    strong {
      color: var(--el-text-color-primary);
      font-size: 13px;
    }
  }

  .preview-foot {
    display: flex;
    align-items: center;
    gap: 7px;
    padding-top: 4px;
    color: var(--el-text-color-secondary);
    font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
    font-size: 9px;
  }
</style>
