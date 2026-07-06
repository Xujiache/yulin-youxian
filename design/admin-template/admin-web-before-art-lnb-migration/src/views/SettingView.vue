<template>
  <div>
    <h1 class="page-title">系统配置</h1>
    <div class="card-section">
      <el-form :model="form" label-width="120px" class="settings-form">
        <el-form-item label="门店名称">
          <el-input v-model="form.storeName" />
        </el-form-item>
        <el-form-item label="起送价（分）">
          <el-input-number v-model="form.minOrderAmount" :min="0" class="full" />
        </el-form-item>
        <el-form-item label="配送费（分）">
          <el-input-number v-model="form.deliveryFee" :min="0" class="full" />
        </el-form-item>
        <el-form-item label="包装费（分）">
          <el-input-number v-model="form.packageFee" :min="0" class="full" />
        </el-form-item>
        <el-form-item label="营业时间">
          <el-input v-model="form.businessHours" />
        </el-form-item>
        <el-form-item label="客服电话">
          <el-input v-model="form.contactPhone" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="handleSave">保存配置</el-button>
        </el-form-item>
      </el-form>
    </div>
  </div>
</template>

<script setup>
import { onMounted, reactive } from "vue";
import { ElMessage } from "element-plus";
import { getSettings, updateSettings } from "../api/settings";

const form = reactive({
  storeName: "禹邻优鲜",
  minOrderAmount: 0,
  deliveryFee: 500,
  packageFee: 100,
  businessHours: "08:00-20:00",
  contactPhone: "400-800-1234"
});

async function loadSettings() {
  const result = await getSettings();
  Object.assign(form, result);
}

async function handleSave() {
  await updateSettings({ ...form });
  ElMessage.success("配置已保存");
}

onMounted(loadSettings);
</script>

<style scoped>
.settings-form {
  max-width: 560px;
}

.full {
  width: 100%;
}
</style>
