<template>
  <div v-show="visible" class="admin-renewal-slot">
    <Transition name="el-message-fade">
      <div
        v-if="visible"
        class="el-message el-message--error admin-renewal-notice"
        role="alert"
        aria-live="assertive"
        aria-atomic="true"
        data-testid="admin-renewal-notice"
      >
        <ElIcon class="el-message__icon el-message-icon--error"><CircleCloseFilled /></ElIcon>
        <p class="el-message__content">账号服务即将到期，请联系管理员续费。</p>
      </div>
    </Transition>
  </div>
</template>

<script setup lang="ts">
  import { toRef } from 'vue'
  import { CircleCloseFilled } from '@element-plus/icons-vue'
  import 'element-plus/es/components/message/style/css'
  import { useAdminRenewalNotice } from '@/hooks/core/useAdminRenewalNotice'

  const props = defineProps<{ active: boolean }>()
  // Reuse error-message styling without ElMessage's Escape-to-close behavior.
  const visible = useAdminRenewalNotice(toRef(props, 'active'))
</script>

<style scoped>
  .admin-renewal-slot {
    display: flex;
    flex: 1;
    align-items: center;
    justify-content: center;
    min-width: 0;
    padding: 0 12px;
  }

  .admin-renewal-notice {
    position: static;
    inset: auto;
    max-width: 100%;
    margin: 0;
    padding: 8px 12px;
    transform: none;
  }

  .admin-renewal-notice .el-message__content {
    line-height: 18px;
    overflow-wrap: anywhere;
  }

  @media (width >= 1024px) {
    .admin-renewal-slot {
      min-width: 240px;
    }
  }

  @media (width <= 1100px) {
    .admin-renewal-slot {
      padding: 0 4px;
    }

    .admin-renewal-notice {
      padding: 4px 8px;
    }

    .admin-renewal-notice .el-message__content {
      font-size: 12px;
      line-height: 16px;
    }
  }
</style>
