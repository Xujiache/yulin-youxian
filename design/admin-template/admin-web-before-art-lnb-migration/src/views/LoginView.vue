<template>
  <div class="login-page">
    <div class="login-panel">
      <div class="brand-mark">禹</div>
      <h1>禹邻优鲜管理后台</h1>
      <p>单门店商品、订单、配送和售后管理</p>
      <el-form :model="form" label-position="top" @submit.prevent>
        <el-form-item label="账号">
          <el-input v-model="form.username" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="form.password" type="password" show-password />
        </el-form-item>
        <el-button type="primary" class="login-button" :loading="loading" @click="handleLogin">登录</el-button>
      </el-form>
    </div>
  </div>
</template>

<script setup>
import { reactive, ref } from "vue";
import { useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import { login } from "../api/auth";

const router = useRouter();
const loading = ref(false);
const form = reactive({
  username: "",
  password: ""
});

async function handleLogin() {
  if (!form.username || !form.password) {
    ElMessage.warning("请输入账号和密码");
    return;
  }
  loading.value = true;
  try {
    const result = await login(form);
    localStorage.setItem("adminToken", result.token);
    localStorage.setItem("adminName", result.name);
    ElMessage.success("登录成功");
    router.replace("/dashboard");
  } catch (error) {
    ElMessage.error(error.message || "登录失败");
  } finally {
    loading.value = false;
  }
}
</script>

<style scoped>
.login-page {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  background: #f2f4f6;
}

.login-panel {
  width: 390px;
  padding: 34px;
  background: #ffffff;
  border: 1px solid #edf0ee;
  border-radius: 8px;
}

.brand-mark {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 44px;
  height: 44px;
  color: #ffffff;
  font-size: 22px;
  font-weight: 800;
  background: #006d37;
  border-radius: 8px;
}

h1 {
  margin: 18px 0 8px;
  color: #191c1e;
  font-size: 24px;
}

p {
  margin: 0 0 24px;
  color: #6d7a6e;
}

.login-button {
  width: 100%;
}
</style>
