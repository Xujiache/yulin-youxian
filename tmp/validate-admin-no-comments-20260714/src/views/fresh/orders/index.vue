<template>
  <div class="fresh-page">
    <div class="fresh-page__head">
      <div>
        <h1 class="fresh-page__title">订单管理</h1>
        <p class="fresh-page__desc">处理门店自配送订单状态流转。</p>
      </div>
      <ElButton type="primary" :loading="loading" @click="loadOrders">刷新订单</ElButton>
    </div>

    <ElCard class="fresh-card" shadow="never">
      <div class="fresh-toolbar">
        <div class="fresh-toolbar__left">
          <ElSegmented v-model="status" :options="statuses" @change="loadOrders" />
        </div>
      </div>

      <ElTable v-loading="loading" :data="orders" border empty-text="暂无订单">
        <ElTableColumn prop="orderNo" label="订单号" min-width="190" />
        <ElTableColumn label="状态" width="140">
          <template #default="{ row }">
            <ElTag :type="statusTag(row.status)">{{ row.status }}</ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn prop="summary" label="商品摘要" min-width="150" />
        <ElTableColumn prop="deliverySlot" label="预约配送" min-width="160" />
        <ElTableColumn label="金额" width="120">
          <template #default="{ row }">
            <span class="money">{{ money(row.totalAmount) }}</span>
          </template>
        </ElTableColumn>
        <ElTableColumn label="操作" width="340" fixed="right">
          <template #default="{ row }">
            <ElButton size="small" @click="openDetail(row.id)">详情</ElButton>
            <ElButton v-if="canAccept(row)" size="small" type="primary" @click="runAction(row.id, 'accept')">
              接单
            </ElButton>
            <ElButton v-if="canDeliver(row)" size="small" @click="runAction(row.id, 'deliver')">
              配送
            </ElButton>
            <ElButton v-if="canComplete(row)" size="small" type="success" @click="runAction(row.id, 'complete')">
              完成
            </ElButton>
            <ElButton v-if="canCancel(row)" size="small" type="danger" plain @click="runAction(row.id, 'cancel')">
              取消
            </ElButton>
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
                <div class="muted">{{ row.quantity }}{{ row.saleUnit }} x {{ money(row.unitPrice) }}</div>
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
        <div><span>商品总额</span><strong>{{ money(detail.productAmount) }}</strong></div>
        <div><span>配送费</span><strong>{{ money(detail.deliveryFee) }}</strong></div>
        <div><span>包装费</span><strong>{{ money(detail.packageFee) }}</strong></div>
        <div><span>实付金额</span><strong class="money">{{ money(detail.paidAmount || detail.payableAmount) }}</strong></div>
        <div><span>已退款</span><strong>{{ money(detail.refundedAmount) }}</strong></div>
      </div>
    </ElDialog>
  </div>
</template>

<script setup lang="ts">
import { ElMessage, ElMessageBox } from 'element-plus';
import { acceptOrder, cancelOrder, completeOrder, deliverOrder, getOrderDetail, getOrders, type OrderDetail, type OrderSummary } from '@/api/admin';
defineOptions({ name: 'FreshOrders' });
const statuses = ['全部', '待支付', '待接单', '备货中', '配送中', '已完成', '售后'];
const status = ref('全部');
const loading = ref(false);
const detailVisible = ref(false);
const orders = ref<OrderSummary[]>([]);
const detail = ref<OrderDetail | null>(null);
const money = (value: number) => `￥${(Number(value || 0) / 100).toFixed(2)}`;
const statusTag = (value: string) => {
    if (value === '已完成' || value === '退款成功')
        return 'success';
    if (value.includes('退款'))
        return 'warning';
    if (value === '已取消')
        return 'info';
    return 'primary';
};
const canAccept = (row: OrderSummary) => row.status === '已支付/待接单';
const canDeliver = (row: OrderSummary) => ['已支付/待接单', '备货中'].includes(row.status);
const canComplete = (row: OrderSummary) => row.status === '配送中';
const canCancel = (row: OrderSummary) => ['待支付', '已支付/待接单', '备货中'].includes(row.status);
const loadOrders = async () => {
    loading.value = true;
    try {
        const result = await getOrders(status.value);
        orders.value = result.items || [];
    }
    catch (error) {
        ElMessage.error(error instanceof Error ? error.message : '订单加载失败');
    }
    finally {
        loading.value = false;
    }
};
const openDetail = async (id: number) => {
    try {
        detail.value = await getOrderDetail(id);
        detailVisible.value = true;
    }
    catch (error) {
        ElMessage.error(error instanceof Error ? error.message : '订单详情加载失败');
    }
};
const actionMap = {
    accept: { message: '确认接单？', success: '已接单', fn: acceptOrder },
    deliver: { message: '确认开始配送？', success: '已进入配送中', fn: deliverOrder },
    complete: { message: '确认订单已完成？', success: '订单已完成', fn: completeOrder },
    cancel: { message: '确认取消订单？', success: '订单已取消', fn: cancelOrder }
};
const runAction = async (id: number, action: keyof typeof actionMap) => {
    const current = actionMap[action];
    await ElMessageBox.confirm(current.message, '订单操作', {
        type: action === 'cancel' ? 'warning' : 'info',
        confirmButtonText: '确认',
        cancelButtonText: '取消'
    });
    try {
        await current.fn(id);
        await loadOrders();
        ElMessage.success(current.success);
    }
    catch (error) {
        ElMessage.error(error instanceof Error ? error.message : '订单操作失败');
    }
};
onMounted(loadOrders);
</script>

<style scoped lang="scss">

  @use '../style.scss';

  .order-item {
    display: flex;
    align-items: center;
    gap: 10px;
  }

  .amount-list {
    display: grid;
    gap: 10px;
    margin-top: 16px;
    padding: 14px;
    border-radius: 10px;
    background: #f7faf8;

    div {
      display: flex;
      align-items: center;
      justify-content: space-between;
    }
  }
</style>
