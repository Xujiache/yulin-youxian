<template>
  <div class="prize-pool">
    <div class="prize-pool__head">
      <div>
        <strong>奖项池</strong>
        <span>
          整数权重会实时换算为概率；禁用和库存耗尽的奖项不计入分母，预算与商品可售性以右侧预览为准。
        </span>
      </div>
      <div class="prize-pool__add">
        <ElButton size="small" type="primary" plain @click="addPrize('DISCOUNT')">
          <ArtSvgIcon icon="ri:coupon-3-line" />
          现金减免
        </ElButton>
        <ElButton size="small" type="primary" plain @click="addPrize('GOODS')">
          <ArtSvgIcon icon="ri:gift-line" />
          实物赠品
        </ElButton>
        <ElButton size="small" @click="addPrize('NONE')">
          <ArtSvgIcon icon="ri:emotion-normal-line" />
          谢谢惠顾
        </ElButton>
      </div>
    </div>

    <ElTable
      v-if="modelValue.length"
      :data="modelValue"
      :row-key="rowKey"
      border
      class="prize-table"
    >
      <ElTableColumn label="奖项类型" width="142" fixed="left">
        <template #default="{ row, $index }">
          <ElSelect
            :model-value="row.type"
            size="small"
            @change="(value) => changeType($index, value)"
          >
            <ElOption label="现金减免" value="DISCOUNT" />
            <ElOption label="实物赠品" value="GOODS" />
            <ElOption label="谢谢惠顾" value="NONE" />
          </ElSelect>
          <div class="type-caption">
            <ArtSvgIcon :icon="prizeIcon(row.type)" />
            {{ prizeTypeLabel(row.type) }}
          </div>
        </template>
      </ElTableColumn>

      <ElTableColumn label="奖项内容" min-width="330">
        <template #default="{ row, $index }">
          <div class="prize-content">
            <ElInput
              :model-value="row.name"
              maxlength="30"
              show-word-limit
              placeholder="奖项展示名称"
              @update:model-value="(value) => updatePrize($index, { name: String(value || '') })"
            />
            <div v-if="row.type === 'DISCOUNT'" class="amount-editor">
              <span>减免</span>
              <ElInputNumber
                :model-value="centToYuan(row.discountAmount)"
                :min="0"
                :precision="2"
                :step="0.5"
                controls-position="right"
                @update:model-value="(value) => updateDiscount($index, value)"
              />
              <span>元</span>
            </div>
            <ProductPicker
              v-else-if="row.type === 'GOODS'"
              :product-id="row.productId"
              :sku-id="row.skuId"
              :product-name="row.name"
              :image-url="row.imageUrl"
              @select="(selection) => chooseProduct($index, selection)"
            />
            <div v-else class="none-prize-tip">
              <ArtSvgIcon icon="ri:leaf-line" />
              不发放减免或赠品，仅参与概率分配
            </div>
          </div>
        </template>
      </ElTableColumn>

      <ElTableColumn label="权重 / 概率" width="166">
        <template #default="{ row, $index }">
          <div class="weight-editor">
            <ElInputNumber
              :model-value="row.weight"
              :min="0"
              :max="1000000"
              :precision="0"
              :step="1"
              controls-position="right"
              size="small"
              @update:model-value="
                (value) => updatePrize($index, { weight: Math.max(0, Number(value || 0)) })
              "
            />
            <div>
              <strong>{{ probabilityText(row) }}</strong>
              <span v-if="isExcluded(row)">{{ exclusionText(row) }}</span>
              <span v-else>当前命中概率</span>
            </div>
          </div>
        </template>
      </ElTableColumn>

      <ElTableColumn label="库存" width="250">
        <template #default="{ row, $index }">
          <div v-if="row.type !== 'GOODS'" class="stock-none">仅实物赠品需要库存</div>
          <div v-else class="stock-editor">
            <div class="stock-mode">
              <ElTag size="small" effect="plain" :type="isExhausted(row) ? 'danger' : 'success'">
                {{ isExhausted(row) ? '营销库存已耗尽' : '限量赠品' }}
              </ElTag>
              <span>已抽数量由服务端保留，保存配置不会重置</span>
            </div>
            <div class="stock-inputs">
              <label>
                <span>总</span>
                <ElInputNumber
                  :model-value="row.stockTotal"
                  :min="0"
                  :max="99999999"
                  :precision="0"
                  :controls="false"
                  size="small"
                  @update:model-value="(value) => updateStockTotal($index, value)"
                />
              </label>
              <label>
                <span>余</span>
                <strong class="stock-remaining">{{ row.stockRemaining ?? 0 }}</strong>
              </label>
            </div>
          </div>
        </template>
      </ElTableColumn>

      <ElTableColumn label="启用" width="92" align="center">
        <template #default="{ row, $index }">
          <div class="enable-editor">
            <ElSwitch
              :model-value="row.enabled"
              @change="(value) => updatePrize($index, { enabled: Boolean(value) })"
            />
            <span>{{ row.enabled ? '参与' : '停用' }}</span>
          </div>
        </template>
      </ElTableColumn>

      <ElTableColumn label="排序" width="112" align="center">
        <template #default="{ row, $index }">
          <div class="sort-editor">
            <ElButton
              text
              :disabled="$index === 0"
              aria-label="奖项上移"
              @click="movePrize($index, -1)"
            >
              <ArtSvgIcon icon="ri:arrow-up-line" />
            </ElButton>
            <span>{{ row.sortOrder }}</span>
            <ElButton
              text
              :disabled="$index === modelValue.length - 1"
              aria-label="奖项下移"
              @click="movePrize($index, 1)"
            >
              <ArtSvgIcon icon="ri:arrow-down-line" />
            </ElButton>
          </div>
        </template>
      </ElTableColumn>

      <ElTableColumn label="操作" width="74" align="center" fixed="right">
        <template #default="{ row, $index }">
          <ElButton link type="danger" @click="removePrize(row, $index)">删除</ElButton>
        </template>
      </ElTableColumn>
    </ElTable>

    <div v-else class="prize-pool__empty">
      <ArtSvgIcon icon="ri:gift-open-line" />
      <div>
        <strong>这个阶梯还没有奖项</strong>
        <span>至少添加一个启用且权重大于 0 的奖项，顾客才可参与抽取。</span>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
  import { ElMessageBox } from 'element-plus'
  import { createLocalId, type EditableLotteryPrize, type LotteryPrizeType } from '@/api/marketing'
  import ProductPicker from './ProductPicker.vue'

  interface ProductSelection {
    productId: number
    skuId: number | null
    productName: string
    skuName: string
    imageUrl: string
  }

  const props = defineProps<{
    modelValue: EditableLotteryPrize[]
  }>()

  const emit = defineEmits<{
    'update:modelValue': [value: EditableLotteryPrize[]]
  }>()

  const rowKey = (row: EditableLotteryPrize) =>
    row.id === null || row.id === undefined ? row.localId : `prize-${row.id}`

  const centToYuan = (value?: number | null) => Number((Number(value || 0) / 100).toFixed(2))
  const yuanToCent = (value?: number | null) => Math.max(0, Math.round(Number(value || 0) * 100))

  const isExhausted = (prize: EditableLotteryPrize) =>
    prize.type === 'GOODS' && Number(prize.stockRemaining || 0) <= 0
  const isExcluded = (prize: EditableLotteryPrize) =>
    !prize.enabled || isExhausted(prize) || !Number.isInteger(prize.weight) || prize.weight <= 0

  const denominator = computed(() =>
    props.modelValue.reduce(
      (total, prize) => total + (isExcluded(prize) ? 0 : Math.max(0, prize.weight)),
      0
    )
  )

  const probabilityText = (prize: EditableLotteryPrize) => {
    if (isExcluded(prize) || denominator.value <= 0) return '0.00%'
    return `${((prize.weight / denominator.value) * 100).toFixed(2)}%`
  }

  const exclusionText = (prize: EditableLotteryPrize) => {
    if (!prize.enabled) return '已停用，不计分母'
    if (isExhausted(prize)) return '库存耗尽，不计分母'
    return '权重为 0，不计分母'
  }

  const prizeTypeLabel = (type: LotteryPrizeType) =>
    ({ DISCOUNT: '现金', GOODS: '赠品', NONE: '未中奖' })[type]
  const prizeIcon = (type: LotteryPrizeType) =>
    ({
      DISCOUNT: 'ri:coupon-3-line',
      GOODS: 'ri:gift-line',
      NONE: 'ri:emotion-normal-line'
    })[type]

  const normalizedOrders = (items: EditableLotteryPrize[]) =>
    items.map((item, index) => ({ ...item, sortOrder: (index + 1) * 10 }))

  const updatePrize = (index: number, patch: Partial<EditableLotteryPrize>) => {
    const next = props.modelValue.map((prize, currentIndex) =>
      currentIndex === index ? { ...prize, ...patch } : prize
    )
    emit('update:modelValue', next)
  }

  const updateDiscount = (index: number, value?: number) => {
    updatePrize(index, { discountAmount: yuanToCent(value) })
  }

  const updateStockTotal = (index: number, value?: number) => {
    const stockTotal = Math.max(0, Math.round(Number(value || 0)))
    const current = props.modelValue[index]
    const previousTotal = Math.max(0, Number(current?.stockTotal || 0))
    const previousRemaining = Math.max(0, Number(current?.stockRemaining || 0))
    const consumed = Math.max(previousTotal - previousRemaining, 0)
    updatePrize(index, {
      stockTotal,
      stockRemaining: Math.max(stockTotal - consumed, 0)
    })
  }

  const changeType = (index: number, value: unknown) => {
    const type = value as LotteryPrizeType
    const current = props.modelValue[index]
    if (!current || !['DISCOUNT', 'GOODS', 'NONE'].includes(type)) return

    const defaultNames: Record<LotteryPrizeType, string> = {
      DISCOUNT: '随机减免',
      GOODS: '实物赠品',
      NONE: '谢谢惠顾'
    }
    const shouldReplaceName =
      !current.name.trim() || ['随机减免', '实物赠品', '谢谢惠顾'].includes(current.name)
    const common = {
      type,
      name: shouldReplaceName ? defaultNames[type] : current.name
    }

    if (type === 'DISCOUNT') {
      updatePrize(index, {
        ...common,
        discountAmount: current.discountAmount > 0 ? current.discountAmount : 100,
        productId: null,
        skuId: null,
        imageUrl: '',
        stockTotal: null,
        stockRemaining: null
      })
      return
    }
    if (type === 'GOODS') {
      updatePrize(index, {
        ...common,
        discountAmount: 0,
        stockTotal: current.stockTotal ?? 100,
        stockRemaining: current.stockRemaining ?? 100
      })
      return
    }
    updatePrize(index, {
      ...common,
      discountAmount: 0,
      productId: null,
      skuId: null,
      imageUrl: '',
      stockTotal: null,
      stockRemaining: null
    })
  }

  const chooseProduct = (index: number, selection: ProductSelection) => {
    const current = props.modelValue[index]
    if (!current) return
    updatePrize(index, {
      productId: selection.productId,
      skuId: selection.skuId,
      imageUrl: selection.imageUrl,
      name:
        !current.name.trim() || current.name === '实物赠品' ? selection.productName : current.name
    })
  }

  const addPrize = (type: LotteryPrizeType) => {
    const limited = type === 'GOODS'
    const next: EditableLotteryPrize = {
      localId: createLocalId('prize'),
      id: null,
      type,
      name: type === 'DISCOUNT' ? '随机减免' : type === 'GOODS' ? '实物赠品' : '谢谢惠顾',
      discountAmount: type === 'DISCOUNT' ? 100 : 0,
      productId: null,
      skuId: null,
      imageUrl: '',
      weight: type === 'NONE' ? 30 : 10,
      stockTotal: limited ? 100 : null,
      stockRemaining: limited ? 100 : null,
      enabled: true,
      sortOrder: (props.modelValue.length + 1) * 10
    }
    emit('update:modelValue', [...props.modelValue, next])
  }

  const movePrize = (index: number, direction: -1 | 1) => {
    const target = index + direction
    if (target < 0 || target >= props.modelValue.length) return
    const next = [...props.modelValue]
    const [current] = next.splice(index, 1)
    next.splice(target, 0, current)
    emit('update:modelValue', normalizedOrders(next))
  }

  const removePrize = async (row: EditableLotteryPrize, index: number) => {
    try {
      await ElMessageBox.confirm(
        `确认删除奖项「${row.name || prizeTypeLabel(row.type)}」？`,
        '删除奖项',
        {
          type: 'warning',
          confirmButtonText: '删除',
          cancelButtonText: '保留'
        }
      )
      emit(
        'update:modelValue',
        normalizedOrders(props.modelValue.filter((_, currentIndex) => currentIndex !== index))
      )
    } catch (error) {
      if (error !== 'cancel' && error !== 'close') throw error
    }
  }
