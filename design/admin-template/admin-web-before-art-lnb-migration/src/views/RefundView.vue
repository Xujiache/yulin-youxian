<template>
  <div>
    <div class="toolbar">
      <h1 class="page-title">售后退款</h1>
      <el-button :loading="loading" @click="loadRefunds">刷新</el-button>
    </div>
    <div class="card-section">
      <el-table :data="refunds" border empty-text="暂无退款申请">
        <el-table-column prop="refundNo" label="退款单号" min-width="150" />
        <el-table-column prop="orderId" label="订单ID" width="90" />
        <el-table-column label="金额" width="120">
          <template #default="{ row }">
            <span class="price-text">￥{{ (row.refundAmount / 100).toFixed(2) }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="reason" label="原因" min-width="220" show-overflow-tooltip />
        <el-table-column label="凭证" min-width="180">
          <template #default="{ row }">
            <div v-if="row.evidenceImages?.length" class="evidence-list">
              <el-image
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
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="row.status === '待审核' ? 'warning' : row.status === '已拒绝' ? 'danger' : 'success'">
              {{ row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="210">
          <template #default="{ row }">
            <el-button size="small" type="success" :disabled="row.status !== '待审核'" @click="approve(row)">通过</el-button>
            <el-button size="small" type="danger" :disabled="row.status !== '待审核'" @click="reject(row)">拒绝</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { approveRefund, getRefunds, rejectRefund } from "../api/refunds";

const refunds = ref([]);
const loading = ref(false);

async function loadRefunds() {
  loading.value = true;
  try {
    const result = await getRefunds();
    refunds.value = result.items || [];
  } catch (error) {
    refunds.value = [];
    ElMessage.error(error.message || "退款列表加载失败");
  } finally {
    loading.value = false;
  }
}

async function approve(row) {
  await ElMessageBox.confirm(`确认通过退款 ${row.refundNo}？`, "审核退款", { type: "warning" });
  await approveRefund(row.id);
  await loadRefunds();
  ElMessage.success("已通过");
}

async function reject(row) {
  const result = await ElMessageBox.prompt("请输入拒绝原因", "拒绝退款", {
    inputType: "textarea",
    inputValue: "退款资料不完整，请补充后重新提交",
    inputPattern: /\S+/,
    inputErrorMessage: "拒绝原因不能为空"
  });
  await rejectRefund(row.id, result.value);
  await loadRefunds();
  ElMessage.success("已拒绝");
}

onMounted(loadRefunds);
</script>

<style scoped>
.evidence-list {
  display: flex;
  align-items: center;
  gap: 8px;
}

.evidence-thumb {
  width: 48px;
  height: 48px;
  border-radius: 8px;
}

.muted {
  color: #8a9690;
}
</style>
