<template>
  <el-container class="admin-shell">
    <el-aside width="224px" class="sidebar">
      <div class="brand">
        <div class="brand-mark">禹</div>
        <div>
          <div class="brand-title">禹邻优鲜</div>
          <div class="brand-subtitle">单门店管理后台</div>
        </div>
      </div>

      <el-menu router :default-active="$route.path" class="menu">
        <el-menu-item index="/dashboard">数据概览</el-menu-item>
        <el-menu-item index="/products">商品管理</el-menu-item>
        <el-menu-item index="/orders">订单管理</el-menu-item>
        <el-menu-item index="/refunds">售后退款</el-menu-item>
        <el-menu-item index="/delivery-slots">预约配送</el-menu-item>
        <el-menu-item index="/settings">系统配置</el-menu-item>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header class="topbar">
        <div>{{ $route.meta.title }}</div>
        <div class="admin-user">
          <span>{{ adminName }}</span>
          <el-button size="small" @click="handleLogout">退出</el-button>
        </div>
      </el-header>
      <el-main class="content">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import { ref } from "vue";
import { useRouter } from "vue-router";
import { logout } from "../api/auth";

const router = useRouter();
const adminName = ref(localStorage.getItem("adminName") || "管理员");

async function handleLogout() {
  try {
    await logout();
  } catch {}
  localStorage.removeItem("adminToken");
  localStorage.removeItem("adminName");
  router.replace("/login");
}
</script>

<style scoped>
.admin-shell {
  min-height: 100vh;
}

.sidebar {
  background: #ffffff;
  border-right: 1px solid #edf0ee;
}

.brand {
  display: flex;
  align-items: center;
  gap: 12px;
  height: 72px;
  padding: 0 18px;
  border-bottom: 1px solid #edf0ee;
}

.brand-mark {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  color: #ffffff;
  font-weight: 700;
  background: #006d37;
  border-radius: 8px;
}

.brand-title {
  color: #006d37;
  font-size: 18px;
  font-weight: 700;
}

.brand-subtitle {
  margin-top: 2px;
  color: #6d7a6e;
  font-size: 12px;
}

.menu {
  border-right: 0;
}

.topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 72px;
  color: #191c1e;
  font-size: 18px;
  font-weight: 700;
  background: #ffffff;
  border-bottom: 1px solid #edf0ee;
}

.admin-user {
  display: flex;
  align-items: center;
  gap: 12px;
  color: #3d4a3f;
  font-size: 14px;
  font-weight: 500;
}

.content {
  padding: 20px;
}
</style>
