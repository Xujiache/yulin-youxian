<template>
  <div>
    <div class="toolbar">
      <h1 class="page-title">订单管理</h1>
      <div class="toolbar-actions">
        <el-segmented v-model="status" :options="statuses" @change="loadOrders" />
        <el-button :loading="loading" @click="loadOrders">刷新</el-button>
      </div>
    </div>

    <div class="card-section">
      <el-table :data="orders" border empty-text="暂无订单">
        <el-table-column prop="orderNo" label="订单号" min-width="170" />
        <el-table-column label="状态" width="130">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="summary" label="商品" min-width="130" />
        <el-table-column prop="deliverySlot" label="预约配送" min-width="160" />
        <el-table-column label="金额" width="120">
          <template #default="{ row }">
            <span class="price-text">￥{{ (row.totalAmount / 100).toFixed(2) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="280">
          <template #default="{ row }">
            <el-button v-if="canPrepare(row)" size="small" type="primary" @click="handlePrepare(row.id)">开始备货</el-button>
            <el-button v-if="canDeliver(row)" size="small" @click="handleDeliver(row.id)">开始配送</el-button>
            <el-button v-if="canComplete(row)" size="small" type="success" @click="handleComplete(row.id)">完成</el-button>
            <el-button v-if="canCancel(row)" size="small" type="danger" plain @click="handleCancel(row.id)">取消</el-button>
            <span v-if="!hasActions(row)" class="muted">无需操作</span>
          </template>
        </el-table-column>
      </el-table>
    </div>
  </div>
</template>

<script setup>
import { onMounted, onUnmounted, ref } from "vue";
import { ElMessage } from "element-plus";
import { cancelOrder, completeOrder, deliverOrder, getOrders, prepareOrder } from "../api/orders";

const statuses = ["全部", "待支付", "待接单", "备货中", "配送中", "已完成", "退款中", "已退款"];
const status = ref("全部");
const orders = ref([]);
const loading = ref(false);
let refreshTimer = null;
let knownOrderNos = new Set();

function statusType(value) {
  if (["已完成", "已退款"].includes(value)) {
    return "success";
  }
  if (["退款中", "部分退款"].includes(value)) {
    return "warning";
  }
  if (["已取消"].includes(value)) {
    return "info";
  }
  return "primary";
}

function canPrepare(row) {
  return row.status === "已支付/待接单";
}

function canDeliver(row) {
  return ["已支付/待接单", "备货中"].includes(row.status);
}

function canComplete(row) {
  return row.status === "配送中";
}

function canCancel(row) {
  return ["待支付", "已支付/待接单", "备货中"].includes(row.status);
}

function hasActions(row) {
  return canPrepare(row) || canDeliver(row) || canComplete(row) || canCancel(row);
}

async function loadOrders({ silent = false } = {}) {
  loading.value = true;
  try {
    const result = await getOrders({ status: status.value });
    const nextOrders = result.items || [];
    const nextOrderNos = new Set(nextOrders.map((item) => item.orderNo));
    const hasNewOrder = knownOrderNos.size > 0 && nextOrders.some((item) => !knownOrderNos.has(item.orderNo));
    orders.value = nextOrders;
    knownOrderNos = nextOrderNos;
    if (hasNewOrder && !silent) {
      ElMessage.success("有新订单，请及时处理");
    }
  } catch (error) {
    orders.value = [];
    if (!silent) {
      ElMessage.error(error.message || "订单加载失败");
    }
  } finally {
    loading.value = false;
  }
}

async function runAction(action, successMessage) {
  try {
    await action();
    await loadOrders({ silent: true });
    ElMessage.success(successMessage);
  } catch (error) {
    ElMessage.error(error.message || "操作失败");
  }
}

function handlePrepare(id) {
  return runAction(() => prepareOrder(id), "已进入备货");
}

function handleDeliver(id) {
  return runAction(() => deliverOrder(id), "已进入配送");
}

function handleComplete(id) {
  return runAction(() => completeOrder(id), "订单已完成");
}

function handleCancel(id) {
  return runAction(() => cancelOrder(id), "订单已取消");
}

onMounted(() => {
  loadOrders({ silent: true });
  refreshTimer = window.setInterval(() => loadOrders({ silent: true }), 30000);
});

onUnmounted(() => {
  if (refreshTimer) {
    window.clearInterval(refreshTimer);
  }
});
</script>

<style scoped>
.toolbar-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.muted {
  color: #8a9690;
}
</style>
