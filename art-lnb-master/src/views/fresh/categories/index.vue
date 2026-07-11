<template>
  <div class="fresh-page">
    <div class="fresh-page__head">
      <div>
        <h1 class="fresh-page__title">分类管理</h1>
        <p class="fresh-page__desc">维护小程序商品分类和展示排序。</p>
      </div>
      <ElButton type="primary" @click="openCreate">新增分类</ElButton>
    </div>

    <ElCard class="fresh-card" shadow="never">
      <div class="fresh-toolbar">
        <div class="fresh-toolbar__left">
          <ElButton :loading="loading" @click="loadCategories">刷新</ElButton>
        </div>
      </div>

      <ElTable v-loading="loading" :data="categories" border empty-text="暂无分类">
        <ElTableColumn label="分类图标" width="110">
          <template #default="{ row }">
            <ElImage
              v-if="row.iconUrl"
              class="category-icon"
              :src="assetUrl(row.iconUrl)"
              fit="cover"
              :preview-src-list="[assetUrl(row.iconUrl)]"
              preview-teleported
            />
            <div v-else class="category-icon category-icon--empty">无图</div>
          </template>
        </ElTableColumn>
        <ElTableColumn prop="name" label="分类名称" min-width="180" />
        <ElTableColumn prop="sortOrder" label="排序值" width="120" />
        <ElTableColumn label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <ElButton size="small" @click="openEdit(row)">编辑</ElButton>
            <ElButton size="small" type="danger" plain @click="removeCategory(row)">删除</ElButton>
          </template>
        </ElTableColumn>
      </ElTable>
    </ElCard>

    <ElDialog v-model="dialogVisible" :title="form.id ? '编辑分类' : '新增分类'" width="420px">
      <ElForm :model="form" label-width="90px">
        <ElFormItem label="分类名称" required>
          <ElInput v-model.trim="form.name" maxlength="20" show-word-limit />
        </ElFormItem>
        <ElFormItem label="分类图标">
          <div class="upload-row">
            <ElImage v-if="form.iconUrl" class="upload-preview" :src="assetUrl(form.iconUrl)" fit="cover" />
            <div v-else class="upload-preview upload-preview--empty">待上传</div>
            <ElUpload
              accept=".jpg,.jpeg,.png,.webp"
              :show-file-list="false"
              :http-request="uploadIcon"
            >
              <ElButton :loading="uploading">上传图标</ElButton>
            </ElUpload>
          </div>
        </ElFormItem>
        <ElFormItem label="排序值" required>
          <ElInputNumber v-model="form.sortOrder" :min="0" :step="10" class="form-full" />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="dialogVisible = false">取消</ElButton>
        <ElButton type="primary" :loading="saving" @click="saveCategory">保存</ElButton>
      </template>
    </ElDialog>
  </div>
</template>

<script setup lang="ts">
  import { ElMessage, ElMessageBox, type UploadRequestOptions } from 'element-plus'
  import {
    createCategory,
    deleteCategory,
    getCategories,
    updateCategory,
    uploadCategoryImage,
    type Category
  } from '@/api/admin'
  import { resolveFreshAssetUrl } from '@/utils/fresh-assets'

  defineOptions({ name: 'FreshCategories' })

  const loading = ref(false)
  const saving = ref(false)
  const uploading = ref(false)
  const dialogVisible = ref(false)
  const categories = ref<Category[]>([])
  const form = reactive<Category>({
    id: undefined,
    name: '',
    sortOrder: 10,
    iconUrl: ''
  })

  const resetForm = () => {
    Object.assign(form, {
      id: undefined,
      name: '',
      sortOrder: (categories.value.length + 1) * 10,
      iconUrl: ''
    })
  }

  const loadCategories = async () => {
    loading.value = true
    try {
      categories.value = await getCategories()
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '分类加载失败')
    } finally {
      loading.value = false
    }
  }

  const openCreate = () => {
    resetForm()
    dialogVisible.value = true
  }

  const openEdit = (row: Category) => {
    Object.assign(form, row)
    dialogVisible.value = true
  }

  const assetUrl = resolveFreshAssetUrl

  const uploadIcon = async (options: UploadRequestOptions) => {
    uploading.value = true
    try {
      const result = await uploadCategoryImage(options.file)
      form.iconUrl = result.url
      options.onSuccess(result)
      ElMessage.success('分类图标已上传')
    } catch (error) {
      options.onError(error as any)
      ElMessage.error(error instanceof Error ? error.message : '分类图标上传失败')
    } finally {
      uploading.value = false
    }
  }

  const saveCategory = async () => {
    if (!form.name) {
      ElMessage.warning('请填写分类名称')
      return
    }
    saving.value = true
    try {
      if (form.id) {
        await updateCategory(form.id, form)
      } else {
        await createCategory(form)
      }
      dialogVisible.value = false
      await loadCategories()
      ElMessage.success('分类已保存')
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '分类保存失败')
    } finally {
      saving.value = false
    }
  }

  const removeCategory = async (row: Category) => {
    if (!row.id) return
    await ElMessageBox.confirm(`确认删除分类「${row.name}」？`, '删除分类', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消'
    })
    await deleteCategory(row.id)
    await loadCategories()
    ElMessage.success('分类已删除')
  }

  onMounted(loadCategories)
</script>

<style scoped lang="scss">
  @use '../style.scss';

  .category-icon,
  .upload-preview {
    width: 64px;
    height: 64px;
    overflow: hidden;
    border: 1px solid var(--art-border-color);
    border-radius: 8px;
    background: #f4f8f5;
  }

  .category-icon--empty,
  .upload-preview--empty {
    display: flex;
    align-items: center;
    justify-content: center;
    color: var(--art-text-gray-600);
    font-size: 12px;
  }

  .upload-row {
    display: flex;
    align-items: center;
    gap: 14px;
  }
</style>
