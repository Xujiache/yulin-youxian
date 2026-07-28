<template>
  <div class="fresh-page">
    <div class="fresh-page__head">
      <div>
        <h1 class="fresh-page__title">订单管理</h1>
        <p class="fresh-page__desc">按配送日期、区域和楼栋智能归组，减少往返配送。</p>
      </div>
      <ElButton type="primary" :loading="loading" @click="loadOrders">刷新订单</ElButton>
    </div>

    <ElCard class="fresh-card" shadow="never">
      <div class="order-filter">
        <div class="order-filter__head">
          <div>
            <strong>订单筛选</strong>
            <span>先缩小订单范围，再进行全选、备货或配送</span>
          </div>
          <ElButton link type="primary" @click="resetFilters">重置筛选</ElButton>
        </div>
        <div class="order-filter__status">
          <div class="order-filter__status-group">
            <span>订单状态</span>
            <ElSegmented v-model="status" :options="statuses" @change="loadOrders" />
          </div>
          <div class="order-filter__status-group">
            <span>打印状态</span>
            <ElSegmented v-model="printStatus" :options="printStatuses" @change="loadOrders" />
          </div>
        </div>
        <div class="order-filter__fields">
          <ElInput
            v-model="keyword"
            clearable
            placeholder="搜索订单号、收货人、电话或地址"
            class="order-filter__keyword"
          />
          <ElDatePicker
            v-model="deliveryDate"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="全部配送日期"
            clearable
            @change="loadOrders"
          />
          <ElSelect
            v-model="filterDeliverySlots"
            multiple
            collapse-tags
            collapse-tags-tooltip
            clearable
            placeholder="全部配送时间段"
          >
            <ElOption v-for="slot in deliverySlots" :key="slot" :label="slot" :value="slot" />
          </ElSelect>
          <ElSelect
            v-model="filterDeliveryAreas"
            multiple
            collapse-tags
            collapse-tags-tooltip
            clearable
            placeholder="全部配送区域"
          >
            <ElOption v-for="area in deliveryAreas" :key="area" :label="area" :value="area" />
          </ElSelect>
          <ElSelect
            v-model="filterDeliveryBuildings"
            multiple
            collapse-tags
            collapse-tags-tooltip
            clearable
            placeholder="全部楼栋"
          >
            <ElOption
              v-for="building in deliveryBuildings"
              :key="building"
              :label="building"
              :value="building"
            />
          </ElSelect>
        </div>
        <div class="order-filter__footer">
          <span>
            当前显示 <b>{{ filteredOrders.length }}</b> / {{ orders.length }} 单， 共
            {{ groupCount }} 个配送分组
          </span>
          <div class="delivery-toolbar__summary">
            <span class="delivery-toolbar__signal"></span>
            <strong>智能配送顺序已开启</strong>
          </div>
        </div>
      </div>

      <div v-if="selectableOrders.length > 0" class="batch-toolbar">
        <div class="batch-toolbar__selection">
          <strong>批量处理</strong>
          <ElCheckbox
            :model-value="isAllSelected"
            :indeterminate="selectedOrderIds.length > 0 && !isAllSelected"
            @change="toggleSelectAll"
          >
            全选筛选结果（{{ selectableOrders.length }}）
          </ElCheckbox>
          <ElButton
            plain
            :disabled="filteredPrepareIds.length === 0"
            @click="selectEligibleOrders('prepare')"
          >
            只选可备货（{{ filteredPrepareIds.length }}）
          </ElButton>
          <ElButton
            plain
            :disabled="filteredDeliverIds.length === 0"
            @click="selectEligibleOrders('deliver')"
          >
            只选可配送（{{ filteredDeliverIds.length }}）
          </ElButton>
          <span class="batch-toolbar__count">已选 {{ selectedOrderIds.length }} 单</span>
        </div>
        <div class="batch-toolbar__actions">
          <ElButton
            type="warning"
            plain
            :disabled="selectedPrepareIds.length === 0"
            :loading="batchPreparing"
            @click="handleBatchAction('prepare')"
          >
            批量备货（{{ selectedPrepareIds.length }}）
          </ElButton>
          <ElButton
            type="success"
            :disabled="selectedDeliverIds.length === 0"
            :loading="batchDelivering"
            @click="handleBatchAction('deliver')"
          >
            批量配送（{{ selectedDeliverIds.length }}）
          </ElButton>
          <ElButton
            type="primary"
            :disabled="selectedPrintableIds.length === 0"
            :loading="batchPrinting"
            @click="handleBatchPrint"
          >
            批量打印（{{ selectedPrintableIds.length }}）
          </ElButton>
          <ElButton
            :disabled="selectedWechatExportIds.length === 0"
            :loading="wechatExporting"
            @click="handleWechatShipmentExport"
          >
            导出微信发货单（{{ selectedWechatExportIds.length }}）
          </ElButton>
          <ElButton v-if="selectedOrderIds.length > 0" link @click="clearSelection"
            >清空选择</ElButton
          >
        </div>
      </div>
      <div v-if="selectedOrderIds.length > 0" class="batch-hint">
        智能识别：可备货 {{ selectedPrepareIds.length }} 单，可配送
        {{ selectedDeliverIds.length }} 单，可打印
        {{ selectedPrintableIds.length }} 单，可导出微信发货单
        {{ selectedWechatExportIds.length }} 单；不符合状态的订单会自动跳过。
      </div>

      <ElTable
        v-loading="loading"
        :data="filteredOrders"
        row-key="id"
        :span-method="spanMethod"
        :row-class-name="rowClassName"
        empty-text="暂无订单"
      >
        <ElTableColumn v-if="filteredOrders.length > 0" width="48" align="center" fixed="left">
          <template #default="{ row }">
            <ElCheckbox
              :model-value="selectedOrderIds.includes(row.id)"
              @change="toggleSelect(row.id)"
            />
          </template>
        </ElTableColumn>
        <ElTableColumn label="顺序" width="64" align="center" fixed="left">
          <template #default="{ row }">
            <span class="delivery-sequence">{{ row.deliverySequence }}</span>
          </template>
        </ElTableColumn>
        <ElTableColumn label="配送分组" width="210">
          <template #default="{ row }">
            <div class="delivery-group">
              <ElTag type="success" effect="light" size="small">{{ row.deliveryDate }}</ElTag>
              <strong>{{ row.deliveryArea }}</strong>
              <div class="delivery-group__building">
                <span>{{ row.deliveryBuilding }}</span>
                <b>{{ displayGroupCount(row) }} 单</b>
              </div>
              <small>同组订单已连续排列</small>
            </div>
          </template>
        </ElTableColumn>
        <ElTableColumn label="收货地址" min-width="300">
          <template #default="{ row }">
            <div class="address-cell">
              <strong>{{ fullAddress(row) }}</strong>
              <span
                >{{ row.address?.name || '未填写收货人' }} ·
                {{ row.address?.phone || '未填写电话' }}</span
              >
              <ElTag
                v-if="row.sameAddressOrderCount > 1"
                type="warning"
                size="small"
                effect="light"
              >
                同一门牌共 {{ row.sameAddressOrderCount }} 单
              </ElTag>
            </div>
          </template>
        </ElTableColumn>
        <ElTableColumn label="状态" width="110">
          <template #default="{ row }">
            <ElTag :type="statusTag(row.status)">{{ row.status }}</ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="打印状态" width="96">
          <template #default="{ row }">
            <ElTag :type="printStatusTag(row.printStatus)">
              {{ printStatusLabel(row.printStatus) }}
            </ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="订单信息" min-width="190">
          <template #default="{ row }">
            <div class="order-summary">
              <strong>{{ row.orderNo }}</strong>
              <span>{{ row.summary }}</span>
            </div>
          </template>
        </ElTableColumn>
        <ElTableColumn prop="deliverySlot" label="预约配送" width="235" />
        <ElTableColumn label="金额" width="100">
          <template #default="{ row }">
            <span class="money">{{ money(row.totalAmount) }}</span>
          </template>
        </ElTableColumn>
        <ElTableColumn label="操作" width="240" fixed="right">
          <template #default="{ row }">
            <div class="order-actions">
              <ElButton size="small" @click="openDetail(row.id)">详情</ElButton>
              <ElButton
                v-if="isSelectable(row)"
                size="small"
                type="success"
                plain
                :disabled="row.printStatus === 'PENDING'"
                :loading="printingOrderId === row.id"
                @click="handlePrintOne(row)"
              >
                {{
                  row.printStatus === 'PENDING'
                    ? '打印中'
                    : row.printStatus === 'SUCCESS'
                      ? '补打'
                      : '打印'
                }}
              </ElButton>
              <ElButton
                v-if="canAccept(row)"
                size="small"
                type="primary"
                @click="runAction(row.id, 'accept')"
              >
                接单
              </ElButton>
              <ElButton v-if="canDeliver(row)" size="small" @click="runAction(row.id, 'deliver')">
                配送
              </ElButton>
              <ElButton
                v-if="canComplete(row)"
                size="small"
                type="success"
                @click="runAction(row.id, 'complete')"
              >
                完成
              </ElButton>
              <ElButton
                v-if="canCancel(row)"
                size="small"
                type="danger"
                plain
                @click="runAction(row.id, 'cancel')"
              >
                取消
              </ElButton>
            </div>
          </template>
        </ElTableColumn>
      </ElTable>
    </ElCard>

    <ElDialog v-model="detailVisible" title="订单详情" width="760px">
      <ElDescriptions v-if="detail" :column="2" border>
        <ElDescriptionsItem label="订单号">{{ detail.orderNo }}</ElDescriptionsItem>
        <ElDescriptionsItem label="订单状态">{{ detail.status }}</ElDescriptionsItem>
        <ElDescriptionsItem label="收货人">
          {{ detail.address?.name }} {{ detail.address?.phone }}
        </ElDescriptionsItem>
        <ElDescriptionsItem label="配送时间">{{ detail.deliverySlot }}</ElDescriptionsItem>
        <ElDescriptionsItem label="收货地址" :span="2">
          {{ detail.address?.locationName }} {{ detail.address?.detail }}
        </ElDescriptionsItem>
        <ElDescriptionsItem label="用户备注" :span="2">
          {{ detail.remark || '无' }}
        </ElDescriptionsItem>
      </ElDescriptions>

      <ElTable v-if="detail" :data="detail.items" border style="margin-top: 16px">
        <ElTableColumn label="商品" min-width="220">
          <template #default="{ row }">
            <div class="order-item">
              <ElImage v-if="row.imageUrl" class="image-thumb" :src="row.imageUrl" fit="cover" />
              <div v-else class="image-thumb empty-thumb">无图</div>
              <div>
                <strong>{{ row.productName }}</strong>
                <div class="muted"
                  >{{ row.quantity }}{{ row.saleUnit }} x {{ money(row.unitPrice) }}</div
                >
              </div>
            </div>
          </template>
        </ElTableColumn>
        <ElTableColumn label="小计" width="120">
          <template #default="{ row }">
            <span class="money">{{ money(row.amount) }}</span>
          </template>
        </ElTableColumn>
      </ElTable>

      <div v-if="detail" class="amount-list">
        <div
          ><span>商品总额</span><strong>{{ money(detail.productAmount) }}</strong></div
        >
        <div
          ><span>配送费</span><strong>{{ money(detail.deliveryFee) }}</strong></div
        >
        <div
          ><span>包装费</span><strong>{{ money(detail.packageFee) }}</strong></div
        >
        <div
          ><span>实付金额</span
          ><strong class="money">{{
            money(detail.paidAmount || detail.payableAmount)
          }}</strong></div
        >
        <div
          ><span>已退款</span><strong>{{ money(detail.refundedAmount) }}</strong></div
        >
      </div>
    </ElDialog>
  </div>
