<template>
  <div class="fresh-page">
    <div class="fresh-page__head">
      <div>
        <h1 class="fresh-page__title">首页轮播图</h1>
        <p class="fresh-page__desc">单独管理小程序首页顶部轮播图，启用后会按排序展示。</p>
      </div>
      <div class="banner-actions">
        <ElButton type="primary" plain @click="addBanner">新增轮播图</ElButton>
        <ElButton type="primary" :loading="saving" @click="saveBanners">保存轮播图</ElButton>
      </div>
    </div>

    <ElCard class="fresh-card" shadow="never">
      <ElSkeleton v-if="loading" :rows="7" animated />
      <template v-else>
        <ElTable :data="banners" border empty-text="暂无轮播图">
          <ElTableColumn label="排序" width="100">
            <template #default="{ row }">
              <ElInputNumber v-model="row.sortOrder" :min="0" :controls="false" class="sort-input" />
            </template>
          </ElTableColumn>

          <ElTableColumn label="图片" width="220">
            <template #default="{ row }">
              <div class="banner-image-cell">
                <ElImage class="banner-thumb" :src="assetUrl(row.imageUrl)" fit="cover">
                  <template #error>
                    <div class="banner-thumb__fallback">预览</div>
                  </template>
                </ElImage>
                <ElUpload
                  :show-file-list="false"
                  accept=".jpg,.jpeg,.png,.webp"
                  :http-request="(options) => uploadBanner(options, row)"
                >
                  <ElButton size="small">上传图片</ElButton>
                </ElUpload>
              </div>
            </template>
          </ElTableColumn>

          <ElTableColumn label="标题" min-width="180">
            <template #default="{ row }">
              <ElInput v-model.trim="row.title" maxlength="24" show-word-limit />
            </template>
          </ElTableColumn>

          <ElTableColumn label="副标题" min-width="220">
            <template #default="{ row }">
              <ElInput v-model.trim="row.subtitle" maxlength="40" show-word-limit />
            </template>
          </ElTableColumn>

          <ElTableColumn label="跳转类型" width="170">
            <template #default="{ row }">
              <ElSelect v-model="row.linkType" class="form-full">
                <ElOption label="不跳转" value="none" />
                <ElOption label="商品详情" value="product" />
                <ElOption label="商品分类" value="category" />
              </ElSelect>
            </template>
          </ElTableColumn>

          <ElTableColumn label="目标ID" width="140">
            <template #default="{ row }">
              <ElInput v-model.trim="row.linkTarget" placeholder="可空" />
            </template>
          </ElTableColumn>

          <ElTableColumn label="启用" width="90" align="center">
            <template #default="{ row }">
              <ElSwitch v-model="row.enabled" />
            </template>
          </ElTableColumn>

          <ElTableColumn label="操作" width="90" fixed="right">
            <template #default="{ $index }">
              <ElButton link type="danger" @click="removeBanner($index)">删除</ElButton>
            </template>
          </ElTableColumn>
        </ElTable>
      </template>
    </ElCard>
  </div>
</template>

<script setup lang="ts">
  import { ElMessage } from 'element-plus'
  import {
    getBanners,
    updateBanners,
    uploadBannerImage,
    type Banner
  } from '@/api/admin'

  defineOptions({ name: 'FreshBanners' })

  const loading = ref(false)
  const saving = ref(false)
  const banners = ref<Banner[]>([])

  const emptyBanner = (): Banner => ({
    title: '今日新鲜到店',
    subtitle: '下单后由门店自配送',
    imageUrl: '/assets/products/home-banner-1.jpg',
    linkType: 'none',
    linkTarget: '',
    sortOrder: (banners.value.length + 1) * 10,
    enabled: true
  })

  const loadBanners = async () => {
    loading.value = true
    try {
      const result = await getBanners()
      banners.value = result.length ? result : [emptyBanner()]
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '轮播图加载失败')
    } finally {
      loading.value = false
    }
  }

  const validateBanners = () => {
    const invalidBanner = banners.value.find((item) => !item.title || !item.imageUrl)
    if (invalidBanner) return '请完善轮播图标题和图片'
    return ''
  }

  const assetUrl = (url: string) => {
    if (!url || /^https?:\/\//.test(url)) return url
    if (url.startsWith('/assets/') || url.startsWith('/uploads/')) {
      return `${import.meta.env.VITE_API_URL}${url}`
    }
    return url
  }

  const saveBanners = async () => {
    const message = validateBanners()
    if (message) {
      ElMessage.warning(message)
      return
    }
    saving.value = true
    try {
      banners.value = await updateBanners(banners.value)
      ElMessage.success('首页轮播图已保存')
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '轮播图保存失败')
    } finally {
      saving.value = false
    }
  }

  const uploadBanner = async (options: any, row: Banner) => {
    try {
      const result = await uploadBannerImage(options.file)
      row.imageUrl = result.url
      options.onSuccess?.(result)
      ElMessage.success('轮播图已上传')
    } catch (error) {
      options.onError?.(error)
      ElMessage.error(error instanceof Error ? error.message : '轮播图上传失败')
    }
  }

  const addBanner = () => {
    banners.value.push(emptyBanner())
  }

  const removeBanner = (index: number) => {
    banners.value.splice(index, 1)
    if (!banners.value.length) {
      banners.value.push(emptyBanner())
    }
  }

  onMounted(loadBanners)
</script>

<style scoped lang="scss">
  @use '../style.scss';

  .banner-actions {
    display: flex;
    gap: 12px;
  }

  .sort-input {
    width: 72px;
  }

  .banner-image-cell {
    display: flex;
    align-items: center;
    gap: 10px;
  }

  .banner-thumb {
    width: 112px;
    height: 64px;
    overflow: hidden;
    border: 1px solid var(--art-border-color);
    border-radius: 8px;
    background: #f4f8f5;
  }

  .banner-thumb__fallback {
    display: flex;
    align-items: center;
    justify-content: center;
    width: 100%;
    height: 100%;
    color: var(--art-text-gray-600);
    font-size: 12px;
    background: var(--art-bg-color);
  }
</style>
