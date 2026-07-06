<template>
  <div class="fresh-page">
    <div class="fresh-page__head">
      <div>
        <h1 class="fresh-page__title">售后退款</h1>
        <p class="fresh-page__desc">审核用户退款申请，通过后会走后端微信退款流程。</p>
      </div>
      <ElButton type="primary" :loading="loading" @click="loadRefunds">刷新</ElButton>
    </div>

    <ElCard class="fresh-card" shadow="never">
      <ElTable v-loading="loading" :data="refunds" border empty-text="暂无退款申请">
        <ElTableColumn prop="refundNo" label="退款单号" min-width="180" />
        <ElTableColumn prop="orderId" label="订单ID" width="100" />
        <ElTableColumn label="退款金额" width="130">
          <template #default="{ row }">
            <span class="money">{{ money(row.refundAmount) }}</span>
          </template>
        </ElTableColumn>
        <ElTableColumn prop="reason" label="退款原因" min-width="220" show-overflow-tooltip />
        <ElTableColumn label="凭证" min-width="170">
          <template #default="{ row }">
            <div v-if="row.evidenceImages?.length" class="evidence-list">
              <ElImage
                v-for="image in row.evidenceImages"
                :key="image"
                class="evidence-thumb"
                :src="image"
                :preview-src-list="row.evidenceImages"
                fit="cover"
                preview-teleported
              />
            </div>
            <span v-else class="muted">未上传</span>
          </template>
        </ElTableColumn>
        <ElTableColumn label="状态" width="110">
          <template #default="{ row }">
            <ElTag :type="refundStatusTag(row.status)">{{ row.status }}</ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <ElButton
              size="small"
              type="success"
              :disabled="!canReview(row)"
              @click="approve(row)"
            >
              通过
            </ElButton>
            <ElButton
              size="small"
              type="danger"
              plain
              :disabled="!canReview(row)"
              @click="openReject(row)"
            >
              拒绝
            </ElButton>
          </template>
        </ElTableColumn>
      </ElTable>
    </ElCard>

    <ElDialog v-model="rejectVisible" title="拒绝退款" width="520px">
      <ElForm label-width="90px">
        <ElFormItem label="退款单号">
          <ElInput :model-value="currentRefund?.refundNo || ''" disabled />
        </ElFormItem>
        <ElFormItem label="拒绝理由" required>
          <ElInput
            v-model.trim="rejectReason"
            type="textarea"
            :rows="5"
            maxlength="200"
            show-word-limit
            placeholder="请填写清晰的拒绝说明，用户会根据该理由补充资料或重新申请。"
          />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="rejectVisible = false">取消</ElButton>
        <ElButton type="danger" :loading="submitting" @click="submitReject">提交拒绝</ElButton>
      </template>
    </ElDialog>
  </div>
</template>

<script setup lang="ts">
  import { ElMessage, ElMessageBox } from 'element-plus'
  import { approveRefund, getRefunds, rejectRefund, type Refund } from '@/api/admin'

  defineOptions({ name: 'FreshRefunds' })

  const loading = ref(false)
  const submitting = ref(false)
  const rejectVisible = ref(false)
  const refunds = ref<Refund[]>([])
  const currentRefund = ref<Refund | null>(null)
  const rejectReason = ref('')

  const money = (value: number) => `￥${(Number(value || 0) / 100).toFixed(2)}`
  const canReview = (row: Refund) => ['待审核', '退款中'].includes(row.status)

  const refundStatusTag = (value: string) => {
    if (value === '退款成功') return 'success'
    if (value === '已拒绝') return 'danger'
    return 'warning'
  }

  const loadRefunds = async () => {
    loading.value = true
    try {
      const result = await getRefunds()
      refunds.value = result.items || []
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '退款列表加载失败')
    } finally {
      loading.value = false
    }
  }

  const approve = async (row: Refund) => {
    await ElMessageBox.confirm(`确认通过退款「${row.refundNo}」？`, '审核退款', {
      type: 'warning',
      confirmButtonText: '通过退款',
      cancelButtonText: '取消'
    })
    try {
      await approveRefund(row.id)
      await loadRefunds()
      ElMessage.success('退款已通过')
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '退款审核失败')
    }
  }

  const openReject = (row: Refund) => {
    currentRefund.value = row
    rejectReason.value = ''
    rejectVisible.value = true
  }

  const submitReject = async () => {
    if (!currentRefund.value) return
    if (!rejectReason.value) {
      ElMessage.warning('请填写拒绝理由')
      return
    }
    submitting.value = true
    try {
      await rejectRefund(currentRefund.value.id, rejectReason.value)
      rejectVisible.value = false
      await loadRefunds()
      ElMessage.success('退款已拒绝')
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '拒绝退款失败')
    } finally {
      submitting.value = false
    }
  }

  onMounted(loadRefunds)
</script>

<style scoped lang="scss">
  @use '../style.scss';

  .evidence-list {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
  }

  .evidence-thumb {
    width: 46px;
    height: 46px;
    overflow: hidden;
    border-radius: 8px;
  }
</style>
