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
          全活动统一四个固定奖项。一等奖、二等奖、三等奖可分别选择满减或百分比，谢谢惠顾无减免。
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
                <div class="budget-editor">
                  <ElInputNumber
                    v-if="form.dailyBudgetAmount !== null"
                    :model-value="centToYuan(form.dailyBudgetAmount)"
                    :min="0"
                    :max="99999999"
                    :precision="2"
                    :step="100"
                    controls-position="right"
                    @update:model-value="updateDailyBudget"
                  />
                  <div v-else class="budget-editor__unlimited">+∞ 不限预算</div>
                  <ElSwitch
                    :model-value="form.dailyBudgetAmount === null"
                    inline-prompt
                    active-text="不限"
                    inactive-text="限额"
                    @change="(value) => toggleDailyBudget(Boolean(value))"
                  />
                </div>
                <span class="field-tip">{{ budgetTip }}</span>
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
            <div class="section-head">
              <div class="section-head__marker"><ArtSvgIcon icon="ri:stack-line" /></div>
              <div>
                <h2>固定奖项</h2>
                <p>四个扇形面积都是 25%，中奖概率由下面四项分别配置，总和必须等于 100.00%。</p>
              </div>
            </div>
          </template>
          <PrizePoolTable v-model="form.prizes" />
        </ElCard>
      </main>

      <div class="preview-rail">
        <ProbabilityPreview
          :prizes="form.prizes"
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
    createLocalId,
    getLotteryCampaign,
    LOTTERY_PRIZE_CODES,
    LOTTERY_PRIZE_NAMES,
    updateLotteryCampaign,
    type EditableLotteryCampaign,
    type EditableLotteryPrize,
    type LotteryCampaign,
    type LotteryCampaignPayload,
    type LotteryDiscountMode,
    type LotteryId,
    type LotteryPrize,
    type LotteryPrizeCode
  } from '@/api/marketing'
  import { resolveFreshAssetUrl } from '@/utils/fresh-assets'
  import PrizePoolTable from '../components/PrizePoolTable.vue'
  import ProbabilityPreview from '../components/ProbabilityPreview.vue'

  defineOptions({ name: 'FreshLuckyDraw' })

  const router = useRouter()
  const loading = ref(false)
  const saving = ref(false)
  /** 切到“不限预算”前的限额，便于切回时恢复 */
  const lastBudgetAmount = ref(10000)

  const safeNumber = (value: unknown, fallback = 0) => {
    if (value === null || value === undefined || value === '') return fallback
    const parsed = Number(value)
    return Number.isFinite(parsed) ? parsed : fallback
  }
  const safeInteger = (value: unknown, fallback = 0) =>
    Math.max(0, Math.round(safeNumber(value, fallback)))
  const normalizeId = (value: unknown): LotteryId | null =>
    typeof value === 'number' || typeof value === 'string' ? value : null
  const normalizePrizeCode = (value: unknown): LotteryPrizeCode | null =>
    value === 'FIRST' || value === 'SECOND' || value === 'THIRD' || value === 'NONE' ? value : null
  const normalizeDiscountMode = (value: unknown, prizeCode: LotteryPrizeCode): LotteryDiscountMode => {
    if (prizeCode === 'NONE') return 'NONE'
    return value === 'THRESHOLD' || value === 'PERCENTAGE' ? value : 'PERCENTAGE'
  }

  const createDefaultPrize = (prizeCode: LotteryPrizeCode): EditableLotteryPrize => ({
    localId: createLocalId('prize'),
    id: null,
    prizeCode,
    name: LOTTERY_PRIZE_NAMES[prizeCode],
    type: prizeCode === 'NONE' ? 'NONE' : 'DISCOUNT',
    probabilityBp: prizeCode === 'NONE' ? 10000 : 0,
    discountMode: prizeCode === 'NONE' ? 'NONE' : 'PERCENTAGE',
    thresholdAmount: null,
    fixedDiscountAmount: null,
    discountRateBp: null,
    maxDiscountAmount: null,
    sortOrder: prizeCode === 'FIRST' ? 10 : prizeCode === 'SECOND' ? 20 : prizeCode === 'THIRD' ? 30 : 40
  })

  const createDefaultPrizes = (): EditableLotteryPrize[] =>
    LOTTERY_PRIZE_CODES.map((code) => createDefaultPrize(code))

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
    prizes: createDefaultPrizes(),
    tiers: []
  })

  const form = reactive<EditableLotteryCampaign>(createDefaultCampaign())

  const normalizeStock = (value: unknown) => {
    if (value === null || value === undefined || value === '') return null
    return safeInteger(value)
  }

  /** 后端用 null 表示不限每日现金预算，前端必须原样保留 */
  const normalizeBudget = (value: unknown) => {
    if (value === null || value === undefined || value === '') return null
    return safeInteger(value)
  }

  const normalizePrize = (
    prize: LotteryPrize | null | undefined,
    prizeCode: LotteryPrizeCode
  ): EditableLotteryPrize => {
    const defaults = createDefaultPrize(prizeCode)
    const mode = normalizeDiscountMode(prize?.discountMode, prizeCode)
    return {
      ...defaults,
      id: normalizeId(prize?.id),
      name: LOTTERY_PRIZE_NAMES[prizeCode],
      probabilityBp: safeInteger(prize?.probabilityBp, defaults.probabilityBp),
      discountMode: mode,
      thresholdAmount: mode === 'THRESHOLD' ? normalizeStock(prize?.thresholdAmount) : null,
      fixedDiscountAmount: mode === 'THRESHOLD' ? normalizeStock(prize?.fixedDiscountAmount) : null,
      discountRateBp: mode === 'PERCENTAGE' ? normalizeStock(prize?.discountRateBp) : null,
      maxDiscountAmount: mode === 'PERCENTAGE' ? normalizeStock(prize?.maxDiscountAmount) : null,
      sortOrder: defaults.sortOrder
    }
  }

  const normalizeCampaign = (
    campaign: LotteryCampaign | null | undefined
  ): EditableLotteryCampaign => {
    if (!campaign || Object.keys(campaign).length === 0) return createDefaultCampaign()
    const incoming = Array.isArray(campaign.prizes) ? campaign.prizes : []
    const byCode = new Map<LotteryPrizeCode, LotteryPrize>()
    incoming.forEach((prize) => {
      const code = normalizePrizeCode(prize?.prizeCode)
      if (code) byCode.set(code, prize as LotteryPrize)
    })
    return {
      id: normalizeId(campaign.id),
      enabled: campaign.enabled === true,
      name: String(campaign.name || '').trim() || '随机减免活动',
      startAt: String(campaign.startAt || ''),
      endAt: String(campaign.endAt || ''),
      dailyUserLimit: Math.max(1, safeInteger(campaign.dailyUserLimit, 1)),
      dailyBudgetAmount: normalizeBudget(campaign.dailyBudgetAmount),
      shareTitle: String(campaign.shareTitle || ''),
      shareDescription: String(campaign.shareDescription || ''),
      shareImageUrl: String(campaign.shareImageUrl || ''),
      prizes: LOTTERY_PRIZE_CODES.map((code) => normalizePrize(byCode.get(code), code)),
      tiers: []
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

  const updateDailyBudget = (value?: number | null) => {
    form.dailyBudgetAmount = yuanToCent(value)
    lastBudgetAmount.value = form.dailyBudgetAmount
  }
  const toggleDailyBudget = (unlimited: boolean) => {
    if (!unlimited) {
      form.dailyBudgetAmount = lastBudgetAmount.value
      return
    }
    if (form.dailyBudgetAmount !== null) lastBudgetAmount.value = form.dailyBudgetAmount
    form.dailyBudgetAmount = null
  }
  const budgetTip = computed(() =>
    form.dailyBudgetAmount === null
      ? '不限预算：现金奖项不会因当日累计减免而退出奖池，保存时提交 null。'
      : '预算与减免金额均按“分”提交。未达满减门槛或预算不足的奖项会退出本次候选。'
  )

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

  const validateCampaign = () => {
    const errors: string[] = []
    if (!form.name.trim()) errors.push('请填写活动名称')
    if (!Number.isInteger(form.dailyUserLimit) || form.dailyUserLimit < 1) {
      errors.push('每日抽取次数必须是大于 0 的整数')
    }
    if (
      form.dailyBudgetAmount !== null &&
      (!Number.isInteger(form.dailyBudgetAmount) || form.dailyBudgetAmount < 0)
    ) {
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
    if (form.shareTitle.length > 128) errors.push('分享标题过长')
    if (form.shareDescription.length > 512) errors.push('分享描述过长')
    if (form.shareImageUrl.length > 512) errors.push('分享图片地址过长')

    const prizes = form.prizes
    const codes = prizes.map((prize) => prize.prizeCode)
    if (prizes.length !== 4) errors.push('必须配置四个固定奖项')
    LOTTERY_PRIZE_CODES.forEach((code) => {
      if (codes.filter((item) => item === code).length !== 1) {
        errors.push(`固定奖项 ${LOTTERY_PRIZE_NAMES[code]} 必须恰好出现一次`)
      }
    })

    let totalBp = 0
    let maxDiscount = 0
    prizes.forEach((prize) => {
      const label = prize.name || LOTTERY_PRIZE_NAMES[prize.prizeCode]
      const probability = Number(prize.probabilityBp)
      if (!Number.isInteger(probability) || probability < 0 || probability > 10000) {
        errors.push(`「${label}」中奖概率必须在 0.00%–100.00% 之间，最多两位小数`)
      } else {
        totalBp += probability
      }

      if (prize.prizeCode === 'NONE') {
        if (prize.discountMode !== 'NONE') errors.push('谢谢惠顾必须是无减免')
        if (
          (prize.thresholdAmount || 0) > 0 ||
          (prize.fixedDiscountAmount || 0) > 0 ||
          (prize.discountRateBp || 0) > 0 ||
          (prize.maxDiscountAmount || 0) > 0
        ) {
          errors.push('谢谢惠顾不能配置任何减免字段')
        }
        return
      }

      if (prize.discountMode !== 'THRESHOLD' && prize.discountMode !== 'PERCENTAGE') {
        errors.push(`「${label}」请选择满减或百分比模式`)
      }
      if (probability <= 0) return

      if (prize.discountMode === 'THRESHOLD') {
        const threshold = Number(prize.thresholdAmount || 0)
        const discount = Number(prize.fixedDiscountAmount || 0)
        if (!Number.isInteger(threshold) || threshold <= 0) {
          errors.push(`「${label}」满减门槛必须大于 0 元`)
        }
        if (!Number.isInteger(discount) || discount <= 0) {
          errors.push(`「${label}」满减金额必须大于 0 元`)
        }
        if (threshold > 0 && discount > 0 && discount >= threshold) {
          errors.push(`「${label}」满减金额必须小于门槛`)
        }
        if ((prize.discountRateBp || 0) > 0 || (prize.maxDiscountAmount || 0) > 0) {
          errors.push(`「${label}」满减模式不能填写百分比字段`)
        }
        maxDiscount = Math.max(maxDiscount, discount)
      }

      if (prize.discountMode === 'PERCENTAGE') {
        const rate = Number(prize.discountRateBp || 0)
        const cap = Number(prize.maxDiscountAmount || 0)
        if (!Number.isInteger(rate) || rate < 1 || rate > 10000) {
          errors.push(`「${label}」减免比例必须在 0.01%–100.00% 之间`)
        }
        if (!Number.isInteger(cap) || cap <= 0) {
          errors.push(`「${label}」最大减免金额必须大于 0 元`)
        }
        if ((prize.thresholdAmount || 0) > 0 || (prize.fixedDiscountAmount || 0) > 0) {
          errors.push(`「${label}」百分比模式不能填写满减字段`)
        }
        maxDiscount = Math.max(maxDiscount, cap)
      }
    })

    if (totalBp !== 10000) {
      const delta = ((10000 - totalBp) / 100).toFixed(2)
      errors.push(
        Number(delta) > 0
          ? `四项中奖概率总和必须等于 100.00%，还差 ${delta}%`
          : `四项中奖概率总和必须等于 100.00%，超出 ${Math.abs(Number(delta)).toFixed(2)}%`
      )
    }

    if (
      form.enabled &&
      form.dailyBudgetAmount !== null &&
      maxDiscount > 0 &&
      form.dailyBudgetAmount < maxDiscount
    ) {
      errors.push(`每日现金预算不能低于最高单笔减免 ${money(maxDiscount)}`)
    }
    return [...new Set(errors)]
  }

  const createPayload = (): LotteryCampaignPayload => ({
    id: form.id,
    enabled: Boolean(form.enabled),
    name: form.name.trim(),
    startAt: form.startAt,
    endAt: form.endAt,
    dailyUserLimit: Math.round(form.dailyUserLimit),
    dailyBudgetAmount: form.dailyBudgetAmount === null ? null : Math.round(form.dailyBudgetAmount),
    shareTitle: form.shareTitle.trim(),
    shareDescription: form.shareDescription.trim(),
    shareImageUrl: form.shareImageUrl.trim(),
    prizes: LOTTERY_PRIZE_CODES.map((code, index) => {
      const prize = form.prizes.find((item) => item.prizeCode === code) || createDefaultPrize(code)
      const mode = code === 'NONE' ? 'NONE' : prize.discountMode
      return {
        id: prize.id,
        prizeCode: code,
        name: LOTTERY_PRIZE_NAMES[code],
        type: code === 'NONE' ? 'NONE' : 'DISCOUNT',
        probabilityBp: Math.round(prize.probabilityBp),
        discountMode: mode,
        thresholdAmount: mode === 'THRESHOLD' ? prize.thresholdAmount : null,
        fixedDiscountAmount: mode === 'THRESHOLD' ? prize.fixedDiscountAmount : null,
        discountRateBp: mode === 'PERCENTAGE' ? prize.discountRateBp : null,
        maxDiscountAmount: mode === 'PERCENTAGE' ? prize.maxDiscountAmount : null,
        sortOrder: (index + 1) * 10
      }
    })
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
      // 服务端未回传内容时用提交数据回填，同样走一遍归一化以补齐本地标识
      const saved: LotteryCampaign =
        result && Object.keys(result).length > 0 ? result : { ...payload }
      Object.assign(form, normalizeCampaign(saved))
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

  .budget-editor,
  .tier-max-editor {
    display: grid;
    grid-template-columns: minmax(0, 1fr) auto;
    align-items: center;
    gap: 7px;

    :deep(.el-input-number) {
      width: 100%;
    }
  }

  .budget-editor {
    width: 100%;
  }

  .budget-editor__unlimited,
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
