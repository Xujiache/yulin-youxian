<template>
  <ElDialog
    :model-value="modelValue"
    title="处理配送异常"
    width="620px"
    append-to-body
    @update:model-value="(value: boolean) => emit('update:modelValue', value)"
    @closed="reset"
  >
    <div v-if="exception" class="handle-dialog">
      <div class="handle-dialog__summary">
        <div class="handle-dialog__row">
          <ElTag size="small" :type="severityTag(exception.severity)" effect="dark">
            {{ severityText(exception.severity) }}
          </ElTag>
          <strong>{{ exceptionTypeText(exception.exceptionType) }}</strong>
          <span>{{ exception.exceptionNo }}</span>
        </div>
        <div class="handle-dialog__row handle-dialog__row--muted">
          <span>{{ exception.taskId ? `任务 ID ${exception.taskId}` : '未关联配送任务' }}</span>
          <span>上报 {{ dateTimeText(exception.createdAt) }}</span>
        </div>
        <p v-if="exception.description" class="handle-dialog__desc">{{ exception.description }}</p>
        <div v-if="photoUrls.length > 0" class="handle-dialog__photos">
          <ElImage
            v-for="(url, index) in photoUrls"
            :key="index"
            class="handle-dialog__thumb"
            :src="url"
            :preview-src-list="photoUrls"
            :initial-index="index"
            preview-teleported
            fit="cover"
          />
        </div>
      </div>

      <ElForm label-width="96px">
        <ElFormItem label="处理方式">
          <ElRadioGroup v-model="resolutionType">
            <ElRadio v-for="item in RESOLUTION_OPTIONS" :key="item.value" :value="item.value">
              {{ item.label }}
            </ElRadio>
          </ElRadioGroup>
          <p class="handle-dialog__hint">{{ currentHint }}</p>
        </ElFormItem>
        <ElFormItem v-if="resolutionType === 'REASSIGN'" label="目标骑手" required>
          <ElButton @click="openRiderPicker">
            {{ selectedRiderName || '选择目标骑手' }}
          </ElButton>
          <span class="handle-dialog__hint">改派时必须选择一个可接单骑手。</span>
        </ElFormItem>
        <ElFormItem label="处理备注">
          <ElInput
            v-model="resolutionNote"
            type="textarea"
            :rows="3"
            maxlength="500"
            show-word-limit
            placeholder="填写处理经过，会同步到异常记录与任务事件流水"
          />
        </ElFormItem>
        <ElFormItem label="免除骑手责任">
          <ElSwitch v-model="riderExempt" />
          <span class="handle-dialog__hint">
            默认免除。仅在确认为骑手责任时才关闭，关闭后会影响服务分与结算。
          </span>
        </ElFormItem>
      </ElForm>
    </div>

    <template #footer>
      <ElButton @click="emit('update:modelValue', false)">取消</ElButton>
      <ElButton type="primary" :loading="submitting" @click="submit">提交处理</ElButton>
    </template>
  </ElDialog>

  <RiderPicker
    v-model="riderPickerVisible"
    :riders="riders"
    :now="now"
    title="选择改派骑手"
    confirm-text="确认选择"
    :loading="ridersLoading"
    @confirm="selectRider"
  />
</template>