</template>

<script setup lang="ts">
  import { ElMessage, ElMessageBox } from 'element-plus'
  import * as XLSX from 'xlsx'
  import {
    acceptOrder,
    batchDeliverOrders,
    batchPrepareOrders,
    batchPrintOrders,
    cancelOrder,
    completeOrder,
    deliverOrder,
    getDeliverySlots,
    getOrderDetail,
    getOrders,
    refreshOrderPaymentTransaction,
    type OrderDetail,
    type OrderSummary
  } from '@/api/admin'

  defineOptions({ name: 'FreshOrders' })

  const statuses = ['全部', '待支付', '待接单', '备货中', '配送中', '已完成', '售后']
  const printStatuses = ['全部', '未打印', '已打印', '打印失败']
  const status = ref('全部')
  const printStatus = ref('全部')
  const deliveryDate = ref('')
  const keyword = ref('')
  const filterDeliverySlots = ref<string[]>([])
  const filterDeliveryAreas = ref<string[]>([])
  const filterDeliveryBuildings = ref<string[]>([])
  const loading = ref(false)
  const batchPrinting = ref(false)
  const batchPreparing = ref(false)
  const batchDelivering = ref(false)
  const wechatExporting = ref(false)
  const printingOrderId = ref<number | null>(null)
  const detailVisible = ref(false)
  const orders = ref<OrderSummary[]>([])
  const configuredDeliverySlots = ref<string[]>([])
  const deliverySlotsLoaded = ref(false)
  const detail = ref<OrderDetail | null>(null)
  const selectedOrderIds = ref<number[]>([])

  const money = (value: number) => `￥${(Number(value || 0) / 100).toFixed(2)}`
  const wechatMerchantId = '1115409474'

  const downloadWechatShipmentSheet = (items: OrderDetail[]) => {
    if (items.length === 0) return
    const missing = items.filter((item) => !item.transactionId)
    if (missing.length > 0) {
      throw new Error(`有 ${missing.length} 个订单缺少微信交易单号，无法生成发货表格`)
    }
    const headers = [
      '交易单号',
      '商户单号',
      '商户号',
      '发货方式',
      '发货模式',
      '快递公司',
      '快递单号（多个快递单使用;分隔）',
      '是否完成发货',
      '是否重新发货',
      '商品信息'
    ]
    const rows = items.map((item) => [
      item.transactionId,
      item.orderNo,
      wechatMerchantId,
      '同城配送',
      '统一发货',
      '',
      '',
      '',
      '',
      item.items
        .map((product) => `${product.productName}*${Number(product.quantity)}${product.saleUnit}`)
        .join('；')
    ])
    const sheet = XLSX.utils.aoa_to_sheet([headers, ...rows])
    sheet['!cols'] = [
      { wch: 28 },
      { wch: 24 },
      { wch: 16 },
      { wch: 12 },
      { wch: 12 },
      { wch: 16 },
      { wch: 34 },
      { wch: 16 },
      { wch: 16 },
      { wch: 48 }
    ]
    const workbook = XLSX.utils.book_new()
    XLSX.utils.book_append_sheet(workbook, sheet, '发货单模板')
    const stamp = new Date().toISOString().replace(/[-:T]/g, '').slice(0, 14)
    XLSX.writeFile(workbook, `微信发货单-${stamp}.xls`, { bookType: 'biff8' })
  }

  const statusTag = (value: string) => {
    if (value === '已完成' || value === '退款成功') return 'success'
    if (value.includes('退款')) return 'warning'
    if (value === '已取消') return 'info'
    return 'primary'
  }

  const printStatusLabel = (status?: string) =>
    ({
      NONE: '未打印',
      PENDING: '打印中',
      SUCCESS: '已打印',
      FAILED: '打印失败'
    })[status || 'NONE']

  const printStatusTag = (status?: string) =>
    ({
      NONE: 'info',
      PENDING: 'warning',
      SUCCESS: 'success',
      FAILED: 'danger'
    })[status || 'NONE'] as 'success' | 'warning' | 'info' | 'danger' | undefined

  const printStatusMap: Record<string, string> = {
    全部: '',
    未打印: 'NONE',
    已打印: 'SUCCESS',
    打印失败: 'FAILED'
  }

  const isPrintableOrder = (order: OrderSummary) =>
    !['待支付', '已取消', '退款中', '已退款'].includes(order.status)

  const deliveryTimeRange = (value?: string) => {
    const match = String(value || '').match(/(\d{1,2}:\d{2})\s*[-~—至]\s*(\d{1,2}:\d{2})/)
    return match ? `${match[1]}-${match[2]}` : String(value || '').trim()
  }

  const deliverySlots = computed(() => {
    const source = deliverySlotsLoaded.value
      ? configuredDeliverySlots.value
      : orders.value.map((order) => order.deliverySlot)
    return [...new Set(source.map(deliveryTimeRange).filter(Boolean))].sort()
  })
  const deliveryAreas = computed(() =>
    [...new Set(orders.value.map((order) => order.deliveryArea).filter(Boolean))].sort()
  )
  const deliveryBuildings = computed(() =>
    [...new Set(orders.value.map((order) => order.deliveryBuilding).filter(Boolean))].sort()
  )
  const filteredOrders = computed(() => {
    const normalizedKeyword = keyword.value.trim().toLowerCase()
    const slotSet = new Set(filterDeliverySlots.value)
    const areaSet = new Set(filterDeliveryAreas.value)
    const buildingSet = new Set(filterDeliveryBuildings.value)
    return orders.value.filter((order) => {
      if (slotSet.size > 0 && !slotSet.has(deliveryTimeRange(order.deliverySlot))) return false
      if (areaSet.size > 0 && !areaSet.has(order.deliveryArea)) return false
      if (buildingSet.size > 0 && !buildingSet.has(order.deliveryBuilding)) return false
      if (!normalizedKeyword) return true
      const searchable = [
        order.orderNo,
        order.summary,
        order.address?.name,
        order.address?.phone,
        order.address?.locationName,
        order.address?.detail,
        order.deliveryArea,
        order.deliveryBuilding
      ]
        .filter(Boolean)
        .join(' ')
        .toLowerCase()
      return searchable.includes(normalizedKeyword)
    })
  })
  const groupCount = computed(
    () => new Set(filteredOrders.value.map((order) => order.deliveryGroupKey)).size
  )
  const displayGroupMeta = computed(() => {
    const grouped = new Map<string, OrderSummary[]>()
    filteredOrders.value.forEach((order) => {
      const group = grouped.get(order.deliveryGroupKey) || []
      group.push(order)
      grouped.set(order.deliveryGroupKey, group)
    })
    const meta = new Map<number, { position: number; count: number }>()
    grouped.forEach((group) => {
      group.forEach((order, index) => {
        meta.set(order.id, { position: index + 1, count: group.length })
      })
    })
    return meta
  })
  const selectableOrders = computed(() => filteredOrders.value)
  const filteredPrepareIds = computed(() =>
    filteredOrders.value
      .filter((order) => order.status === '已支付/待接单')
      .map((order) => order.id)
  )
  const filteredDeliverIds = computed(() =>
    filteredOrders.value.filter((order) => order.status === '备货中').map((order) => order.id)
  )
  const selectedOrders = computed(() =>
    filteredOrders.value.filter((order) => selectedOrderIds.value.includes(order.id))
  )
  const selectedPrepareIds = computed(() =>
    selectedOrders.value
      .filter((order) => order.status === '已支付/待接单')
      .map((order) => order.id)
  )
  const selectedDeliverIds = computed(() =>
    selectedOrders.value.filter((order) => order.status === '备货中').map((order) => order.id)
  )
  const selectedPrintableIds = computed(() =>
    selectedOrders.value.filter(isPrintableOrder).map((order) => order.id)
  )
  const selectedWechatExportIds = computed(() =>
    selectedOrders.value
      .filter((order) =>
        ['已支付/待接单', '备货中', '配送中', '已完成', '部分退款'].includes(order.status)
      )
      .map((order) => order.id)
  )

  const isAllSelected = computed(
    () =>
      selectableOrders.value.length > 0 &&
      selectableOrders.value.every((order) => selectedOrderIds.value.includes(order.id))
  )

  const toggleSelectAll = () => {
    if (isAllSelected.value) {
      selectedOrderIds.value = []
    } else {
      selectedOrderIds.value = selectableOrders.value.map((o) => o.id)
    }
  }

  const toggleSelect = (orderId: number) => {
    const index = selectedOrderIds.value.indexOf(orderId)
    if (index > -1) {
      selectedOrderIds.value.splice(index, 1)
    } else {
      selectedOrderIds.value.push(orderId)
    }
  }

  const isSelectable = isPrintableOrder

  const clearSelection = () => {
    selectedOrderIds.value = []
  }

  const selectEligibleOrders = (action: 'prepare' | 'deliver') => {
    selectedOrderIds.value =
      action === 'prepare' ? [...filteredPrepareIds.value] : [...filteredDeliverIds.value]
    ElMessage.success(
      `已选中 ${selectedOrderIds.value.length} 个可${action === 'prepare' ? '备货' : '配送'}订单`
    )
  }

  watch(
    [keyword, filterDeliverySlots, filterDeliveryAreas, filterDeliveryBuildings],
    clearSelection,
    { deep: true }
  )

  const canAccept = (row: OrderSummary) => row.status === '已支付/待接单'
  const canDeliver = (row: OrderSummary) => row.status === '备货中'
  const canComplete = (row: OrderSummary) => row.status === '配送中'
  const canCancel = (row: OrderSummary) =>
    ['待支付', '已支付/待接单', '备货中'].includes(row.status)

  const fullAddress = (row: OrderSummary) =>
    [row.address?.locationName, row.address?.detail].filter(Boolean).join(' ') || '地址待完善'

  const displayGroupCount = (row: OrderSummary) =>
    displayGroupMeta.value.get(row.id)?.count || row.buildingOrderCount

  const spanMethod = ({ row, column }: { row: OrderSummary; column: { label?: string } }) => {
    if (column.label !== '配送分组') return [1, 1]
    const meta = displayGroupMeta.value.get(row.id)
    return meta?.position === 1 ? [meta.count, 1] : [0, 0]
  }

  const rowClassName = ({ row }: { row: OrderSummary }) =>
    displayGroupMeta.value.get(row.id)?.position === 1
      ? 'delivery-group-start'
      : 'delivery-group-row'

  const resetFilters = async () => {
    status.value = '全部'
    printStatus.value = '全部'
    deliveryDate.value = ''
    keyword.value = ''
    filterDeliverySlots.value = []
    filterDeliveryAreas.value = []
    filterDeliveryBuildings.value = []
    await loadOrders()
  }

  const loadOrders = async () => {
    loading.value = true
    try {
      const mappedPrintStatus = printStatusMap[printStatus.value]
      const [result, slotResult] = await Promise.all([
        getOrders(status.value, deliveryDate.value || undefined, mappedPrintStatus || undefined),
        getDeliverySlots()
      ])
      orders.value = result.items || []
      configuredDeliverySlots.value = (slotResult.items || [])
        .filter((slot) => slot.available)
        .map((slot) => slot.label)
      deliverySlotsLoaded.value = true
      clearSelection()
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '订单加载失败')
    } finally {
      loading.value = false
    }
  }

  const handleBatchPrint = async () => {
    if (selectedPrintableIds.value.length === 0) {
      ElMessage.warning('请先选择要打印的订单')
      return
    }

    try {
      await ElMessageBox.confirm(
        `确认批量打印 ${selectedPrintableIds.value.length} 个订单？`,
        '批量打印',
        { type: 'warning' }
      )

      batchPrinting.value = true
      const result = await batchPrintOrders(selectedPrintableIds.value)

      if (result.failed > 0) {
        const details = result.errors
          .slice(0, 3)
          .map((item) => `订单 ${item.orderId}：${item.reason}`)
          .join('；')
        ElMessage.warning(
          `批量打印已提交：成功 ${result.success} 个，失败 ${result.failed} 个${details ? `；${details}` : ''}`
        )
      } else {
        ElMessage.success(`批量打印任务已提交，共 ${result.success} 个`)
      }

      selectedOrderIds.value = []
      await loadOrders()
    } catch (error) {
      if (error !== 'cancel' && error !== 'close') {
        ElMessage.error(error instanceof Error ? error.message : '批量打印失败')
      }
    } finally {
      batchPrinting.value = false
    }
  }

  const handleWechatShipmentExport = async () => {
    if (selectedWechatExportIds.value.length === 0) {
      ElMessage.warning('请先选择要导出的已支付订单')
      return
    }
    try {
      await ElMessageBox.confirm(
        `确认按微信批量发货模板导出 ${selectedWechatExportIds.value.length} 个订单？此操作不会修改订单状态。`,
        '导出微信发货单',
        {
          type: 'info',
          confirmButtonText: '生成并下载',
          cancelButtonText: '取消'
        }
      )
      wechatExporting.value = true
      const currentDetails = await Promise.all(
        selectedWechatExportIds.value.map((orderId) => getOrderDetail(orderId))
      )
      const details: OrderDetail[] = await Promise.all(
        currentDetails.map((order) =>
          order.transactionId ? order : refreshOrderPaymentTransaction(order.id)
        )
      )
      downloadWechatShipmentSheet(details)
      ElMessage.success(`已生成 ${details.length} 单微信批量发货文件`)
    } catch (error) {
      if (error !== 'cancel' && error !== 'close') {
        ElMessage.error(error instanceof Error ? error.message : '微信发货单生成失败')
      }
    } finally {
      wechatExporting.value = false
    }
  }

  const handleBatchAction = async (action: 'prepare' | 'deliver') => {
    const ids = action === 'prepare' ? selectedPrepareIds.value : selectedDeliverIds.value
    if (ids.length === 0) {
      ElMessage.warning(
        action === 'prepare' ? '选中的订单中没有可备货订单' : '选中的订单中没有可配送订单'
      )
      return
    }
    const label = action === 'prepare' ? '备货' : '配送'
    try {
      await ElMessageBox.confirm(
        `确认将 ${ids.length} 个符合状态的订单批量${label}？`,
        `批量${label}`,
        {
          type: 'warning',
          confirmButtonText: `确认${label}`,
          cancelButtonText: '取消'
        }
      )
      if (action === 'prepare') batchPreparing.value = true
      else batchDelivering.value = true
      const result =
        action === 'prepare' ? await batchPrepareOrders(ids) : await batchDeliverOrders(ids)
      if (action === 'deliver' && result.processedOrderIds.length > 0) {
        const deliveredOrders = await Promise.all(
          result.processedOrderIds.map((orderId) => getOrderDetail(orderId))
        )
        downloadWechatShipmentSheet(deliveredOrders)
      }
      if (result.skipped > 0) {
        ElMessage.warning(`批量${label}完成：成功 ${result.success} 单，跳过 ${result.skipped} 单`)
      } else {
        ElMessage.success(`批量${label}完成，共处理 ${result.success} 单`)
      }
      await loadOrders()
    } catch (error) {
      if (error !== 'cancel' && error !== 'close') {
        ElMessage.error(error instanceof Error ? error.message : `批量${label}失败`)
      }
    } finally {
      batchPreparing.value = false
      batchDelivering.value = false
    }
  }

  const handlePrintOne = async (row: OrderSummary) => {
    try {
      const isReprint = row.printStatus === 'SUCCESS'
      await ElMessageBox.confirm(
        `确认${isReprint ? '补打' : '打印'}订单 ${row.orderNo}？`,
        isReprint ? '补打订单' : '打印订单',
        {
          type: 'warning',
          confirmButtonText: isReprint ? '确认补打' : '确认打印',
          cancelButtonText: '取消'
        }
      )
      printingOrderId.value = row.id
      const result = await batchPrintOrders([row.id])
      if (result.success > 0) {
        ElMessage.success(isReprint ? '补打任务已提交' : '打印任务已提交')
      } else {
        ElMessage.error(result.errors[0]?.reason || '打印任务提交失败')
      }
      await loadOrders()
    } catch (error) {
      if (error !== 'cancel' && error !== 'close') {
        ElMessage.error(error instanceof Error ? error.message : '打印任务提交失败')
      }
    } finally {
      printingOrderId.value = null
    }
  }

  const openDetail = async (id: number) => {
    try {
      detail.value = await getOrderDetail(id)
      detailVisible.value = true
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '订单详情加载失败')
    }
  }

  const actionMap = {
    accept: { message: '确认接单？', success: '已接单', fn: acceptOrder },
    deliver: { message: '确认开始配送？', success: '已进入配送中', fn: deliverOrder },
    complete: { message: '确认订单已完成？', success: '订单已完成', fn: completeOrder },
    cancel: { message: '确认取消订单？', success: '订单已取消', fn: cancelOrder }
  }

  const runAction = async (id: number, action: keyof typeof actionMap) => {
    const current = actionMap[action]
    await ElMessageBox.confirm(current.message, '订单操作', {
      type: action === 'cancel' ? 'warning' : 'info',
      confirmButtonText: '确认',
      cancelButtonText: '取消'
    })
    try {
      const updated = await current.fn(id)
      if (action === 'deliver') {
        downloadWechatShipmentSheet([updated])
      }
      await loadOrders()
      ElMessage.success(current.success)
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '订单操作失败')
    }
  }

  onMounted(loadOrders)
