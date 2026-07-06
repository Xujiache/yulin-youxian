<template>
  <div>
    <h1 class="page-title">数据概览</h1>
    <el-row :gutter="16">
      <el-col v-for="item in statCards" :key="item.label" :span="6">
        <div class="stat-card">
          <div class="stat-label">{{ item.label }}</div>
          <div class="stat-value">{{ item.value }}</div>
        </div>
      </el-col>
    </el-row>

    <div class="card-section pending-section">
      <div class="section-head">
        <strong>今日待处理</strong>
        <el-button type="primary" plain>刷新</el-button>
      </div>
      <el-alert title="待接单、退款审核和预约配送订单会在这里集中处理。" type="success" :closable="false" />
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from "vue";
import { ElMessage } from "element-plus";
import { getDashboardSummary } from "../api/dashboard";

const summary = ref({
  todayOrderCount: 0,
  todaySalesAmount: 0,
  pendingOrderCount: 0,
  refundPendingCount: 0
});

const statCards = computed(() => [
  { label: "今日订单", value: summary.value.todayOrderCount },
  { label: "今日销售额", value: `￥${(summary.value.todaySalesAmount / 100).toFixed(2)}` },
  { label: "待接单", value: summary.value.pendingOrderCount },
  { label: "退款待处理", value: summary.value.refundPendingCount }
]);

onMounted(async () => {
  try {
    summary.value = await getDashboardSummary();
  } catch (error) {
    ElMessage.error(error.message || "数据概览加载失败");
    summary.value = {
      todayOrderCount: 0,
      todaySalesAmount: 0,
      pendingOrderCount: 0,
      refundPendingCount: 0
    };
  }
});
</script>

<style scoped>
.stat-card {
  padding: 20px;
  background: #ffffff;
  border: 1px solid #edf0ee;
  border-radius: 8px;
}

.stat-label {
  color: #3d4a3f;
  font-size: 14px;
}

.stat-value {
  margin-top: 10px;
  color: #006d37;
  font-size: 28px;
  font-weight: 800;
}

.pending-section {
  margin-top: 16px;
}

.section-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}
</style>
