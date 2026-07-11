<template>
  <div class="fresh-page">
    <div class="fresh-page__head">
      <div>
        <h1 class="fresh-page__title">售后退款</h1>
        <p class="fresh-page__desc">审核用户申请、调整待审核金额，或按账号 ID 和订单 ID 主动发起退款。</p>
      </div>
      <div class="head-actions">
        <ElButton type="primary" plain @click="openCreateRefund">主动退款</ElButton>
        <ElButton type="primary" :loading="loading" @click="loadRefunds">刷新</ElButton>
      </div>
    </div>

    <ElCard class="fresh-card" shadow="never">
      <div class="fresh-toolbar">
        <div class="fresh-toolbar__left">
          <ElInput v-model.trim="filters.accountId" clearable placeholder="账号 ID" style="width: 180px" />
          <ElInput v-model.trim="filters.orderId" clearable placeholder="订单 ID" style="width: 180px" />
          <ElButton type="primary" plain @click="loadRefunds">查询</ElButton>
          <ElButton @click="resetFilters">重置</ElButton>
        </div>
      </div>

      <ElTable v-loading="loading" :data="refunds" border empty-text="暂无退款记录">
        <ElTableColumn prop="refundNo" label="退款单号" min-width="160" />
        <ElTableColumn prop="userId" label="账号 ID" width="110" />
        <ElTableColumn label="订单" min-width="190">
          <template #default="{ row }">
            <div class="order-cell">
              <strong>ID：{{ row.orderId }}</strong>
              <span>{{ row.orderNo || '未记录订单编号' }}</span>
            </div>
          </template>
        </ElTableColumn>
        <ElTableColumn label="发起来源" width="110">
          <template #default="{ row }">
            <ElTag :type="row.source === 'ADMIN' ? 'primary' : 'info'" effect="light">
              {{ sourceText(row.source) }}
            </ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="退款金额" width="160">
          <template #default="{ row }">
            <div class="amount-cell">
              <span class="money">{{ money(row.refundAmount) }}</span>
              <ElButton v-if="canEditAmount(row)" link type="primary" @click="openAmount(row)">修改</ElButton>
            </div>
          </template>
        </ElTableColumn>
        <ElTableColumn prop="reason" label="退款原因" min-width="220" show-overflow-tooltip />
        <ElTableColumn label="凭证" min-width="170">
          <template #default="{ row }">
            <div v-if="row.evidenceImages?.length" class="evidence-list">
              <ElImage
                v-for="image in evidenceUrls(row)"
                :key="image"
                class="evidence-thumb"
                :src="image"
                :preview-src-list="evidenceUrls(row)"
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
        <ElTableColumn label="创建时间" width="170">
          <template #default="{ row }">{{ dateTime(row.createdAt) }}</template>
        </ElTableColumn>
        <ElTableColumn label="操作" width="170" fixed="right">
          <template #default="{ row }">
            <ElButton size="small" type="success" :disabled="!canReview(row)" @click="approve(row)">通过</ElButton>
            <ElButton size="small" type="danger" plain :disabled="!canReview(row)" @click="openReject(row)">拒绝</ElButton>
          </template>
        </ElTableColumn>
      </ElTable>
    </ElCard>

    <ElDialog v-model="createVisible" title="管理员主动退款" width="620px" destroy-on-close>
      <ElForm label-width="108px">
        <ElFormItem label="账号 ID" required>
          <div class="inline-field">
            <ElInput v-model.trim="createForm.accountId" placeholder="输入客户账号 ID" />
            <ElButton :loading="searchingCustomer" @click="searchAccount">搜索账号</ElButton>
          </div>
        </ElFormItem>
        <ElAlert
          v-if="matchedCustomer"
          class="form-alert"
          type="success"
          :closable="false"
          :title="`已找到：${matchedCustomer.nickName}（账号 ${matchedCustomer.userId}，共 ${matchedCustomer.orderCount} 个订单）`"
        />
        <ElFormItem label="订单 ID" required>
          <div class="inline-field">
            <ElInput v-model.trim="createForm.orderId" placeholder="输入该账号的订单 ID" />
            <ElButton :disabled="!matchedCustomer" :loading="verifyingOrder" @click="verifyOrder">验证订单</ElButton>
          </div>
        </ElFormItem>
        <ElAlert
          v-if="verifiedOrder"
          class="form-alert"
          type="info"
          :closable="false"
          :title="`订单 ${verifiedOrder.orderNo}，实付 ${money(verifiedOrder.paidAmount)}，已退款 ${money(verifiedOrder.refundedAmount)}`"
        />
        <ElFormItem label="退款金额（元）" required>
          <ElInputNumber
            v-model="createForm.refundAmountYuan"
            :min="0.01"
            :precision="2"
            :step="1"
            class="form-full"
          />
        </ElFormItem>
        <ElFormItem label="退款原因" required>
          <ElInput
            v-model.trim="createForm.reason"
            type="textarea"
            :rows="4"
            maxlength="200"
            show-word-limit
            placeholder="该原因会同步显示给用户"
          />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="createVisible = false">取消</ElButton>
        <ElButton type="primary" :loading="submitting" @click="submitAdminRefund">确认并发起退款</ElButton>
      </template>
    </ElDialog>

    <ElDialog v-model="amountVisible" title="修改退款金额" width="460px">
      <ElForm label-width="110px">
        <ElFormItem label="退款单号">
          <ElInput :model-value="currentRefund?.refundNo || ''" disabled />
        </ElFormItem>
        <ElFormItem label="退款金额（元）" required>
          <ElInputNumber v-model="amountYuan" :min="0.01" :precision="2" :step="1" class="form-full" />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="amountVisible = false">取消</ElButton>
        <ElButton type="primary" :loading="submitting" @click="submitAmount">保存金额</ElButton>
      </template>
    </ElDialog>

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
            placeholder="请填写清晰的拒绝说明，用户端会显示这条理由。"
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
  import {
    approveRefund,
    createAdminRefund,
    getOrderDetail,
    getRefunds,
    rejectRefund,
    searchCustomers,
    updateRefundAmount,
    type AdminCustomer,
    type OrderDetail,
    type Refund
  } from '@/api/admin'
  import { resolveFreshAssetUrl } from '@/utils/fresh-assets'

  defineOptions({ name: 'FreshRefunds' })

  const loading = ref(false)
  const submitting = ref(false)
  const searchingCustomer = ref(false)
  const verifyingOrder = ref(false)
  const rejectVisible = ref(false)
  const amountVisible = ref(false)
  const createVisible = ref(false)
  const refunds = ref<Refund[]>([])
  const currentRefund = ref<Refund | null>(null)
  const rejectReason = ref('')
  const amountYuan = ref(0)
  const matchedCustomer = ref<AdminCustomer | null>(null)
  const verifiedOrder = ref<OrderDetail | null>(null)
  const filters = reactive({ accountId: '', orderId: '' })
  const createForm = reactive({ accountId: '', orderId: '', refundAmountYuan: 0, reason: '' })

  const positiveId = (value: string) => {
    const result = Number(String(value || '').trim())
    return Number.isInteger(result) && result > 0 ? result : 0
  }
  const centToYuan = (value: number) => Number((Number(value || 0) / 100).toFixed(2))
  const yuanToCent = (value: number) => Math.max(1, Math.round(Number(value || 0) * 100))
  const money = (value: number) => `￥${centToYuan(value).toFixed(2)}`
  const canReview = (row: Refund) => row.status === '待审核'
  const canEditAmount = (row: Refund) => row.status === '待审核'
  const sourceText = (value: string) => (value === 'ADMIN' ? '管理员发起' : '用户申请')
  const dateTime = (value: string) => (value ? value.replace('T', ' ').slice(0, 19) : '--')
  const evidenceUrls = (row: Refund) => (row.evidenceImages || []).map(resolveFreshAssetUrl)

  const refundStatusTag = (value: string) => {
    if (value === '退款成功') return 'success'
    if (value === '已拒绝') return 'danger'
    if (value === '退款中') return 'primary'
    return 'warning'
  }

  const loadRefunds = async () => {
    loading.value = true
    try {
      const userId = positiveId(filters.accountId)
      const orderId = positiveId(filters.orderId)
      const result = await getRefunds({
        ...(userId ? { userId } : {}),
        ...(orderId ? { orderId } : {})
      })
      refunds.value = result.items || []
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '退款列表加载失败')
    } finally {
      loading.value = false
    }
  }

  const resetFilters = () => {
    filters.accountId = ''
    filters.orderId = ''
    loadRefunds()
  }

  const approve = async (row: Refund) => {
    try {
      await ElMessageBox.confirm(`确认按 ${money(row.refundAmount)} 通过退款「${row.refundNo}」？`, '审核退款', {
        type: 'warning',
        confirmButtonText: '通过退款',
        cancelButtonText: '取消'
      })
    } catch {
      return
    }
    try {
      await approveRefund(row.id)
      await loadRefunds()
      ElMessage.success('退款已提交')
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '退款审核失败')
    }
  }

  const openAmount = (row: Refund) => {
    currentRefund.value = row
    amountYuan.value = centToYuan(row.refundAmount)
    amountVisible.value = true
  }

  const submitAmount = async () => {
    if (!currentRefund.value || amountYuan.value <= 0) return
    submitting.value = true
    try {
      await updateRefundAmount(currentRefund.value.id, yuanToCent(amountYuan.value))
      amountVisible.value = false
      await loadRefunds()
      ElMessage.success('退款金额已更新')
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '退款金额修改失败')
    } finally {
      submitting.value = false
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

  const openCreateRefund = () => {
    Object.assign(createForm, { accountId: '', orderId: '', refundAmountYuan: 0, reason: '' })
    matchedCustomer.value = null
    verifiedOrder.value = null
    createVisible.value = true
  }

  const searchAccount = async () => {
    const accountId = positiveId(createForm.accountId)
    if (!accountId) {
      ElMessage.warning('请输入正确的账号 ID')
      return
    }
    searchingCustomer.value = true
    matchedCustomer.value = null
    verifiedOrder.value = null
    try {
      const result = await searchCustomers(accountId)
      matchedCustomer.value = result.items?.[0] || null
      if (!matchedCustomer.value) ElMessage.warning('未找到该账号')
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '账号搜索失败')
    } finally {
      searchingCustomer.value = false
    }
  }

  const verifyOrder = async () => {
    const orderId = positiveId(createForm.orderId)
    if (!matchedCustomer.value || !orderId) {
      ElMessage.warning('请先搜索账号并填写订单 ID')
      return
    }
    verifyingOrder.value = true
    verifiedOrder.value = null
    try {
      const order = await getOrderDetail(orderId)
      if (order.userId !== matchedCustomer.value.userId) {
        ElMessage.error('该订单不属于当前账号')
        return
      }
      if (Number(order.paidAmount || 0) <= 0) {
        ElMessage.error('该订单尚未支付，不能退款')
        return
      }
      verifiedOrder.value = order
      const remaining = Math.max(Number(order.paidAmount || 0) - Number(order.refundedAmount || 0), 0)
      createForm.refundAmountYuan = centToYuan(remaining)
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '订单验证失败')
    } finally {
      verifyingOrder.value = false
    }
  }

  const submitAdminRefund = async () => {
    const userId = positiveId(createForm.accountId)
    const orderId = positiveId(createForm.orderId)
    if (!matchedCustomer.value || matchedCustomer.value.userId !== userId) {
      ElMessage.warning('请先搜索并确认账号')
      return
    }
    if (!verifiedOrder.value || verifiedOrder.value.id !== orderId) {
      ElMessage.warning('请先验证订单')
      return
    }
    if (createForm.refundAmountYuan <= 0 || !createForm.reason) {
      ElMessage.warning('请填写退款金额和原因')
      return
    }
    try {
      await ElMessageBox.confirm('确认后将立即提交微信退款，是否继续？', '确认主动退款', {
        type: 'warning',
        confirmButtonText: '确认退款',
        cancelButtonText: '取消'
      })
    } catch {
      return
    }
    submitting.value = true
    try {
      await createAdminRefund({
        userId,
        orderId,
        refundAmount: yuanToCent(createForm.refundAmountYuan),
        reason: createForm.reason
      })
      createVisible.value = false
      await loadRefunds()
      ElMessage.success('管理员退款已发起')
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '主动退款失败')
    } finally {
      submitting.value = false
    }
  }

  onMounted(loadRefunds)
</script>

<style scoped lang="scss">
  @use '../style.scss';

  .head-actions,
  .inline-field,
  .amount-cell,
  .evidence-list {
    display: flex;
    align-items: center;
    gap: 10px;
  }

  .inline-field {
    width: 100%;
  }

  .inline-field .el-input {
    flex: 1;
  }

  .form-alert {
    margin: -4px 0 18px 108px;
    width: calc(100% - 108px);
  }

  .order-cell {
    display: flex;
    flex-direction: column;
    gap: 4px;
  }

  .order-cell span {
    color: var(--art-text-gray-600);
    font-size: 12px;
  }

  .evidence-list {
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
