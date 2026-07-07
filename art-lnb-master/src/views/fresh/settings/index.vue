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
        <ElFormItem label="首页Logo">
          <div class="logo-upload-row">
            <ElImage class="logo-preview" :src="assetUrl(form.logoUrl)" fit="cover">
              <template #error>
                <div class="logo-preview__fallback">Logo</div>
              </template>
            </ElImage>
            <div class="logo-upload-main">
              <ElUpload
                :show-file-list="false"
                accept=".jpg,.jpeg,.png,.webp"
                :http-request="uploadLogo"
              >
                <ElButton :loading="uploadingLogo">上传Logo</ElButton>
              </ElUpload>
              <p>建议使用 1:1 图片，小程序首页左上角展示。</p>
            </div>
          </div>
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
    uploadLogoImage,
    type StoreSettings
  } from '@/api/admin'

  defineOptions({ name: 'FreshSettings' })

  const loading = ref(false)
  const saving = ref(false)
  const seeding = ref(false)
  const uploadingLogo = ref(false)
  const form = reactive<StoreSettings>({
    storeName: '',
    logoUrl: '/assets/products/store-logo.png',
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

  const assetUrl = (url: string) => {
    if (!url || /^https?:\/\//.test(url)) return url
    if (url.startsWith('/assets/') || url.startsWith('/uploads/')) {
      return `${import.meta.env.VITE_API_URL}${url}`
    }
    return url
  }

  const uploadLogo = async (options: any) => {
    uploadingLogo.value = true
    try {
      const result = await uploadLogoImage(options.file)
      form.logoUrl = result.url
      options.onSuccess?.(result)
      ElMessage.success('Logo已上传')
    } catch (error) {
      options.onError?.(error)
      ElMessage.error(error instanceof Error ? error.message : 'Logo上传失败')
    } finally {
      uploadingLogo.value = false
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

  .logo-upload-row {
    display: flex;
    align-items: center;
    gap: 16px;
  }

  .logo-preview {
    width: 64px;
    height: 64px;
    flex-shrink: 0;
    overflow: hidden;
    border: 1px solid var(--art-border-color);
    border-radius: 16px;
    background: #f4f8f5;
  }

  .logo-preview__fallback {
    display: flex;
    align-items: center;
    justify-content: center;
    width: 100%;
    height: 100%;
    color: var(--art-text-gray-600);
    font-size: 12px;
    background: var(--art-bg-color);
  }

  .logo-upload-main {
    display: flex;
    flex-direction: column;
    gap: 8px;

    p {
      margin: 0;
      color: var(--art-text-gray-600);
      font-size: 12px;
      line-height: 18px;
    }
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