<script setup lang="ts">
  import { ElMessage, ElMessageBox } from 'element-plus'
  import {
    getDeliveryBoard,
    getEvidences,
    handleException,
    type DeliveryEvidence,
    type DeliveryExceptionItem,
    type RiderBoardCard
  } from '@/api/delivery'
  import { resolveFreshAssetUrl } from '@/utils/fresh-assets'
  import RiderPicker from './RiderPicker.vue'
  import {
    dateTimeText,
    exceptionTypeText,
    RESOLUTION_OPTIONS,
    severityTag,
    severityText
  } from '../utils'

  defineOptions({ name: 'ExceptionHandleDialog' })

  const props = defineProps<{
    modelValue: boolean
    exception: DeliveryExceptionItem | null
  }>()

  const emit = defineEmits<{
    (event: 'update:modelValue', value: boolean): void
    (event: 'done'): void
  }>()

  const resolutionType = ref('CONTINUE')
  const resolutionNote = ref('')
  const riderExempt = ref(true)
  const submitting = ref(false)
  const evidences = ref<DeliveryEvidence[]>([])
  const riders = ref<RiderBoardCard[]>([])
  const ridersLoading = ref(false)
  const riderPickerVisible = ref(false)
  const toRiderId = ref<number | null>(null)
  const now = ref(Date.now())

  const photoUrls = computed(() =>
    evidences.value.map((item) => resolveFreshAssetUrl(item.fileUrl)).filter(Boolean)
  )

  const currentHint = computed(
    () => RESOLUTION_OPTIONS.find((item) => item.value === resolutionType.value)?.hint || ''
  )
  const selectedRiderName = computed(
    () => riders.value.find((item) => item.riderId === toRiderId.value)?.name || ''
  )

  const loadRiders = async () => {
    ridersLoading.value = true
    try {
      const board = await getDeliveryBoard()
      riders.value = board.riders || []
    } catch {
      riders.value = []
    } finally {
      ridersLoading.value = false
    }
  }

  const loadEvidences = async () => {
    const exceptionId = props.exception?.exceptionId
    if (!exceptionId) {
      evidences.value = []
      return
    }
    try {
      evidences.value = await getEvidences({ exceptionId })
    } catch {
      evidences.value = []
    }
  }

  const openRiderPicker = () => {
    if (!props.exception?.taskId) {
      ElMessage.warning('该异常未关联配送任务，无法改派')
      return
    }
    now.value = Date.now()
    riderPickerVisible.value = true
    if (riders.value.length === 0) void loadRiders()
  }

  const selectRider = (payload: { riderId: number }) => {
    toRiderId.value = payload.riderId
    riderPickerVisible.value = false
  }

  const reset = () => {
    resolutionType.value = 'CONTINUE'
    resolutionNote.value = ''
    riderExempt.value = true
    evidences.value = []
    riderPickerVisible.value = false
    toRiderId.value = null
  }

  const submit = async () => {
    if (!props.exception) return
    if (resolutionType.value === 'REASSIGN' && !toRiderId.value) {
      ElMessage.warning('请选择目标骑手')
      return
    }
    const label =
      RESOLUTION_OPTIONS.find((item) => item.value === resolutionType.value)?.label || ''
    const riderText =
      resolutionType.value === 'REASSIGN' && selectedRiderName.value
        ? `目标骑手：${selectedRiderName.value}。`
        : ''
    try {
      await ElMessageBox.confirm(
        `确认按「${label}」处理异常 ${props.exception.exceptionNo}？${riderText}${
          riderExempt.value ? '本次免除骑手责任。' : '本次判定为骑手责任，将影响服务分。'
        }`,
        '处理异常',
        { type: 'warning', confirmButtonText: '确认处理', cancelButtonText: '再想想' }
      )
      submitting.value = true
      await handleException(
        props.exception.exceptionId,
        {
          resolutionType: resolutionType.value,
          resolutionNote: resolutionNote.value.trim() || undefined,
          riderExempt: riderExempt.value
        },
        resolutionType.value === 'REASSIGN' ? toRiderId.value || undefined : undefined
      )
      ElMessage.success('异常已处理')
      emit('update:modelValue', false)
      emit('done')
    } catch (error) {
      if (error !== 'cancel' && error !== 'close') {
        ElMessage.error(error instanceof Error ? error.message : '异常处理失败')
      }
    } finally {
      submitting.value = false
    }
  }

  watch(
    () => props.modelValue,
    (visible) => {
      if (!visible) return
      now.value = Date.now()
      void Promise.all([loadRiders(), loadEvidences()])
    }
  )
</script>

<style scoped lang="scss">
  .handle-dialog {
    display: grid;
    gap: 14px;
  }

  .handle-dialog__summary {
    display: grid;
    gap: 8px;
    padding: 12px;
    border-radius: 10px;
    background: var(--el-fill-color-light);
  }

  .handle-dialog__row {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 8px;

    strong {
      color: var(--art-gray-900);
    }

    &--muted {
      color: var(--art-gray-600);
      font-size: 12px;
    }
  }

  .handle-dialog__desc {
    margin: 0;
    color: var(--art-gray-800);
    font-size: 13px;
    line-height: 19px;
  }

  .handle-dialog__photos {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
  }

  .handle-dialog__thumb {
    width: 64px;
    height: 64px;
    overflow: hidden;
    border: 1px solid var(--art-border-color);
    border-radius: 8px;
  }

  .handle-dialog__hint {
    margin: 4px 0 0;
    color: var(--art-gray-600);
    font-size: 12px;
    line-height: 18px;
  }
</style>
