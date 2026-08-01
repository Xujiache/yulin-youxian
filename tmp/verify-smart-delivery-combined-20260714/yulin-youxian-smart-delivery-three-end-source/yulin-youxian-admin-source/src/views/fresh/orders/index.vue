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
      <div class="delivery-toolbar">
        <div class="delivery-toolbar__filters">
          <ElSegmented v-model="status" :options="statuses" @change="loadOrders" />
          <ElDatePicker
            v-model="deliveryDate"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="全部配送日期"
            clearable
            @change="loadOrders"
          />
        </div>
        <div class="delivery-toolbar__summary">
          <span class="delivery-toolbar__signal"></span>
          <div>
            <strong>智能配送顺序已开启</strong>
            <span>共 {{ orders.length }} 单 · {{ groupCount }} 个配送分组</span>
          </div>
        </div>
      </div>

      <ElTable
        v-loading="loading"
        :data="orders"
        row-key="id"
        :span-method="spanMethod"
        :row-class-name="rowClassName"
        empty-text="暂无订单"
      >
        <ElTableColumn label="顺序" width="76" align="center">
          <template #default="{ row }">
            <span class="delivery-sequence">{{ row.deliverySequence }}</span>
          </template>
        </ElTableColumn>
        <ElTableColumn label="配送分组" min-width="210">
          <template #default="{ row }">
            <div class="delivery-group">
              <ElTag type="success" effect="light" size="small">{{ row.deliveryDate }}</ElTag>
              <strong>{{ row.deliveryArea }}</strong>
              <div class="delivery-group__building">
                <span>{{ row.deliveryBuilding }}</span>
                <b>{{ row.buildingOrderCount }} 单</b>
              </div>
              <small>同组订单已连续排列</small>
            </div>
          </template>
        </ElTableColumn>
        <ElTableColumn label="收货地址" min-width="290">
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
        <ElTableColumn label="状态" width="140">
          <template #default="{ row }">
            <ElTag :type="statusTag(row.status)">{{ row.status }}</ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="订单信息" min-width="210">
          <template #default="{ row }">
            <div class="order-summary">
              <strong>{{ row.orderNo }}</strong>
              <span>{{ row.summary }}</span>
            </div>
          </template>
        </ElTableColumn>
        <ElTableColumn prop="deliverySlot" label="预约配送" min-width="160" />
        <ElTableColumn label="金额" width="120">
          <template #default="{ row }">
            <span class="money">{{ money(row.totalAmount) }}</span>
          </template>
        </ElTableColumn>
        <ElTableColumn label="操作" width="340" fixed="right">
          <template #default="{ row }">
            <ElButton size="small" @click="openDetail(row.id)">详情</ElButton>
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
import { ElMessage, ElMessageBox } from 'element-plus';
import { acceptOrder, cancelOrder, completeOrder, deliverOrder, getOrderDetail, getOrders, type OrderDetail, type OrderSummary } from '@/api/admin';
defineOptions({ name: 'FreshOrders' });
const statuses = ['全部', '待支付', '待接单', '备货中', '配送中', '已完成', '售后'];
const status = ref('全部');
const deliveryDate = ref('');
const loading = ref(false);
const detailVisible = ref(false);
const orders = ref<OrderSummary[]>([]);
const detail = ref<OrderDetail | null>(null);
const groupCount = computed(() => new Set(orders.value.map((order) => order.deliveryGroupKey)).size);
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
const fullAddress = (row: OrderSummary) => [row.address?.locationName, row.address?.detail].filter(Boolean).join(' ') || '地址待完善';
const spanMethod = ({ row, columnIndex }: {
    row: OrderSummary;
    columnIndex: number;
}) => {
    if (columnIndex !== 1)
        return [1, 1];
    return row.buildingOrderPosition === 1 ? [row.buildingOrderCount, 1] : [0, 0];
};
const rowClassName = ({ row }: {
    row: OrderSummary;
}) => row.buildingOrderPosition === 1 ? 'delivery-group-start' : 'delivery-group-row';
const loadOrders = async () => {
    loading.value = true;
    try {
        const result = await getOrders(status.value, deliveryDate.value || undefined);
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

  .delivery-toolbar {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 20px;
    margin-bottom: 18px;

    &__filters {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 12px;
    }

    &__summary {
      display: flex;
      align-items: center;
      gap: 10px;
      min-width: 230px;
      padding: 10px 14px;
      border: 1px solid #ccebd9;
      border-radius: 12px;
      background: #f3fbf6;

      div {
        display: grid;
        gap: 2px;
      }

      strong {
        color: #087f45;
        font-size: 13px;
      }

      span {
        color: #6c7d73;
        font-size: 12px;
      }
    }

    &__signal {
      width: 10px;
      height: 10px;
      border-radius: 50%;
      background: #16a05d;
      box-shadow: 0 0 0 5px rgb(22 160 93 / 12%);
    }
  }

  .delivery-sequence {
    display: inline-grid;
    width: 34px;
    height: 34px;
    place-items: center;
    border-radius: 11px;
    color: #087f45;
    font-weight: 700;
    background: #eaf8f0;
  }

  .delivery-group {
    display: grid;
    gap: 7px;
    padding: 6px 2px;

    > strong {
      color: #16251c;
      font-size: 15px;
    }

    &__building {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 10px;
      color: #087f45;

      b {
        padding: 3px 8px;
        border-radius: 999px;
        font-size: 12px;
        background: #def4e7;
      }
    }

    small {
      color: #8b9991;
    }
  }

  .address-cell,
  .order-summary {
    display: grid;
    justify-items: start;
    gap: 6px;

    strong {
      color: #18241d;
    }

    span {
      color: #6f7c74;
      font-size: 13px;
    }
  }

  :deep(.delivery-group-start td) {
    border-top: 2px solid #c8ead6;
  }

  :deep(.delivery-group-row td) {
    background: #fbfefc;
  }

  @media (max-width: 1100px) {
    .delivery-toolbar {
      align-items: stretch;
      flex-direction: column;

      &__summary {
        min-width: 0;
      }
    }
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
