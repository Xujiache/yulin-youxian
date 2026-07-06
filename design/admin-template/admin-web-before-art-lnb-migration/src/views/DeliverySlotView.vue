<template>
  <div>
    <div class="toolbar">
      <h1 class="page-title">预约配送</h1>
      <el-button type="primary" @click="openCreate">新增时间段</el-button>
    </div>
    <div class="card-section">
      <el-table :data="slots" border>
        <el-table-column prop="label" label="时间段" />
        <el-table-column prop="maxOrders" label="最大订单数" width="120" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.available ? 'success' : 'info'">{{ row.available ? "启用" : "停用" }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="240">
          <template #default="{ row }">
            <el-button size="small" @click="openEdit(row)">编辑</el-button>
            <el-button size="small" @click="toggleStatus(row)">{{ row.available ? "停用" : "启用" }}</el-button>
            <el-button size="small" type="danger" @click="removeSlot(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑时间段' : '新增时间段'" width="480px">
      <el-form :model="form" label-width="110px">
        <el-form-item label="时间段">
          <el-input v-model="form.label" placeholder="今日 09:00-11:00" />
        </el-form-item>
        <el-form-item label="最大订单数">
          <el-input-number v-model="form.maxOrders" :min="1" class="full" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.available" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="saveSlot">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { createDeliverySlot, deleteDeliverySlot, getDeliverySlots, updateDeliverySlot, updateDeliverySlotStatus } from "../api/deliverySlots";

const slots = ref([]);
const dialogVisible = ref(false);
const form = reactive(emptyForm());

function emptyForm() {
  return {
    id: 0,
    label: "",
    maxOrders: 30,
    available: true
  };
}

async function loadSlots() {
  const result = await getDeliverySlots();
  slots.value = result.items || [];
}

function openCreate() {
  Object.assign(form, emptyForm());
  dialogVisible.value = true;
}

function openEdit(row) {
  Object.assign(form, emptyForm(), row);
  dialogVisible.value = true;
}

async function saveSlot() {
  if (!form.label) {
    ElMessage.warning("请填写时间段");
    return;
  }
  const payload = { label: form.label, maxOrders: form.maxOrders, available: form.available };
  if (form.id) {
    await updateDeliverySlot(form.id, payload);
  } else {
    await createDeliverySlot(payload);
  }
  dialogVisible.value = false;
  await loadSlots();
  ElMessage.success("已保存");
}

async function toggleStatus(row) {
  await updateDeliverySlotStatus(row.id, !row.available);
  await loadSlots();
}

async function removeSlot(row) {
  await ElMessageBox.confirm(`确认删除 ${row.label}？`, "删除时间段", { type: "warning" });
  await deleteDeliverySlot(row.id);
  await loadSlots();
}

onMounted(loadSlots);
</script>

<style scoped>
.full {
  width: 100%;
}
</style>