</script>

<style scoped lang="scss">
  @use '../style.scss';

  .order-item {
    display: flex;
    align-items: center;
    gap: 10px;
  }

  .order-filter {
    display: grid;
    gap: 14px;
    padding: 16px;
    margin-bottom: 18px;
    border: 1px solid var(--el-border-color);
    border-radius: 12px;
    background: var(--el-fill-color-lighter);

    &__head,
    &__footer {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 16px;
    }

    &__head > div {
      display: grid;
      gap: 3px;

      strong {
        color: var(--el-text-color-primary);
        font-size: 15px;
      }

      span {
        color: var(--el-text-color-secondary);
        font-size: 12px;
      }
    }

    &__status {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 12px 22px;
      overflow-x: auto;
    }

    &__status-group {
      display: flex;
      align-items: center;
      gap: 10px;

      > span {
        flex: none;
        color: var(--el-text-color-secondary);
        font-size: 13px;
      }
    }

    &__fields {
      display: grid;
      grid-template-columns: minmax(260px, 2fr) repeat(4, minmax(165px, 1fr));
      gap: 12px;

      :deep(.el-date-editor),
      :deep(.el-select) {
        width: 100%;
      }
    }

    &__footer {
      padding-top: 12px;
      border-top: 1px dashed var(--el-border-color);
      color: var(--el-text-color-secondary);
      font-size: 13px;

      b {
        color: var(--el-color-primary);
      }
    }
  }

  .delivery-toolbar {
    &__summary {
      display: flex;
      align-items: center;
      gap: 10px;

      strong {
        color: var(--el-color-primary);
        font-size: 13px;
      }

      span {
        color: var(--el-text-color-secondary);
        font-size: 12px;
      }
    }

    &__signal {
      width: 10px;
      height: 10px;
      border-radius: 50%;
      background: var(--el-color-success);
      box-shadow: 0 0 0 5px color-mix(in srgb, var(--el-color-success) 18%, transparent);
    }
  }

  .batch-toolbar {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 18px;
    padding: 14px 16px;
    margin-bottom: 16px;
    border-radius: 10px;
    background: var(--el-fill-color-light);
    border: 1px solid var(--el-border-color);

    &__selection,
    &__actions {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 10px;
    }

    &__selection {
      min-width: 0;

      > strong {
        padding-right: 4px;
        color: var(--el-text-color-primary);
      }
    }

    &__actions {
      flex: none;
      justify-content: flex-end;
    }

    &__count {
      color: var(--el-text-color-secondary);
      font-size: 14px;
    }
  }

  .batch-hint {
    padding: 0 4px 14px;
    color: var(--el-text-color-secondary);
    font-size: 12px;
  }

  .delivery-sequence {
    display: inline-grid;
    width: 34px;
    height: 34px;
    place-items: center;
    border-radius: 11px;
    color: var(--el-color-primary);
    font-weight: 700;
    background: var(--el-color-primary-light-9);
  }

  .delivery-group {
    display: grid;
    min-width: 0;
    gap: 7px;
    padding: 6px 2px;

    > strong {
      color: var(--el-text-color-primary);
      font-size: 15px;
      overflow-wrap: anywhere;
    }

    &__building {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 10px;
      color: var(--el-color-primary);

      b {
        flex: none;
        padding: 3px 8px;
        border-radius: 999px;
        font-size: 12px;
        background: var(--el-color-primary-light-8);
      }
    }

    small {
      color: var(--el-text-color-secondary);
    }
  }

  .address-cell,
  .order-summary {
    display: grid;
    min-width: 0;
    justify-items: start;
    gap: 6px;

    strong {
      color: var(--el-text-color-primary);
      overflow-wrap: anywhere;
    }

    span {
      color: var(--el-text-color-secondary);
      font-size: 13px;
      overflow-wrap: anywhere;
    }
  }

  .order-actions {
    display: flex;
    flex-wrap: nowrap;
    align-items: center;
    justify-content: center;
    gap: 6px;
    white-space: nowrap;

    :deep(.el-button + .el-button) {
      margin-left: 0;
    }

    :deep(.el-button) {
      min-width: 0;
      padding-right: 8px;
      padding-left: 8px;
    }
  }

  :deep(.el-table__cell) {
    vertical-align: middle;
  }

  :deep(.el-table__body .cell) {
    overflow: visible;
  }

  :deep(.delivery-group-start td) {
    border-top: 2px solid var(--el-color-primary-light-5);
  }

  :deep(.delivery-group-row td) {
    background: var(--el-bg-color);
  }

  @media (max-width: 1100px) {
    .order-filter {
      &__fields {
        grid-template-columns: repeat(2, minmax(0, 1fr));
      }

      &__keyword {
        grid-column: 1 / -1;
      }
    }

    .batch-toolbar {
      flex-direction: column;

      &__actions {
        justify-content: flex-start;
      }
    }
  }

  @media (max-width: 700px) {
    .order-filter {
      &__fields {
        grid-template-columns: 1fr;
      }

      &__keyword {
        grid-column: auto;
      }

      &__footer {
        align-items: flex-start;
        flex-direction: column;
      }
    }
  }

  .amount-list {
    display: grid;
    gap: 10px;
    margin-top: 16px;
    padding: 14px;
    border-radius: 10px;
    background: var(--el-fill-color-light);

    div {
      display: flex;
      align-items: center;
      justify-content: space-between;
    }
  }
</style>
