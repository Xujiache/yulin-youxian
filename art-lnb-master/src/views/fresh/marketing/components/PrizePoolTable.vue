<template>
  <div class="prize-pool">
    <div class="prize-pool__head">
      <div>
        <strong>固定奖项</strong>
        <span>四个槽位不可增删改名。前三等奖可分别选择满减或百分比，谢谢惠顾固定无减免。</span>
      </div>
    </div>

    <ElTable :data="modelValue" :row-key="rowKey" border class="prize-table">
      <ElTableColumn label="奖项" width="120" fixed="left">
        <template #default="{ row }">
          <div class="prize-name">
            <strong>{{ row.name }}</strong>
            <small>{{ row.prizeCode }}</small>
          </div>
        </template>
      </ElTableColumn>

      <ElTableColumn label="中奖概率" width="180">
        <template #default="{ row, $index }">
          <div class="amount-editor">
            <ElInputNumber
              :model-value="bpToPercent(row.probabilityBp)"
              :min="0"
              :max="100"
              :precision="2"
              :step="1"
              controls-position="right"
              @update:model-value="(value) => updateProbability($index, value)"
            />
            <span>%</span>
          </div>
        </template>
      </ElTableColumn>

      <ElTableColumn label="减免规则" min-width="420">
        <template #default="{ row, $index }">
          <div v-if="row.prizeCode === 'NONE'" class="none-hint">无减免</div>
          <div v-else class="rule-editor">
            <ElSelect
              :model-value="row.discountMode"
              size="small"
              @change="(value) => changeMode($index, value)"
            >
              <ElOption label="满减模式" value="THRESHOLD" />
              <ElOption label="百分比模式" value="PERCENTAGE" />
            </ElSelect>
            <div v-if="row.discountMode === 'THRESHOLD'" class="amount-editor">
              <span>满</span>
              <ElInputNumber
                :model-value="centToYuan(row.thresholdAmount)"
                :min="0"
                :precision="2"
                :step="1"
                controls-position="right"
                @update:model-value="(value) => updatePrize($index, { thresholdAmount: yuanToCent(value) })"
              />
              <span>元减</span>
              <ElInputNumber
                :model-value="centToYuan(row.fixedDiscountAmount)"
                :min="0"
                :precision="2"
                :step="1"
                controls-position="right"
                @update:model-value="(value) => updatePrize($index, { fixedDiscountAmount: yuanToCent(value) })"
              />
              <span>元</span>
            </div>
            <div v-else class="amount-editor">
              <ElInputNumber
                :model-value="bpToPercent(row.discountRateBp)"
                :min="0"
                :max="100"
                :precision="2"
                :step="1"
                controls-position="right"
                @update:model-value="(value) => updatePrize($index, { discountRateBp: percentToBp(value) })"
              />
              <span>% ，最高减</span>
              <ElInputNumber
                :model-value="centToYuan(row.maxDiscountAmount)"
                :min="0"
                :precision="2"
                :step="1"
                controls-position="right"
                @update:model-value="(value) => updatePrize($index, { maxDiscountAmount: yuanToCent(value) })"
              />
              <span>元</span>
            </div>
          </div>
        </template>
      </ElTableColumn>
    </ElTable>
  </div>
</template>

<script setup lang="ts">
  import type { EditableLotteryPrize, LotteryDiscountMode } from '@/api/marketing'

  const props = defineProps<{
    modelValue: EditableLotteryPrize[]
  }>()

  const emit = defineEmits<{
    'update:modelValue': [value: EditableLotteryPrize[]]
  }>()

  const rowKey = (row: EditableLotteryPrize) => row.localId
  const centToYuan = (value?: number | null) => Number((Number(value || 0) / 100).toFixed(2))
  const yuanToCent = (value?: number | null) => Math.max(0, Math.round(Number(value || 0) * 100))
  const bpToPercent = (value?: number | null) => Number((Number(value || 0) / 100).toFixed(2))
  const percentToBp = (value?: number | null) => Math.max(0, Math.round(Number(value || 0) * 100))

  const replace = (index: number, patch: Partial<EditableLotteryPrize>) => {
    emit(
      'update:modelValue',
      props.modelValue.map((prize, current) => (current === index ? { ...prize, ...patch } : prize))
    )
  }

  const updatePrize = (index: number, patch: Partial<EditableLotteryPrize>) => replace(index, patch)

  const updateProbability = (index: number, value?: number | null) => {
    replace(index, { probabilityBp: percentToBp(value) })
  }

  const changeMode = (index: number, value: LotteryDiscountMode) => {
    if (value === 'THRESHOLD') {
      replace(index, {
        discountMode: 'THRESHOLD',
        discountRateBp: null,
        maxDiscountAmount: null
      })
      return
    }
    replace(index, {
      discountMode: 'PERCENTAGE',
      thresholdAmount: null,
      fixedDiscountAmount: null
    })
  }
</script>

<style scoped lang="scss">
  .prize-pool {
    display: grid;
    gap: 12px;
  }

  .prize-pool__head {
    display: flex;
    justify-content: space-between;
    gap: 12px;

    strong {
      display: block;
      font-size: 15px;
    }

    span {
      color: var(--el-text-color-secondary);
      font-size: 12px;
      line-height: 1.6;
    }
  }

  .prize-table {
    width: 100%;
  }

  .prize-name {
    display: grid;
    gap: 2px;

    small {
      color: var(--el-text-color-secondary);
      font-size: 11px;
    }
  }

  .amount-editor,
  .rule-editor {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 8px;
  }

  .none-hint {
    color: var(--el-text-color-secondary);
  }
</style>
