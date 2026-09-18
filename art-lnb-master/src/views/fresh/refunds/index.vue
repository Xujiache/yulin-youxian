<template>
  <div class="fresh-page">
    <div class="fresh-page__head">
      <div>
        <h1 class="fresh-page__title">售后退款</h1>
        <p class="fresh-page__desc"
          >审核用户申请、调整待审核金额，或按账号 ID 和订单 ID 主动发起退款。</p
        >
      </div>
      <div class="head-actions">
        <ElButton type="primary" plain @click="openCreateRefund">主动退款</ElButton>
        <ElButton type="primary" :loading="loading" @click="loadRefunds">刷新</ElButton>
      </div>
    </div>

    <ElCard class="fresh-card" shadow="never">
      <div class="fresh-toolbar">
        <div class="fresh-toolbar__left">
          <ElInput
            v-model.trim="filters.accountId"
            clearable
            placeholder="账号 ID"
            style="width: 180px"
          />
          <ElInput
            v-model.trim="filters.keyword"
            clearable
            placeholder="订单 ID、订单号或退款单号"
            style="width: 240px"
          />
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
              <ElButton v-if="canEditAmount(row)" link type="primary" @click="openAmount(row)"
                >修改</ElButton
              >
            </div>
          </template>
        </ElTableColumn>
        <ElTableColumn prop="reason" label="退款原因" min-width="220" show-overflow-tooltip />
        <ElTableColumn label="退款商品" min-width="230">
          <template #default="{ row }">
            <div v-if="row.items?.length" class="refund-item-summary">
              <div v-for="item in row.items" :key="item.orderItemId">
                {{ item.productName }} {{ item.specificationText }} · {{ money(item.refundAmount) }}
              </div>
            </div>
            <span v-else class="muted">整单/历史退款（未指定商品）</span>
          </template>
        </ElTableColumn>
        <ElTableColumn label="凭证" min-width="170">
          <template #default="{ row }">
            <div v-if="row.evidenceImages?.length" class="evidence-list">
              <FreshImage
                v-for="image in evidenceUrls(row)"
                :key="image"
                class="evidence-thumb"
                :src="image"
                fit="cover"
              />
            </div>
            <span v-else class="muted">未上传</span>
          </template>
        </ElTableColumn>
        <ElTableColumn label="状态" width="110">
          <template #default="{ row }">
            <ElTooltip v-if="row.failureMessage" :content="row.failureMessage" placement="top">
              <ElTag :type="refundStatusTag(row.status)">{{ row.status }}</ElTag>
            </ElTooltip>
            <ElTag v-else :type="refundStatusTag(row.status)">{{ row.status }}</ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="创建时间" width="170">
          <template #default="{ row }">{{ dateTime(row.createdAt) }}</template>
        </ElTableColumn>
        <ElTableColumn label="操作" width="300" fixed="right">
          <template #default="{ row }">
            <ElButton size="small" type="success" :disabled="!canReview(row)" @click="approve(row)"
              >通过</ElButton
            >
            <ElButton
              size="small"
              type="danger"
              plain
              :disabled="!canReview(row)"
              @click="openReject(row)"
              >拒绝</ElButton
            >
            <ElButton
              v-if="needsStatusSync(row)"
              size="small"
              type="primary"
              plain
              @click="syncRefundStatus(row)"
              >同步微信状态</ElButton
            >
            <ElButton
              v-else-if="row.status === '退款失败'"
              size="small"
              type="warning"
              plain
              @click="retry(row)"
              >重新发起</ElButton
            >
            <ElButton
              v-if="canRestoreOrder(row)"
              size="small"
              type="info"
              plain
              @click="restoreOrder(row)"
              >确认未退款并恢复订单</ElButton
            >
          </template>
        </ElTableColumn>
      </ElTable>
    </ElCard>

    <ElDialog
      v-model="createVisible"
      title="按商品主动退款"
      width="min(900px, 96vw)"
      destroy-on-close
      :close-on-click-modal="!submitting"
      :close-on-press-escape="!submitting"
      :show-close="!submitting"
      @closed="clearVerifiedOrder"
    >
      <ElForm label-width="108px" :disabled="submitting">
        <ElFormItem label="账号 ID" required>
          <div class="inline-field">
            <ElInput
              v-model.trim="createForm.accountId"
              placeholder="输入客户账号 ID"
              @input="clearVerifiedOrder"
            />
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
        <ElFormItem label="订单" required>
          <div class="inline-field">
            <ElInput
              v-model.trim="createForm.orderKeyword"
              placeholder="输入订单 ID 或订单号"
              @input="clearVerifiedOrder"
              @keyup.enter="verifyOrder"
            />
            <ElButton :loading="verifyingOrder" @click="verifyOrder">查找订单</ElButton>
          </div>
        </ElFormItem>
        <ElAlert
          v-if="verifiedOrder"
          class="form-alert"
          type="info"
          :closable="false"
          :title="`订单 ${verifiedOrder.orderNo}，实付 ${money(verifiedOrder.paidAmount)}，已退款 ${money(verifiedOrder.refundedAmount)}`"
        />
        <ElFormItem v-if="verifiedOrder" label="退款商品" required>
          <div class="refund-products">
            <p class="muted"
              >勾选需要退款的商品，可单独调整退款金额；金额已扣除优惠及该商品已退/处理中金额，不含配送费和包装费。</p
            >
            <ElAlert
              v-if="hasUnallocatedRefunds"
              type="warning"
              :closable="false"
              title="此订单有未关联商品的整单或历史退款，请核对后操作；合计仍受订单剩余可退金额限制。"
            />
            <ElTable :data="refundableProducts" border empty-text="该订单没有可退款商品">
              <ElTableColumn label="选择" width="58">
                <template #default="{ row }"
                  ><ElCheckbox
                    v-model="row.selected"
                    :disabled="row.availableAmount <= 0 || submitting"
                    :aria-label="`退款 ${row.item.productName}`"
                /></template>
              </ElTableColumn>
              <ElTableColumn label="商品 / 规格" min-width="190">
                <template #default="{ row }">
                  <div class="refund-product-info">
                    <FreshImage :src="row.item.imageUrl" class="refund-product-image" fit="cover" />
                    <div
                      ><strong>{{ row.item.productName }}</strong
                      ><div class="muted"
                        >{{ row.item.specificationText || row.item.saleUnit }} · 购买数量
                        {{ row.item.quantity }}</div
                      ></div
                    >
                  </div>
                </template>
              </ElTableColumn>
              <ElTableColumn label="剩余可退" width="105">
                <template #default="{ row }">{{ money(row.availableAmount) }}</template>
              </ElTableColumn>
              <ElTableColumn label="本次退款（元）" width="185">
                <template #default="{ row }">
                  <ElInputNumber
                    v-if="row.availableAmount > 0"
                    :key="`${row.item.id}-${row.selected}-${submitting}`"
                    v-model="row.amountYuan"
                    :min="0.01"
                    :max="centToYuan(row.availableAmount)"
                    :precision="2"
                    :step="0.01"
                    :disabled="!row.selected || submitting"
                    controls-position="right"
                  />
                  <span v-else class="muted">暂无可退金额</span>
                </template>
              </ElTableColumn>
            </ElTable>
          </div>
        </ElFormItem>
        <ElFormItem label="退款合计">
          <strong class="money">{{ money(selectedRefundAmount) }}</strong>
          <span class="refund-total-note"
            >已选 {{ selectedProducts.length }} 项 · 订单剩余可退
            {{ money(orderAvailableAmount) }}</span
          >
          <span v-if="selectedRefundAmount > orderAvailableAmount" class="refund-over-limit"
            >合计超过剩余可退金额，请调低所选商品金额</span
          >
        </ElFormItem>
        <ElFormItem label="退款原因" required>
          <div class="reason-shortcuts">
            <ElButton
              v-for="reason in refundReasons"
              :key="reason"
              size="small"
              :type="createForm.reason === reason ? 'primary' : 'default'"
              @click="createForm.reason = reason"
              >{{ reason }}</ElButton
            >
          </div>
          <ElInput
            v-model.trim="createForm.reason"
            type="textarea"
            :rows="4"
            maxlength="200"
            show-word-limit
            placeholder="可选择快捷原因后补充，也可直接填写；完整原因会同步显示给用户"
          />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton :disabled="submitting" @click="createVisible = false">取消</ElButton>
        <ElButton
          type="primary"
          :loading="submitting"
          :disabled="!verifiedOrder || verifyingOrder || !selectedProducts.length"
          @click="submitAdminRefund"
          >确认并发起退款</ElButton
        >
      </template>
    </ElDialog>

    <ElDialog v-model="amountVisible" title="修改退款金额" width="460px">
      <ElForm label-width="110px">
        <ElFormItem label="退款单号">
          <ElInput :model-value="currentRefund?.refundNo || ''" disabled />
        </ElFormItem>
        <ElFormItem label="退款金额（元）" required>
          <ElInputNumber
            v-model="amountYuan"
            :min="0.01"
            :precision="2"
            :step="1"
            class="form-full"
          />
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
  import FreshImage from '@/components/business/fresh-image/index.vue'
  import { ElMessage, ElMessageBox } from 'element-plus'
  import {
    approveRefund,
    createAdminRefund,
    findOrder,
    getRefundableItems,
    getRefunds,
    reconcileRefund,
    rejectRefund,
    retryRefund,
    searchCustomers,
    updateRefundAmount,
    type AdminCustomer,
    type OrderDetail,
    type RefundableItems,
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
  const filters = reactive({ accountId: '', keyword: '' })
  const createForm = reactive({ accountId: '', orderKeyword: '', reason: '' })
  type RefundProduct = RefundableItems['items'][number] & { selected: boolean; amountYuan: number }
  const refundableProducts = ref<RefundProduct[]>([])
  const orderAvailableAmount = ref(0)
  const hasUnallocatedRefunds = ref(false)
  const refundReasons = [
    '商品损坏',
    '没货',
    '商品不新鲜',
    '漏送商品',
    '错送商品',
    '商品质量问题',
    '协商退款'
  ]
  const selectedProducts = computed(() => refundableProducts.value.filter((item) => item.selected))
  const selectedRefundAmount = computed(() =>
    selectedProducts.value.reduce(
      (sum, item) => sum + Math.round(Number(item.amountYuan || 0) * 100),
      0
    )
  )
  let orderLookupSequence = 0
  let lastRefundSignature = ''
  let refundRequestKey = ''
  const clearVerifiedOrder = () => {
    orderLookupSequence++
    verifiedOrder.value = null
    refundableProducts.value = []
    orderAvailableAmount.value = 0
    hasUnallocatedRefunds.value = false
    verifyingOrder.value = false
    lastRefundSignature = ''
    refundRequestKey = ''
  }

  const positiveId = (value: string) => {
    const result = Number(String(value || '').trim())
    return Number.isInteger(result) && result > 0 ? result : 0
  }
  const centToYuan = (value: number) => Number((Number(value || 0) / 100).toFixed(2))
  const yuanToCent = (value: number) => Math.max(1, Math.round(Number(value || 0) * 100))
  const money = (value: number) => `￥${centToYuan(value).toFixed(2)}`
  const canReview = (row: Refund) => row.status === '待审核'
  const canEditAmount = (row: Refund) => row.status === '待审核' && !row.items?.length
  const sourceText = (value: string) => (value === 'ADMIN' ? '管理员发起' : '用户申请')
  const dateTime = (value: string) => (value ? value.replace('T', ' ').slice(0, 19) : '--')
  const evidenceUrls = (row: Refund) => (row.evidenceImages || []).map(resolveFreshAssetUrl)

  const refundStatusTag = (value: string) => {
    if (value === '退款成功') return 'success'
    if (value === '已拒绝') return 'danger'
    if (value === '退款中') return 'primary'
    return 'warning'
  }
  const needsStatusSync = (row: Refund) =>
    row.status === '退款失败' &&
    (row.failureCode === 'REFUND_REQUEST_MISMATCH' ||
      row.failureCode === 'REFUND_STATUS_UNCONFIRMED' ||
      row.failureCode === 'REFUND_IDENTITY_MISMATCH' ||
      row.failureMessage?.includes('订单金额或退款金额与之前请求不一致'))
  const canRestoreOrder = (row: Refund) =>
    row.status === '退款失败' && ['CLOSED', 'ABNORMAL'].includes(row.failureCode || '')

  const loadRefunds = async () => {
    loading.value = true
    try {
      const userId = positiveId(filters.accountId)
      const result = await getRefunds({
        ...(userId ? { userId } : {}),
        ...(filters.keyword ? { keyword: filters.keyword } : {}),
        page: 1,
        pageSize: 100
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
    filters.keyword = ''
    loadRefunds()
  }

  const approve = async (row: Refund) => {
    try {
      await ElMessageBox.confirm(
        `确认按 ${money(row.refundAmount)} 通过退款「${row.refundNo}」？`,
        '审核退款',
        {
          type: 'warning',
          confirmButtonText: '通过退款',
          cancelButtonText: '取消'
        }
      )
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

  const retry = async (row: Refund) => {
    try {
      await ElMessageBox.confirm(
        `将重新向微信发起退款「${row.refundNo}」，确认继续？`,
        '重新发起退款',
        { type: 'warning', confirmButtonText: '重新发起', cancelButtonText: '取消' }
      )
    } catch {
      return
    }
    try {
      await retryRefund(row.id)
      await loadRefunds()
      ElMessage.success('退款已重新提交，请稍后刷新确认结果')
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '重新发起退款失败')
    }
  }

  const syncRefundStatus = async (row: Refund) => {
    try {
      await ElMessageBox.confirm(
        `仅向微信查询退款单「${row.refundNo}」的真实状态，不会再次发起退款。是否继续？`,
        '同步微信退款状态',
        { type: 'info', confirmButtonText: '查询微信状态', cancelButtonText: '取消' }
      )
    } catch {
      return
    }
    try {
      const result = await reconcileRefund(row.id)
      await loadRefunds()
      ElMessage.success(
        result.status === '退款成功' ? '微信已确认退款成功' : `微信当前状态：${result.status}`
      )
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '微信退款状态查询失败')
    }
  }

  const restoreOrder = async (row: Refund) => {
    try {
      await ElMessageBox.confirm(
        `微信已确认退款未成功。确认后将把订单恢复为退款前状态，退款单「${row.refundNo}」将标记为已拒绝。`,
        '确认未退款并恢复订单',
        { type: 'warning', confirmButtonText: '确认恢复', cancelButtonText: '取消' }
      )
    } catch {
      return
    }
    try {
      await rejectRefund(row.id, '微信已确认退款未成功，恢复订单处理')
      await loadRefunds()
      ElMessage.success('订单已恢复为退款前状态')
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '恢复订单失败')
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
    Object.assign(createForm, { accountId: '', orderKeyword: '', reason: '' })
    matchedCustomer.value = null
    clearVerifiedOrder()
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
    clearVerifiedOrder()
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
    const keyword = createForm.orderKeyword.trim()
    if (!keyword) {
      ElMessage.warning('请输入订单 ID 或订单号')
      return
    }
    clearVerifiedOrder()
    const sequence = orderLookupSequence
    verifyingOrder.value = true
    try {
      const order = await findOrder(keyword)
      if (sequence !== orderLookupSequence || !createVisible.value) return
      const enteredUserId = positiveId(createForm.accountId)
      if (enteredUserId && order.userId !== enteredUserId) {
        ElMessage.error('该订单不属于当前账号')
        return
      }
      if (Number(order.paidAmount || 0) <= 0) {
        ElMessage.error('该订单尚未支付，不能退款')
        return
      }
      if (!matchedCustomer.value || matchedCustomer.value.userId !== order.userId) {
        const customers = await searchCustomers(order.userId)
        if (sequence !== orderLookupSequence || !createVisible.value) return
        matchedCustomer.value = customers.items?.[0] || {
          userId: order.userId,
          nickName: '该订单客户',
          avatarUrl: '',
          orderCount: 0
        }
      }
      const quote = await getRefundableItems(order.id)
      if (sequence !== orderLookupSequence || !createVisible.value) return
      createForm.accountId = String(order.userId)
      verifiedOrder.value = order
      orderAvailableAmount.value = quote.availableAmount
      hasUnallocatedRefunds.value = quote.hasUnallocatedRefunds
      refundableProducts.value = quote.items.map((item) => ({
        ...item,
        selected: false,
        amountYuan: centToYuan(item.availableAmount)
      }))
    } catch (error) {
      if (sequence === orderLookupSequence)
        ElMessage.error(error instanceof Error ? error.message : '订单验证失败')
    } finally {
      if (sequence === orderLookupSequence) verifyingOrder.value = false
    }
  }

  const submitAdminRefund = async () => {
    if (submitting.value) return
    if (!verifiedOrder.value || !matchedCustomer.value) {
      ElMessage.warning('请先查找并确认订单')
      return
    }
    if (!selectedProducts.value.length || !createForm.reason.trim()) {
      ElMessage.warning('请选择退款商品并填写退款原因')
      return
    }
    if (
      selectedRefundAmount.value > orderAvailableAmount.value ||
      selectedProducts.value.some(
        (item) =>
          !Number.isFinite(item.amountYuan) ||
          yuanToCent(item.amountYuan) > item.availableAmount ||
          item.amountYuan <= 0
      )
    ) {
      ElMessage.warning('请填写有效金额，且不能超过商品或订单剩余可退金额')
      return
    }
    const payload = {
      userId: verifiedOrder.value.userId,
      orderId: verifiedOrder.value.id,
      refundAmount: selectedRefundAmount.value,
      reason: createForm.reason.trim(),
      items: selectedProducts.value.map((item) => ({
        orderItemId: item.item.id,
        refundAmount: yuanToCent(item.amountYuan)
      }))
    }
    const summary = selectedProducts.value
      .map(
        (item) =>
          `${item.item.productName} ${item.item.specificationText || ''}（${money(yuanToCent(item.amountYuan))}）`
      )
      .join('、')
    const signature = JSON.stringify(payload)
    if (signature !== lastRefundSignature) {
      lastRefundSignature = signature
      refundRequestKey = crypto.randomUUID()
    }
    submitting.value = true
    try {
      await ElMessageBox.confirm(
        `将为订单 ${verifiedOrder.value.orderNo} 退还：${summary}。合计 ${money(payload.refundAmount)}，原因：${payload.reason}。确认后立即提交微信退款。`,
        '确认主动退款',
        {
          type: 'warning',
          confirmButtonText: '确认退款',
          cancelButtonText: '取消'
        }
      )
    } catch {
      submitting.value = false
      return
    }
    try {
      const result = await createAdminRefund({ ...payload, requestKey: refundRequestKey })
      createVisible.value = false
      await loadRefunds()
      if (result.status === '退款成功') ElMessage.success('微信已确认退款成功')
      else if (result.status === '退款失败')
        ElMessage.warning(result.failureMessage || '退款未成功，请在列表查看原因')
      else ElMessage.success('退款已提交，到账状态请查看退款记录')
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

  .refund-products {
    width: 100%;
  }
  .refund-product-info {
    display: flex;
    align-items: center;
    gap: 10px;
  }
  .refund-product-image {
    width: 44px;
    height: 44px;
    flex: 0 0 44px;
    border-radius: 6px;
  }
  .refund-products .el-alert {
    margin-bottom: 12px;
  }
  .refund-products .el-input-number {
    width: 150px;
  }
  .reason-shortcuts {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    margin-bottom: 12px;
  }
  .reason-shortcuts .el-button {
    margin-left: 0;
  }
  .refund-total-note {
    margin-left: 12px;
    color: var(--art-text-gray-600);
  }
  .refund-over-limit {
    width: 100%;
    color: var(--el-color-danger);
  }
  .refund-item-summary {
    line-height: 1.7;
  }

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
