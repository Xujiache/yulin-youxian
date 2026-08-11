<template>
  <div class="fresh-page lucky-draw-page">
    <div class="fresh-page__head lucky-head">
      <div>
        <div class="lucky-head__eyebrow">
          <span></span>
          MARKETING / LUCKY DRAW
        </div>
        <h1 class="fresh-page__title">随机减免</h1>
        <p class="fresh-page__desc">
          按商品金额进入不同奖池，以整数权重分配现金减免、实物赠品与谢谢惠顾。
        </p>
      </div>
      <div class="lucky-head__actions">
        <ElButton @click="goRecords">
          <ArtSvgIcon icon="ri:file-list-3-line" />
          中奖记录
        </ElButton>
        <ElButton :loading="loading" @click="loadCampaign">
          <ArtSvgIcon icon="ri:refresh-line" />
          重新加载
        </ElButton>
        <ElButton type="primary" :loading="saving" @click="saveCampaign">
          <ArtSvgIcon icon="ri:save-3-line" />
          保存整体配置
        </ElButton>
      </div>
    </div>

    <div v-loading="loading" class="lucky-layout">
      <main class="lucky-main">
        <ElCard class="fresh-card campaign-status-card" shadow="never">
          <div class="campaign-status">
            <div class="campaign-status__icon" :class="{ 'is-enabled': form.enabled }">
              <ArtSvgIcon icon="ri:magic-line" />
            </div>
            <div class="campaign-status__copy">
              <span>单活动总开关</span>
              <strong>{{ form.enabled ? '随机减免已开启' : '当前为关闭状态' }}</strong>
              <p>{{ statusCaption }}</p>
            </div>
            <ElSwitch
              v-model="form.enabled"
              size="large"
              active-text="开启活动"
              inactive-text="关闭活动"
            />
          </div>
        </ElCard>

        <ElCard class="fresh-card config-card" shadow="never">
          <template #header>
            <div class="section-head">
              <div class="section-head__marker"><ArtSvgIcon icon="ri:calendar-event-line" /></div>
              <div>
                <h2>活动规则</h2>
                <p>控制活动名称、有效时段、单用户每日次数和现金预算上限。</p>
              </div>
            </div>
          </template>

          <ElForm label-position="top">
            <div class="form-grid">
              <ElFormItem label="活动名称" required class="form-grid__wide">
                <ElInput
                  v-model.trim="form.name"
                  maxlength="40"
                  show-word-limit
                  placeholder="例如：每日下单随机减"
                />
              </ElFormItem>
              <ElFormItem label="活动时段" required class="form-grid__wide">
                <ElDatePicker
                  v-model="campaignRange"
                  type="datetimerange"
                  value-format="YYYY-MM-DDTHH:mm:ss"
                  start-placeholder="开始时间"
                  end-placeholder="结束时间"
                  range-separator="至"
                  class="form-full"
                />
                <span class="field-tip"
                  >结束时间必须晚于开始时间；关闭活动时可保存未排期草稿。</span
                >
              </ElFormItem>
              <ElFormItem label="每位用户每日抽取次数" required>
                <ElInputNumber
                  v-model="form.dailyUserLimit"
                  :min="1"
                  :max="999"
                  :precision="0"
                  :step="1"
                  controls-position="right"
                  class="form-full"
                />
              </ElFormItem>
              <ElFormItem label="每日现金预算（元）" required>
                <ElInputNumber
                  :model-value="centToYuan(form.dailyBudgetAmount)"
                  :min="0"
                  :max="99999999"
                  :precision="2"
                  :step="100"
                  controls-position="right"
                  class="form-full"
                  @update:model-value="updateDailyBudget"
                />
                <span class="field-tip">预算与减免金额均按“分”提交；赠品库存不占现金预算。</span>
              </ElFormItem>
            </div>
          </ElForm>
        </ElCard>

        <ElCard class="fresh-card config-card" shadow="never">
          <template #header>
            <div class="section-head">
              <div class="section-head__marker"><ArtSvgIcon icon="ri:share-forward-line" /></div>
              <div>
                <h2>分享文案</h2>
                <p>用于小程序分享卡片，图片可填写完整 URL 或现有 /uploads、/assets 地址。</p>
              </div>
            </div>
          </template>

          <div class="share-editor">
            <ElForm label-position="top">
              <div class="form-grid">
                <ElFormItem label="分享标题" class="form-grid__wide">
                  <ElInput
                    v-model.trim="form.shareTitle"
                    maxlength="40"
                    show-word-limit
                    placeholder="下单后还有一份随机惊喜"
                  />
                </ElFormItem>
                <ElFormItem label="分享描述" class="form-grid__wide">
                  <ElInput
                    v-model.trim="form.shareDescription"
                    type="textarea"
                    :rows="3"
                    maxlength="100"
                    show-word-limit
                    resize="none"
                    placeholder="告诉顾客活动规则与福利内容"
                  />
                </ElFormItem>
                <ElFormItem label="分享图片地址" class="form-grid__wide">
                  <ElInput
                    v-model.trim="form.shareImageUrl"
                    clearable
                    placeholder="https://... 或 /uploads/..."
                  >
                    <template #prefix><ArtSvgIcon icon="ri:image-line" /></template>
                  </ElInput>
                </ElFormItem>
              </div>
            </ElForm>

            <div class="share-preview">
              <ElImage
                v-if="form.shareImageUrl"
                :src="assetUrl(form.shareImageUrl)"
                fit="cover"
                class="share-preview__image"
              >
                <template #error>
                  <div class="share-preview__fallback">
                    <ArtSvgIcon icon="ri:image-off-line" />
                    图片无法预览
                  </div>
                </template>
              </ElImage>
              <div v-else class="share-preview__image share-preview__fallback">
                <ArtSvgIcon icon="ri:leaf-line" />
                待配置分享图片
              </div>
              <div>
                <strong>{{ form.shareTitle || '随机减免活动' }}</strong>
                <span>{{ form.shareDescription || '分享描述会显示在这里。' }}</span>
                <small>禹邻优鲜</small>
              </div>
            </div>
          </div>
        </ElCard>

        <ElCard class="fresh-card config-card tier-config-card" shadow="never">
          <template #header>
            <div class="tier-section-head">
              <div class="section-head">
                <div class="section-head__marker"><ArtSvgIcon icon="ri:stack-line" /></div>
                <div>
                  <h2>金额阶梯与奖项</h2>
                  <p>按 productAmount 匹配左闭右开区间 [min, max)，max 留空表示无上限。</p>
                </div>
              </div>
              <ElButton type="primary" plain @click="addTier">
                <ArtSvgIcon icon="ri:add-line" />
                新增金额阶梯
              </ElButton>
            </div>
          </template>

          <ElAlert
            v-if="overlapPairs.length"
            :title="overlapPairs[0]"
            :description="
              overlapPairs.length > 1
                ? `另有 ${overlapPairs.length - 1} 处区间冲突，请一并调整。`
                : ''
            "
            type="error"
            :closable="false"
            show-icon
            class="overlap-alert"
          />

          <div v-if="form.tiers.length" class="tier-list">
            <section
              v-for="(tier, tierIndex) in form.tiers"
              :key="tierKey(tier)"
              class="tier-editor"
              :class="{
                'is-disabled': !tier.enabled,
                'has-overlap': overlappingTiers.has(tier)
              }"
            >
              <header class="tier-editor__head">
                <div class="tier-editor__identity">
                  <span>{{ String(tierIndex + 1).padStart(2, '0') }}</span>
                  <div>
                    <strong>{{ tier.name || `阶梯 ${tierIndex + 1}` }}</strong>
                    <small>{{ tierRangeText(tier) }}</small>
                  </div>
                </div>
                <div class="tier-editor__actions">
                  <ElTag
                    v-if="overlappingTiers.has(tier)"
                    type="danger"
                    size="small"
                    effect="light"
                  >
                    区间重叠
                  </ElTag>
                  <ElSwitch v-model="tier.enabled" active-text="启用" inactive-text="停用" />
                  <ElButton
                    text
                    :disabled="tierIndex === 0"
                    aria-label="阶梯上移"
                    @click="moveTier(tierIndex, -1)"
                  >
                    <ArtSvgIcon icon="ri:arrow-up-line" />
                  </ElButton>
                  <ElButton
                    text
                    :disabled="tierIndex === form.tiers.length - 1"
                    aria-label="阶梯下移"
                    @click="moveTier(tierIndex, 1)"
                  >
                    <ArtSvgIcon icon="ri:arrow-down-line" />
                  </ElButton>
                  <ElButton link type="danger" @click="removeTier(tier, tierIndex)"
                    >删除阶梯</ElButton
                  >
                </div>
              </header>

              <div class="tier-rule-grid">
                <ElFormItem label="阶梯名称" required>
                  <ElInput
                    v-model.trim="tier.name"
                    maxlength="24"
                    placeholder="例如：满 50 元奖池"
                  />
                </ElFormItem>
                <ElFormItem label="最低商品金额（元）" required>
                  <ElInputNumber
                    :model-value="centToYuan(tier.minProductAmount)"
                    :min="0"
                    :precision="2"
                    :step="10"
                    controls-position="right"
                    class="form-full"
                    @update:model-value="(value) => updateTierMin(tier, value)"
                  />
                </ElFormItem>
                <ElFormItem label="最高商品金额（元）">
                  <div class="tier-max-editor">
                    <ElInputNumber
                      v-if="tier.maxProductAmount !== null"
                      :model-value="centToYuan(tier.maxProductAmount)"
                      :min="0"
                      :precision="2"
                      :step="10"
                      controls-position="right"
                      @update:model-value="(value) => updateTierMax(tier, value)"
                    />
                    <div v-else class="tier-max-editor__infinity">+∞ 无上限</div>
                    <ElSwitch
                      :model-value="tier.maxProductAmount === null"
                      inline-prompt
                      active-text="∞"
                      inactive-text="限"
                      @change="(value) => toggleTierMax(tier, Boolean(value))"
                    />
                  </div>
                </ElFormItem>
                <ElFormItem label="匹配规则">
                  <div class="range-code">
                    <ArtSvgIcon icon="ri:braces-line" />
                    productAmount ∈ {{ tierRangeText(tier) }}
                  </div>
                </ElFormItem>
              </div>

              <PrizePoolTable v-model="tier.prizes" />
            </section>
          </div>

          <div v-else class="tier-empty">
            <ArtSvgIcon icon="ri:stack-line" />
            <div>
              <strong>还没有金额阶梯</strong>
              <span>新增阶梯并配置奖项后，系统才能根据订单商品金额选择奖池。</span>
            </div>
            <ElButton type="primary" plain @click="addTier">新增第一个阶梯</ElButton>
          </div>
        </ElCard>
      </main>

      <div class="preview-rail">
        <ProbabilityPreview
          :tiers="form.tiers"
          :enabled="form.enabled"
          :daily-budget-amount="form.dailyBudgetAmount"
        />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
  import { ElMessage, ElMessageBox } from 'element-plus'
  import {
    getLotteryCampaign,
    updateLotteryCampaign,
    type EditableLotteryCampaign,
    type EditableLotteryPrize,
    type EditableLotteryTier,
    type LotteryCampaign,
    type LotteryId,
    type LotteryPrize,
    type LotteryPrizeType,
    type LotteryTier
  } from '@/api/marketing'
  import { resolveFreshAssetUrl } from '@/utils/fresh-assets'
  import PrizePoolTable from '../components/PrizePoolTable.vue'
  import ProbabilityPreview from '../components/ProbabilityPreview.vue'

  defineOptions({ name: 'FreshLuckyDraw' })

  const router = useRouter()
  const loading = ref(false)
  const saving = ref(false)
  let tierKeySequence = 0
  const tierKeys = new WeakMap<EditableLotteryTier, string>()

  const safeNumber = (value: unknown, fallback = 0) => {
    const parsed = Number(value)
    return Number.isFinite(parsed) ? parsed : fallback
  }
  const safeInteger = (value: unknown, fallback = 0) =>
    Math.max(0, Math.round(safeNumber(value, fallback)))
  const normalizeId = (value: unknown): LotteryId | null =>
    typeof value === 'number' || typeof value === 'string' ? value : null
  const normalizePrizeType = (value: unknown): LotteryPrizeType =>
    value === 'DISCOUNT' || value === 'GOODS' || value === 'NONE' ? value : 'NONE'

  const createDefaultPrizes = (): EditableLotteryPrize[] => [
    {
      id: null,
      type: 'DISCOUNT',
      name: '随机减免',
      discountAmount: 100,
      productId: null,
      skuId: null,
      imageUrl: '',
      weight: 10,
      stockTotal: null,
      stockRemaining: null,
      enabled: true,
      sortOrder: 10
    },
    {
      id: null,
      type: 'NONE',
      name: '谢谢惠顾',
      discountAmount: 0,
      productId: null,
      skuId: null,
      imageUrl: '',
      weight: 90,
      stockTotal: null,
      stockRemaining: null,
      enabled: true,
      sortOrder: 20
    }
  ]

  const createDefaultTier = (): EditableLotteryTier => ({
    id: null,
    name: '全部订单',
    minProductAmount: 0,
    maxProductAmount: null,
    enabled: true,
    sortOrder: 10,
    prizes: createDefaultPrizes()
  })

  const createDefaultCampaign = (): EditableLotteryCampaign => ({
    id: null,
    enabled: false,
    name: '随机减免活动',
    startAt: '',
    endAt: '',
    dailyUserLimit: 1,
    dailyBudgetAmount: 10000,
    shareTitle: '',
    shareDescription: '',
    shareImageUrl: '',
    tiers: [createDefaultTier()]
  })

  const form = reactive<EditableLotteryCampaign>(createDefaultCampaign())

  const normalizeStock = (value: unknown) => {
    if (value === null || value === undefined || value === '') return null
    return safeInteger(value)
  }

  const normalizePrize = (
    prize: LotteryPrize | null | undefined,
    index: number
  ): EditableLotteryPrize => {
    const type = normalizePrizeType(prize?.type)
    const stockTotal = type === 'GOODS' ? normalizeStock(prize?.stockTotal) : null
    const stockRemaining = type === 'GOODS' ? normalizeStock(prize?.stockRemaining) : null
    const hasFiniteStock = type === 'GOODS'
    const normalizedTotal = hasFiniteStock ? (stockTotal ?? stockRemaining ?? 0) : null
    const normalizedRemaining = hasFiniteStock ? (stockRemaining ?? stockTotal ?? 0) : null
    return {
      id: normalizeId(prize?.id),
      type,
      name:
        String(prize?.name || '').trim() ||
        (type === 'DISCOUNT' ? '随机减免' : type === 'GOODS' ? '实物赠品' : '谢谢惠顾'),
      discountAmount: safeInteger(prize?.discountAmount),
      productId:
        prize?.productId === null || prize?.productId === undefined
          ? null
          : safeInteger(prize.productId),
      skuId: prize?.skuId === null || prize?.skuId === undefined ? null : safeInteger(prize.skuId),
      imageUrl: String(prize?.imageUrl || ''),
      weight: safeInteger(prize?.weight),
      stockTotal: normalizedTotal,
      stockRemaining: normalizedRemaining,
      enabled: prize?.enabled !== false,
      sortOrder: safeInteger(prize?.sortOrder, (index + 1) * 10)
    }
  }

  const normalizeTier = (
    tier: LotteryTier | null | undefined,
    index: number
  ): EditableLotteryTier => ({
    id: normalizeId(tier?.id),
    name: String(tier?.name || '').trim() || `金额阶梯 ${index + 1}`,
    minProductAmount: safeInteger(tier?.minProductAmount),
    maxProductAmount:
      tier?.maxProductAmount === null || tier?.maxProductAmount === undefined
        ? null
        : safeInteger(tier.maxProductAmount),
    enabled: tier?.enabled !== false,
    sortOrder: safeInteger(tier?.sortOrder, (index + 1) * 10),
    prizes: (Array.isArray(tier?.prizes) ? tier.prizes : [])
      .map((prize, prizeIndex) => normalizePrize(prize, prizeIndex))
      .sort((left, right) => left.sortOrder - right.sortOrder)
  })

  const normalizeCampaign = (
    campaign: LotteryCampaign | null | undefined
  ): EditableLotteryCampaign => {
    if (!campaign || Object.keys(campaign).length === 0) return createDefaultCampaign()
    const tiers = (Array.isArray(campaign.tiers) ? campaign.tiers : [])
      .map((tier, index) => normalizeTier(tier, index))
      .sort((left, right) => left.sortOrder - right.sortOrder)
    return {
      id: normalizeId(campaign.id),
      enabled: campaign.enabled === true,
      name: String(campaign.name || '').trim() || '随机减免活动',
      startAt: String(campaign.startAt || ''),
      endAt: String(campaign.endAt || ''),
      dailyUserLimit: Math.max(1, safeInteger(campaign.dailyUserLimit, 1)),
      dailyBudgetAmount: safeInteger(campaign.dailyBudgetAmount),
      shareTitle: String(campaign.shareTitle || ''),
      shareDescription: String(campaign.shareDescription || ''),
      shareImageUrl: String(campaign.shareImageUrl || ''),
      tiers
    }
  }

  const campaignRange = computed<string[]>({
    get: () => (form.startAt && form.endAt ? [form.startAt, form.endAt] : []),
    set: (range) => {
      form.startAt = range?.[0] || ''
      form.endAt = range?.[1] || ''
    }
  })

  const centToYuan = (value?: number | null) => Number((Number(value || 0) / 100).toFixed(2))
  const yuanToCent = (value?: number | null) => Math.max(0, Math.round(Number(value || 0) * 100))
  const money = (value?: number | null) => `￥${centToYuan(value).toFixed(2)}`
  const assetUrl = resolveFreshAssetUrl

  const updateDailyBudget = (value?: number) => {
    form.dailyBudgetAmount = yuanToCent(value)
  }
  const updateTierMin = (tier: EditableLotteryTier, value?: number) => {
    tier.minProductAmount = yuanToCent(value)
  }
  const updateTierMax = (tier: EditableLotteryTier, value?: number) => {
    tier.maxProductAmount = value === undefined ? null : yuanToCent(value)
  }
  const toggleTierMax = (tier: EditableLotteryTier, unlimited: boolean) => {
    tier.maxProductAmount = unlimited
      ? null
      : Math.max(tier.minProductAmount + 100, tier.minProductAmount * 2 || 10000)
  }

  const tierRangeText = (tier: EditableLotteryTier) =>
    `[${money(tier.minProductAmount)}, ${
      tier.maxProductAmount === null ? '+∞' : money(tier.maxProductAmount)
    })`

  const overlapState = computed(() => {
    const pairs: string[] = []
    const tiers = form.tiers
      .filter((tier) => tier.enabled)
      .map((tier) => ({
        tier,
        min: Number(tier.minProductAmount || 0),
        max: tier.maxProductAmount === null ? Number.POSITIVE_INFINITY : tier.maxProductAmount
      }))
      .sort((left, right) => left.min - right.min || left.max - right.max)
    const affected = new Set<EditableLotteryTier>()

    for (let index = 1; index < tiers.length; index += 1) {
      const previous = tiers[index - 1]
      const current = tiers[index]
      if (current.min < previous.max) {
        affected.add(previous.tier)
        affected.add(current.tier)
        pairs.push(
          `「${previous.tier.name || '未命名阶梯'}」${tierRangeText(previous.tier)} 与「${
            current.tier.name || '未命名阶梯'
          }」${tierRangeText(current.tier)} 存在重叠`
        )
      }
    }
    return { pairs, affected }
  })

  const overlapPairs = computed(() => overlapState.value.pairs)
  const overlappingTiers = computed(() => overlapState.value.affected)

  const statusCaption = computed(() => {
    if (!form.enabled) return '配置可以继续编辑，顾客端不会触发抽奖。'
    if (!form.startAt || !form.endAt) return '活动已开启，但需要补全活动时段后才能保存。'
    const now = Date.now()
    const start = Date.parse(form.startAt)
    const end = Date.parse(form.endAt)
    if (Number.isFinite(start) && now < start)
      return `活动将在 ${form.startAt.replace('T', ' ')} 开始。`
    if (Number.isFinite(end) && now >= end) return '活动时段已结束，请调整结束时间或关闭活动。'
    return '当前处于活动时段内，保存后新规则整体生效。'
  })

  const tierKey = (tier: EditableLotteryTier) => {
    if (tier.id !== null) return `tier-${tier.id}`
    if (!tierKeys.has(tier)) {
      tierKeySequence += 1
      tierKeys.set(tier, `new-tier-${tierKeySequence}`)
    }
    return tierKeys.get(tier) as string
  }

  const addTier = () => {
    const ordered = [...form.tiers].sort(
      (left, right) => left.minProductAmount - right.minProductAmount
    )
    const last = ordered[ordered.length - 1]
    let minProductAmount = 0
    if (last) {
      minProductAmount =
        last.maxProductAmount === null ? last.minProductAmount + 10000 : last.maxProductAmount
      if (last.maxProductAmount === null) last.maxProductAmount = minProductAmount
    }
    form.tiers.push({
      id: null,
      name: minProductAmount > 0 ? `满 ${centToYuan(minProductAmount)} 元` : '全部订单',
      minProductAmount,
      maxProductAmount: null,
      enabled: true,
      sortOrder: (form.tiers.length + 1) * 10,
      prizes: createDefaultPrizes()
    })
  }

  const normalizedTierOrders = (tiers: EditableLotteryTier[]) =>
    tiers.map((tier, index) => ({ ...tier, sortOrder: (index + 1) * 10 }))

  const moveTier = (index: number, direction: -1 | 1) => {
    const target = index + direction
    if (target < 0 || target >= form.tiers.length) return
    const next = [...form.tiers]
    const [current] = next.splice(index, 1)
    next.splice(target, 0, current)
    form.tiers = normalizedTierOrders(next)
  }

  const removeTier = async (tier: EditableLotteryTier, index: number) => {
    try {
      await ElMessageBox.confirm(
        `确认删除「${tier.name || `阶梯 ${index + 1}`}」及其全部奖项？`,
        '删除金额阶梯',
        {
          type: 'warning',
          confirmButtonText: '删除',
          cancelButtonText: '保留'
        }
      )
      form.tiers = normalizedTierOrders(
        form.tiers.filter((_, currentIndex) => currentIndex !== index)
      )
    } catch (error) {
      if (error !== 'cancel' && error !== 'close') throw error
    }
  }

  const validateCampaign = () => {
    const errors: string[] = []
    if (!form.name.trim()) errors.push('请填写活动名称')
    if (!Number.isInteger(form.dailyUserLimit) || form.dailyUserLimit < 1) {
      errors.push('每日抽取次数必须是大于 0 的整数')
    }
    if (!Number.isInteger(form.dailyBudgetAmount) || form.dailyBudgetAmount < 0) {
      errors.push('每日现金预算必须是大于等于 0 的金额')
    }

    const hasOnlyOneDate = Boolean(form.startAt) !== Boolean(form.endAt)
    if (hasOnlyOneDate) errors.push('活动开始和结束时间必须同时填写')
    if (form.startAt && form.endAt) {
      const start = Date.parse(form.startAt)
      const end = Date.parse(form.endAt)
      if (!Number.isFinite(start) || !Number.isFinite(end) || end <= start) {
        errors.push('活动结束时间必须晚于开始时间')
      }
    }
    if (form.enabled && (!form.startAt || !form.endAt)) {
      errors.push('开启活动前请设置完整活动时段')
    }
    if (form.tiers.length === 0) errors.push('请至少添加一个金额阶梯')
    if (overlapPairs.value.length > 0) errors.push('启用中的金额阶梯不能重叠')

    let maxDiscount = 0
    form.tiers.forEach((tier, tierIndex) => {
      const tierLabel = tier.name.trim() || `阶梯 ${tierIndex + 1}`
      if (!tier.name.trim()) errors.push(`请填写${tierLabel}的名称`)
      if (!Number.isInteger(tier.minProductAmount) || tier.minProductAmount < 0) {
        errors.push(`「${tierLabel}」最低商品金额无效`)
      }
      if (
        tier.maxProductAmount !== null &&
        (!Number.isInteger(tier.maxProductAmount) || tier.maxProductAmount <= tier.minProductAmount)
      ) {
        errors.push(`「${tierLabel}」最高金额必须大于最低金额`)
      }
      if (tier.enabled && tier.prizes.length === 0) {
        errors.push(`「${tierLabel}」已启用，但没有奖项`)
      }

      let weightedPrizeCount = 0
      tier.prizes.forEach((prize, prizeIndex) => {
        const prizeLabel = prize.name.trim() || `奖项 ${prizeIndex + 1}`
        if (!Number.isInteger(prize.weight) || prize.weight < 0) {
          errors.push(`「${tierLabel} / ${prizeLabel}」权重必须是非负整数`)
        }
        if (prize.enabled && prize.weight <= 0) {
          errors.push(`「${tierLabel} / ${prizeLabel}」启用时权重必须大于 0`)
        }
        if (prize.enabled && prize.weight > 0) weightedPrizeCount += 1
        if (tier.enabled && prize.enabled && prize.type === 'DISCOUNT') {
          if (!Number.isInteger(prize.discountAmount) || prize.discountAmount <= 0) {
            errors.push(`「${tierLabel} / ${prizeLabel}」现金减免必须大于 0 元`)
          }
          maxDiscount = Math.max(maxDiscount, prize.discountAmount)
        }
        if (prize.enabled && prize.type === 'GOODS' && !prize.productId) {
          errors.push(`「${tierLabel} / ${prizeLabel}」尚未绑定商品`)
        }

        const hasTotal = prize.stockTotal !== null
        const hasRemaining = prize.stockRemaining !== null
        if (prize.type === 'GOODS' && (!hasTotal || !hasRemaining)) {
          errors.push(`「${tierLabel} / ${prizeLabel}」实物赠品必须设置营销库存`)
        } else if (prize.type === 'GOODS' && hasTotal && hasRemaining) {
          if (
            !Number.isInteger(prize.stockTotal) ||
            !Number.isInteger(prize.stockRemaining) ||
            Number(prize.stockTotal) < 0 ||
            Number(prize.stockRemaining) < 0
          ) {
            errors.push(`「${tierLabel} / ${prizeLabel}」库存必须是非负整数`)
          } else if (Number(prize.stockRemaining) > Number(prize.stockTotal)) {
            errors.push(`「${tierLabel} / ${prizeLabel}」剩余库存不能大于总库存`)
          }
        }
      })
      if (tier.enabled && weightedPrizeCount === 0) {
        errors.push(`「${tierLabel}」至少需要一个启用且权重大于 0 的奖项`)
      }
    })

    if (form.enabled && maxDiscount > 0 && form.dailyBudgetAmount < maxDiscount) {
      errors.push(`每日现金预算不能低于最高单笔减免 ${money(maxDiscount)}`)
    }
    return [...new Set(errors)]
  }

  const createPayload = (): EditableLotteryCampaign => ({
    id: form.id,
    enabled: Boolean(form.enabled),
    name: form.name.trim(),
    startAt: form.startAt,
    endAt: form.endAt,
    dailyUserLimit: Math.round(form.dailyUserLimit),
    dailyBudgetAmount: Math.round(form.dailyBudgetAmount),
    shareTitle: form.shareTitle.trim(),
    shareDescription: form.shareDescription.trim(),
    shareImageUrl: form.shareImageUrl.trim(),
    tiers: form.tiers.map((tier, tierIndex) => ({
      id: tier.id,
      name: tier.name.trim(),
      minProductAmount: Math.round(tier.minProductAmount),
      maxProductAmount: tier.maxProductAmount === null ? null : Math.round(tier.maxProductAmount),
      enabled: Boolean(tier.enabled),
      sortOrder: tier.sortOrder || (tierIndex + 1) * 10,
      prizes: tier.prizes.map((prize, prizeIndex) => ({
        id: prize.id,
        type: prize.type,
        name: prize.name.trim(),
        discountAmount: Math.round(prize.discountAmount),
        productId: prize.productId,
        skuId: prize.skuId,
        imageUrl: prize.imageUrl.trim(),
        weight: Math.round(prize.weight),
        stockTotal: prize.stockTotal === null ? null : Math.round(prize.stockTotal),
        stockRemaining: prize.stockRemaining === null ? null : Math.round(prize.stockRemaining),
        enabled: Boolean(prize.enabled),
        sortOrder: prize.sortOrder || (prizeIndex + 1) * 10
      }))
    }))
  })

  const loadCampaign = async () => {
    loading.value = true
    try {
      Object.assign(form, normalizeCampaign(await getLotteryCampaign()))
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '随机减免配置加载失败')
    } finally {
      loading.value = false
    }
  }

  const saveCampaign = async () => {
    const errors = validateCampaign()
    if (errors.length > 0) {
      ElMessage.warning(errors[0])
      if (errors.length > 1) {
        await ElMessageBox.alert(
          errors.map((item, index) => `${index + 1}. ${item}`).join('\n'),
          `保存前请处理 ${errors.length} 个问题`,
          { type: 'warning', confirmButtonText: '返回修改' }
        )
      }
      return
    }

    saving.value = true
    try {
      const payload = createPayload()
      const result = await updateLotteryCampaign(payload)
      Object.assign(
        form,
        result && Object.keys(result).length > 0 ? normalizeCampaign(result) : payload
      )
      ElMessage.success('随机减免整体配置已保存')
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '随机减免配置保存失败')
    } finally {
      saving.value = false
    }
  }

  const goRecords = () => {
    router.push({ name: 'FreshLuckyDrawRecords' })
  }

  onMounted(loadCampaign)
