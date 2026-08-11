<template>
  <ElDialog
    :model-value="modelValue"
    :title="rider ? `发消息给 ${rider.name}` : '广播消息给全体在岗骑手'"
    width="520px"
    append-to-body
    @update:model-value="(value: boolean) => emit('update:modelValue', value)"
    @closed="reset"
  >
    <ElForm label-width="80px">
      <ElFormItem label="接收人">
        <span>{{ rider ? `${rider.name}（${rider.riderNo}）` : '全体在岗骑手' }}</span>
      </ElFormItem>
      <ElFormItem label="标题">
        <ElInput v-model="title" maxlength="40" show-word-limit placeholder="如：请尽快联系顾客" />
      </ElFormItem>
      <ElFormItem label="内容">
        <ElInput
          v-model="content"
          type="textarea"
          :rows="4"
          maxlength="500"
          show-word-limit
          placeholder="骑手端会在消息中心收到，紧急消息建议开启语音播报"
        />
      </ElFormItem>
      <ElFormItem label="优先级">
        <ElRadioGroup v-model="priority">
          <ElRadioButton v-for="item in PRIORITIES" :key="item.value" :value="item.value">
            {{ item.label }}
          </ElRadioButton>
        </ElRadioGroup>
      </ElFormItem>
      <ElFormItem label="语音播报">
        <ElSwitch v-model="needVoice" />
        <span class="message-dialog__hint">开启后骑手端会朗读，骑行途中也能听到</span>
      </ElFormItem>
    </ElForm>

    <template #footer>
      <ElButton @click="emit('update:modelValue', false)">取消</ElButton>
      <ElButton type="primary" :loading="submitting" @click="submit">发送</ElButton>
    </template>
  </ElDialog>
</template>

<script setup lang="ts">
  import { ElMessage, ElMessageBox } from 'element-plus'
  import { broadcastMessage } from '@/api/delivery'

  defineOptions({ name: 'RiderMessageDialog' })

  const props = defineProps<{
    modelValue: boolean
    rider: { riderId: number; riderNo: string; name: string } | null
  }>()

  const emit = defineEmits<{
    (event: 'update:modelValue', value: boolean): void
    (event: 'done'): void
  }>()

  const PRIORITIES = [
    { label: '普通', value: 'NORMAL' },
    { label: '重要', value: 'HIGH' },
    { label: '紧急', value: 'URGENT' }
  ]

  const title = ref('')
  const content = ref('')
  const priority = ref('NORMAL')
  const needVoice = ref(false)
  const submitting = ref(false)

  const reset = () => {
    title.value = ''
    content.value = ''
    priority.value = 'NORMAL'
    needVoice.value = false
  }

  const submit = async () => {
    if (!title.value.trim() || !content.value.trim()) {
      ElMessage.warning('请填写标题和内容')
      return
    }
    try {
      await ElMessageBox.confirm(
        props.rider ? `确认发送给 ${props.rider.name}？` : '确认向全体在岗骑手广播这条消息？',
        '发送消息',
        { type: 'warning', confirmButtonText: '确认发送', cancelButtonText: '取消' }
      )
      submitting.value = true
      await broadcastMessage({
        title: title.value.trim(),
        content: content.value.trim(),
        riderIds: props.rider ? [props.rider.riderId] : undefined,
        priority: priority.value,
        needVoice: needVoice.value
      })
      ElMessage.success('消息已发送')
      emit('update:modelValue', false)
      emit('done')
    } catch (error) {
      if (error !== 'cancel' && error !== 'close') {
        ElMessage.error(error instanceof Error ? error.message : '消息发送失败')
      }
    } finally {
      submitting.value = false
    }
  }
</script>

<style scoped lang="scss">
  .message-dialog__hint {
    margin-left: 10px;
    color: var(--art-gray-600);
    font-size: 12px;
  }
</style>
