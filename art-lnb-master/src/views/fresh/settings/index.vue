<template>
  <div class="fresh-page">
    <div class="fresh-page__head">
      <div>
        <h1 class="fresh-page__title">门店设置</h1>
        <p class="fresh-page__desc">配置小程序门店基础信息、下单结算费用和联系方式。</p>
      </div>
      <div class="settings-actions">
        <ElButton :loading="seeding" @click="handleSeedDemoData">补齐示例数据</ElButton>
        <ElButton type="primary" :loading="saving" @click="saveSettings">保存配置</ElButton>
      </div>
    </div>

    <ElCard class="fresh-card" shadow="never">
      <template #header>
        <div class="card-header">
          <strong>基础信息</strong>
          <span>影响小程序门店名称、费用和联系电话。</span>
        </div>
      </template>
      <ElSkeleton v-if="loading" :rows="7" animated />
      <ElForm v-else :model="form" label-width="130px" class="settings-form">
        <ElFormItem label="门店名称" required>
          <ElInput v-model.trim="form.storeName" maxlength="30" show-word-limit />
        </ElFormItem>
        <ElFormItem label="起送价（分）" required>
          <ElInputNumber v-model="form.minOrderAmount" :min="0" class="form-full" />
        </ElFormItem>
        <ElFormItem label="配送费（分）" required>
          <ElInputNumber v-model="form.deliveryFee" :min="0" class="form-full" />
        </ElFormItem>
        <ElFormItem label="包装费（分）" required>
          <ElInputNumber v-model="form.packageFee" :min="0" class="form-full" />
        </ElFormItem>
        <ElFormItem label="营业时间" required>
          <ElInput v-model.trim="form.businessHours" placeholder="例如：08:00-20:00" />
        </ElFormItem>
        <ElFormItem label="联系电话" required>
          <ElInput v-model.trim="form.contactPhone" maxlength="20" />
        </ElFormItem>
      </ElForm>
    </ElCard>
  </div>
</template>

<script setup lang="ts">
  import { ElMessage } from 'element-plus'
  import {
    getSettings,
    seedDemoData,
    updateSettings,
    type StoreSettings
  } from '@/api/admin'

  defineOptions({ name: 'FreshSettings' })

  const loading = ref(false)
  const saving = ref(false)
  const seeding = ref(false)
  const form = reactive<StoreSettings>({
    storeName: '',
    minOrderAmount: 0,
    deliveryFee: 0,
    packageFee: 0,
    businessHours: '',
    contactPhone: ''
  })

  const loadSettings = async () => {
    loading.value = true
    try {
      const settingsResult = await getSettings()
      Object.assign(form, settingsResult)
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '门店设置加载失败')
    } finally {
      loading.value = false
    }
  }

  const validateSettings = () => {
    if (!form.storeName) return '请填写门店名称'
    if (!form.businessHours) return '请填写营业时间'
    if (!form.contactPhone) return '请填写联系电话'
    return ''
  }

  const saveSettings = async () => {
    const message = validateSettings()
    if (message) {
      ElMessage.warning(message)
      return
    }
    saving.value = true
    try {
      const settingsResult = await updateSettings(form)
      Object.assign(form, settingsResult)
      ElMessage.success('门店设置已保存')
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '门店设置保存失败')
    } finally {
      saving.value = false
    }
  }

  const handleSeedDemoData = async () => {
    seeding.value = true
    try {
      await seedDemoData()
      ElMessage.success('示例数据已补齐')
      await loadSettings()
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '示例数据补齐失败')
    } finally {
      seeding.value = false
    }
  }

  onMounted(loadSettings)
</script>

<style scoped lang="scss">
  @use '../style.scss';

  .settings-actions {
    display: flex;
    gap: 12px;
  }

  .settings-form {
    max-width: 620px;
  }

  .card-header {
    display: flex;
    flex-direction: column;
    gap: 4px;

    span {
      color: var(--art-text-gray-600);
      font-size: 13px;
      font-weight: 400;
    }
  }
</style>