</script>

<style scoped lang="scss">
  @use '../../style.scss';

  .lucky-draw-page {
    --lucky-green: var(--el-color-primary);
    --lucky-green-soft: var(--el-color-primary-light-9);
    padding-bottom: 24px;
  }

  .lucky-head {
    align-items: flex-end;
  }

  .lucky-head__eyebrow {
    display: flex;
    align-items: center;
    gap: 7px;
    margin-bottom: 7px;
    color: var(--lucky-green);
    font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
    font-size: 10px;
    font-weight: 800;
    letter-spacing: 0.14em;

    > span {
      width: 17px;
      height: 2px;
      border-radius: 999px;
      background: var(--lucky-green);
    }
  }

  .lucky-head__actions {
    display: flex;
    flex-wrap: wrap;
    justify-content: flex-end;
    gap: 9px;

    :deep(.el-button + .el-button) {
      margin-left: 0;
    }
  }

  .lucky-layout {
    display: grid;
    grid-template-columns: minmax(0, 1fr) 340px;
    align-items: start;
    gap: 18px;
    min-height: 360px;
  }

  .lucky-main {
    display: grid;
    min-width: 0;
    gap: 16px;
  }

  .preview-rail {
    position: sticky;
    top: 16px;
    max-height: calc(100vh - 32px);
    overflow: auto;
    scrollbar-width: thin;
  }

  .campaign-status-card {
    border-color: color-mix(in srgb, var(--lucky-green) 24%, var(--art-border-color));
    background:
      radial-gradient(
        circle at 85% 0%,
        color-mix(in srgb, var(--lucky-green) 12%, transparent),
        transparent 36%
      ),
      var(--el-bg-color);

    :deep(.el-card__body) {
      padding: 18px 20px;
    }
  }

  .campaign-status {
    display: grid;
    grid-template-columns: 46px minmax(0, 1fr) auto;
    align-items: center;
    gap: 14px;
  }

  .campaign-status__icon {
    display: grid;
    width: 46px;
    height: 46px;
    place-items: center;
    border-radius: 14px;
    color: var(--el-text-color-secondary);
    font-size: 22px;
    background: var(--el-fill-color);

    &.is-enabled {
      color: var(--lucky-green);
      background: var(--lucky-green-soft);
      box-shadow: inset 0 0 0 1px var(--el-color-primary-light-7);
    }
  }

  .campaign-status__copy {
    display: grid;
    gap: 2px;

    span {
      color: var(--el-text-color-secondary);
      font-size: 10px;
      font-weight: 700;
      letter-spacing: 0.08em;
    }

    strong {
      color: var(--el-text-color-primary);
      font-size: 17px;
    }

    p {
      margin: 1px 0 0;
      color: var(--el-text-color-secondary);
      font-size: 12px;
    }
  }

  .config-card {
    :deep(.el-card__header) {
      padding: 17px 20px;
    }

    :deep(.el-card__body) {
      padding: 20px;
    }
  }

  .section-head {
    display: flex;
    align-items: center;
    gap: 11px;

    h2,
    p {
      margin: 0;
    }

    h2 {
      color: var(--el-text-color-primary);
      font-size: 16px;
      font-weight: 750;
    }

    p {
      margin-top: 3px;
      color: var(--el-text-color-secondary);
      font-size: 12px;
    }
  }

  .section-head__marker {
    display: grid;
    width: 35px;
    height: 35px;
    flex: none;
    place-items: center;
    border-radius: 11px;
    color: var(--lucky-green);
    font-size: 18px;
    background: var(--lucky-green-soft);
  }

  .form-grid {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 0 18px;

    &__wide {
      grid-column: 1 / -1;
    }
  }

  .field-tip {
    display: block;
    margin-top: 5px;
    color: var(--el-text-color-secondary);
    font-size: 11px;
    line-height: 1.5;
  }

  .share-editor {
    display: grid;
    grid-template-columns: minmax(0, 1fr) 250px;
    align-items: stretch;
    gap: 20px;
  }

  .share-preview {
    align-self: start;
    padding: 10px;
    border: 1px solid var(--el-border-color);
    border-radius: 12px;
    background: var(--el-fill-color-lighter);

    &__image {
      display: grid;
      width: 100%;
      height: 126px;
      overflow: hidden;
      place-items: center;
      border-radius: 9px;
      background: var(--el-color-primary-light-9);
    }

    &__fallback {
      gap: 5px;
      color: var(--el-color-primary);
      font-size: 11px;

      > svg {
        font-size: 25px;
      }
    }

    > div:last-child {
      display: grid;
      gap: 4px;
      padding: 10px 3px 2px;

      strong {
        color: var(--el-text-color-primary);
        font-size: 13px;
      }

      span,
      small {
        color: var(--el-text-color-secondary);
        font-size: 10px;
        line-height: 1.45;
      }

      small {
        color: var(--el-color-primary);
        font-weight: 700;
      }
    }
  }

  .tier-section-head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 16px;
  }

  .overlap-alert {
    margin-bottom: 16px;
    white-space: pre-line;
  }

  .tier-list {
    display: grid;
    gap: 16px;
  }

  .tier-editor {
    min-width: 0;
    overflow: hidden;
    border: 1px solid var(--el-border-color);
    border-radius: 13px;
    background: var(--el-bg-color);

    &.is-disabled {
      background: color-mix(in srgb, var(--el-fill-color-lighter) 70%, transparent);
    }

    &.has-overlap {
      border-color: var(--el-color-danger-light-5);
    }
  }

  .tier-editor__head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 16px;
    padding: 13px 15px;
    border-bottom: 1px solid var(--el-border-color-lighter);
    background: var(--el-fill-color-lighter);
  }

  .tier-editor__identity {
    display: flex;
    min-width: 0;
    align-items: center;
    gap: 10px;

    > span {
      display: grid;
      width: 32px;
      height: 32px;
      flex: none;
      place-items: center;
      border-radius: 10px;
      color: var(--lucky-green);
      font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
      font-size: 11px;
      font-weight: 800;
      background: var(--lucky-green-soft);
    }

    > div {
      display: grid;
      min-width: 0;
      gap: 2px;

      strong,
      small {
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }

      strong {
        color: var(--el-text-color-primary);
        font-size: 14px;
      }

      small {
        color: var(--el-text-color-secondary);
        font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
        font-size: 10px;
      }
    }
  }

  .tier-editor__actions {
    display: flex;
    flex: none;
    align-items: center;
    gap: 5px;

    :deep(.el-button + .el-button) {
      margin-left: 0;
    }
  }

  .tier-rule-grid {
    display: grid;
    grid-template-columns: 1.15fr 1fr 1fr 1.25fr;
    gap: 14px;
    padding: 16px 16px 2px;
    border-bottom: 1px dashed var(--el-border-color);

    :deep(.el-form-item__label) {
      color: var(--el-text-color-secondary);
      font-size: 11px;
    }
  }

  .tier-max-editor {
    display: grid;
    grid-template-columns: minmax(0, 1fr) auto;
    align-items: center;
    gap: 7px;

    :deep(.el-input-number) {
      width: 100%;
    }
  }

  .tier-max-editor__infinity,
  .range-code {
    display: flex;
    height: 32px;
    align-items: center;
    gap: 6px;
    padding: 0 9px;
    border: 1px dashed var(--el-border-color);
    border-radius: 7px;
    color: var(--el-color-primary);
    font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
    font-size: 10px;
    background: var(--el-color-primary-light-9);
  }

  .range-code {
    width: 100%;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .tier-editor :deep(.prize-pool) {
    padding: 16px;
  }

  .tier-empty {
    display: grid;
    min-height: 180px;
    place-content: center;
    justify-items: center;
    gap: 10px;
    padding: 24px;
    border: 1px dashed var(--el-border-color);
    border-radius: 12px;
    text-align: center;

    > svg {
      color: var(--el-color-primary);
      font-size: 34px;
    }

    > div {
      display: grid;
      gap: 4px;

      strong {
        color: var(--el-text-color-primary);
      }

      span {
        color: var(--el-text-color-secondary);
        font-size: 12px;
      }
    }
  }

  @media (max-width: 1280px) {
    .lucky-layout {
      grid-template-columns: minmax(0, 1fr) 310px;
    }

    .tier-rule-grid {
      grid-template-columns: repeat(2, minmax(0, 1fr));
    }
  }

  @media (max-width: 1040px) {
    .lucky-layout {
      grid-template-columns: 1fr;
    }

    .preview-rail {
      position: static;
      max-height: none;
      overflow: visible;
    }
  }

  @media (max-width: 760px) {
    .lucky-head {
      align-items: flex-start;
      flex-direction: column;
    }

    .lucky-head__actions {
      justify-content: flex-start;
    }

    .campaign-status {
      grid-template-columns: 42px minmax(0, 1fr);

      :deep(.el-switch) {
        grid-column: 1 / -1;
        justify-self: start;
      }
    }

    .form-grid,
    .share-editor,
    .tier-rule-grid {
      grid-template-columns: 1fr;
    }

    .form-grid__wide {
      grid-column: auto;
    }

    .share-preview {
      width: 100%;
      max-width: 320px;
    }

    .tier-section-head,
    .tier-editor__head {
      align-items: flex-start;
      flex-direction: column;
    }

    .tier-editor__actions {
      flex-wrap: wrap;
    }
  }
</style>