</script>

<style scoped lang="scss">
  .prize-pool {
    display: grid;
    gap: 12px;
  }

  .prize-pool__head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 14px;

    > div:first-child {
      display: grid;
      gap: 3px;

      strong {
        color: var(--el-text-color-primary);
        font-size: 14px;
      }

      span {
        color: var(--el-text-color-secondary);
        font-size: 12px;
      }
    }
  }

  .prize-pool__add {
    display: flex;
    flex-wrap: wrap;
    justify-content: flex-end;
    gap: 8px;

    :deep(.el-button + .el-button) {
      margin-left: 0;
    }
  }

  .type-caption {
    display: flex;
    align-items: center;
    gap: 5px;
    margin-top: 6px;
    color: var(--el-text-color-secondary);
    font-size: 11px;

    > svg {
      color: var(--el-color-primary);
    }
  }

  .prize-content {
    display: grid;
    gap: 9px;
  }

  .amount-editor {
    display: flex;
    align-items: center;
    gap: 7px;
    color: var(--el-text-color-secondary);
    font-size: 12px;

    :deep(.el-input-number) {
      width: 138px;
    }
  }

  .none-prize-tip {
    display: flex;
    align-items: center;
    gap: 7px;
    color: var(--el-text-color-secondary);
    font-size: 12px;
  }

  .weight-editor {
    display: grid;
    gap: 8px;

    :deep(.el-input-number) {
      width: 100%;
    }

    > div {
      display: grid;
      gap: 2px;

      strong {
        color: var(--el-color-primary);
        font-variant-numeric: tabular-nums;
      }

      span {
        color: var(--el-text-color-secondary);
        font-size: 10px;
      }
    }
  }

  .stock-editor {
    display: grid;
    gap: 8px;
  }

  .stock-mode {
    display: flex;
    align-items: center;
    gap: 8px;

    > span {
      color: var(--el-text-color-secondary);
      font-size: 10px;
      line-height: 1.35;
    }
  }

  .stock-inputs {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 7px;

    label {
      display: flex;
      min-width: 0;
      align-items: center;
      gap: 4px;

      > span {
        flex: none;
        color: var(--el-text-color-secondary);
        font-size: 11px;
      }
    }

    :deep(.el-input-number) {
      width: 100%;
    }
  }

  .stock-remaining {
    display: grid;
    width: 100%;
    height: 24px;
    place-items: center;
    border: 1px solid var(--el-border-color);
    border-radius: 5px;
    color: var(--el-text-color-regular);
    font-size: 12px;
    font-variant-numeric: tabular-nums;
    background: var(--el-fill-color-light);
  }

  .stock-none {
    color: var(--el-text-color-secondary);
    font-size: 12px;
  }

  .enable-editor {
    display: grid;
    justify-items: center;
    gap: 4px;

    span {
      color: var(--el-text-color-secondary);
      font-size: 10px;
    }
  }

  .sort-editor {
    display: grid;
    grid-template-columns: 28px 1fr 28px;
    align-items: center;

    span {
      color: var(--el-text-color-secondary);
      font-size: 11px;
      font-variant-numeric: tabular-nums;
    }
  }

  .prize-pool__empty {
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 18px;
    border: 1px dashed var(--el-border-color);
    border-radius: 10px;
    background: var(--el-fill-color-lighter);

    > svg {
      color: var(--el-color-primary);
      font-size: 28px;
    }

    > div {
      display: grid;
      gap: 4px;

      strong {
        color: var(--el-text-color-primary);
        font-size: 13px;
      }

      span {
        color: var(--el-text-color-secondary);
        font-size: 12px;
      }
    }
  }

  .prize-table {
    :deep(.el-table__cell) {
      vertical-align: middle;
    }

    :deep(.cell) {
      overflow: visible;
    }
  }

  @media (max-width: 780px) {
    .prize-pool__head {
      align-items: flex-start;
      flex-direction: column;
    }

    .prize-pool__add {
      justify-content: flex-start;
    }
  }
</style>
