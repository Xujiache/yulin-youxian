<template>
  <div class="fresh-page">
    <div class="fresh-page__head">
      <div>
        <h1 class="fresh-page__title">商品管理</h1>
        <p class="fresh-page__desc">按重量、起购量、步进值、库存和上下架状态维护商品。</p>
      </div>
      <ElButton type="primary" @click="openCreate">新增商品</ElButton>
    </div>

    <ElCard class="fresh-card" shadow="never">
      <div class="fresh-toolbar">
        <div class="fresh-toolbar__left">
          <ElSelect
            v-model="query.categoryId"
            clearable
            placeholder="全部分类"
            style="width: 180px"
            @change="loadProducts"
          >
            <ElOption
              v-for="item in categories"
              :key="item.id"
              :label="item.name"
              :value="item.id || 0"
            />
          </ElSelect>
          <ElButton :loading="loading" @click="loadProducts">刷新</ElButton>
        </div>
        <div class="fresh-toolbar__right">
          <ElButton @click="goCategories">管理分类</ElButton>
        </div>
      </div>

      <ElTable v-loading="loading" :data="products" border empty-text="暂无商品">
        <ElTableColumn label="图片" width="86">
          <template #default="{ row }">
            <ElImage
              v-if="row.imageUrl"
              class="image-thumb"
              :src="imageUrl(row.imageUrl)"
              fit="cover"
              :preview-src-list="[imageUrl(row.imageUrl)]"
              preview-teleported
            />
            <div v-else class="image-thumb empty-thumb">无图</div>
          </template>
        </ElTableColumn>
        <ElTableColumn prop="name" label="商品名称" min-width="180">
          <template #default="{ row }">
            <div>
              <strong>{{ row.name }}</strong>
              <div class="muted">{{ row.subtitle || '未填写副标题' }}</div>
            </div>
          </template>
        </ElTableColumn>
        <ElTableColumn label="分类" width="120">
          <template #default="{ row }">{{ categoryName(row.categoryId) }}</template>
        </ElTableColumn>
        <ElTableColumn label="价格" width="130">
          <template #default="{ row }">
            <span class="money">{{ money(row.unitPrice) }}/{{ row.saleUnit }}</span>
          </template>
        </ElTableColumn>
        <ElTableColumn label="重量规则" min-width="170">
          <template #default="{ row }">
            起购 {{ row.minPurchaseQty }}{{ row.saleUnit }}，每次 {{ row.stepQty }}{{ row.saleUnit }}
          </template>
        </ElTableColumn>
        <ElTableColumn label="库存" width="170">
          <template #default="{ row }">
            <ElInputNumber
              :model-value="Number(row.stockQty || 0)"
              :min="0"
              :step="Number(row.stepQty || 0.5)"
              size="small"
              style="width: 130px"
              @change="(value) => changeStock(row, Number(value || 0))"
            />
          </template>
        </ElTableColumn>
        <ElTableColumn label="状态" width="100">
          <template #default="{ row }">
            <ElTag :type="row.status === 1 ? 'success' : 'info'">
              {{ row.status === 1 ? '上架中' : '已下架' }}
            </ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="操作" width="230" fixed="right">
          <template #default="{ row }">
            <ElButton size="small" @click="openEdit(row)">编辑</ElButton>
            <ElButton size="small" @click="toggleStatus(row)">
              {{ row.status === 1 ? '下架' : '上架' }}
            </ElButton>
            <ElButton size="small" type="danger" plain @click="removeProduct(row)">删除</ElButton>
          </template>
        </ElTableColumn>
      </ElTable>
    </ElCard>

    <ElDialog v-model="dialogVisible" :title="form.id ? '编辑商品' : '新增商品'" width="680px">
      <ElForm :model="form" label-width="112px">
        <ElFormItem label="商品分类" required>
          <ElSelect v-model="form.categoryId" placeholder="请选择分类" class="form-full">
            <ElOption
              v-for="item in categories"
              :key="item.id"
              :label="item.name"
              :value="item.id || 0"
            />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="商品名称" required>
          <ElInput v-model.trim="form.name" maxlength="40" show-word-limit />
        </ElFormItem>
        <ElFormItem label="副标题">
          <ElInput v-model.trim="form.subtitle" maxlength="60" show-word-limit />
        </ElFormItem>
        <ElFormItem label="商品图片" required>
          <div class="upload-row">
            <ElImage
              v-if="form.imageUrl"
              class="upload-preview"
              :src="imageUrl(form.imageUrl)"
              fit="cover"
            />
            <div v-else class="upload-preview upload-placeholder">待上传</div>
            <ElUpload
              accept=".jpg,.jpeg,.png,.webp"
              :show-file-list="false"
              :http-request="uploadImage"
            >
              <ElButton :loading="uploading">上传图片</ElButton>
            </ElUpload>
          </div>
        </ElFormItem>
        <ElFormItem label="销售单位" required>
          <ElInput v-model.trim="form.saleUnit" placeholder="斤、份、盒等" />
        </ElFormItem>
        <ElFormItem label="单价（分）" required>
          <ElInputNumber v-model="form.unitPrice" :min="1" class="form-full" />
        </ElFormItem>
        <ElFormItem label="起购重量" required>
          <ElInputNumber v-model="form.minPurchaseQty" :min="0.001" :step="0.5" class="form-full" />
        </ElFormItem>
        <ElFormItem label="步进重量" required>
          <ElInputNumber v-model="form.stepQty" :min="0.001" :step="0.5" class="form-full" />
        </ElFormItem>
        <ElFormItem label="库存" required>
          <ElInputNumber v-model="form.stockQty" :min="0" :step="0.5" class="form-full" />
        </ElFormItem>
        <ElFormItem label="商品标签">
          <ElInput v-model.trim="form.badge" placeholder="热销、新鲜、今日到店等" />
        </ElFormItem>
        <ElFormItem label="商品状态">
          <ElSwitch v-model="form.status" :active-value="1" :inactive-value="0" />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="dialogVisible = false">取消</ElButton>
        <ElButton type="primary" :loading="saving" @click="saveProduct">保存</ElButton>
      </template>
    </ElDialog>
  </div>
</template>

<script setup lang="ts">
  import { ElMessage, ElMessageBox, type UploadRequestOptions } from 'element-plus'
  import {
    createProduct,
    deleteProduct,
    getCategories,
    getProducts,
    updateProduct,
    updateProductStatus,
    updateProductStock,
    uploadProductImage,
    type Category,
    type Product
  } from '@/api/admin'
  import { resolveFreshAssetUrl } from '@/utils/fresh-assets'

  defineOptions({ name: 'FreshProducts' })

  const router = useRouter()
  const loading = ref(false)
  const saving = ref(false)
  const uploading = ref(false)
  const dialogVisible = ref(false)
  const categories = ref<Category[]>([])
  const products = ref<Product[]>([])
  const query = reactive<{ categoryId?: number | null }>({ categoryId: null })
  const form = reactive<Product>(emptyForm())

  function emptyForm(): Product {
    return {
      id: undefined,
      categoryId: null,
      name: '',
      subtitle: '',
      imageUrl: '',
      saleUnit: '斤',
      unitPrice: 1,
      minPurchaseQty: 0.5,
      stepQty: 0.5,
      stockQty: 0,
      badge: '',
      status: 1
    }
  }

  const money = (value: number) => `￥${(Number(value || 0) / 100).toFixed(2)}`
  const imageUrl = resolveFreshAssetUrl

  const categoryName = (categoryId: number) =>
    categories.value.find((item) => item.id === categoryId)?.name || '未分类'

  const loadCategories = async () => {
    categories.value = await getCategories()
  }

  const loadProducts = async () => {
    loading.value = true
    try {
      const result = await getProducts({ categoryId: query.categoryId })
      products.value = result.items || []
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '商品加载失败')
    } finally {
      loading.value = false
    }
  }

  const loadAll = async () => {
    await loadCategories()
    await loadProducts()
  }

  const openCreate = () => {
    Object.assign(form, emptyForm(), { categoryId: categories.value[0]?.id || null })
    dialogVisible.value = true
  }

  const openEdit = (row: Product) => {
    Object.assign(form, emptyForm(), row)
    dialogVisible.value = true
  }

  const uploadImage = async (options: UploadRequestOptions) => {
    uploading.value = true
    try {
      const result = await uploadProductImage(options.file)
      form.imageUrl = result.url
      options.onSuccess(result)
      ElMessage.success('图片已上传')
    } catch (error) {
      options.onError(error as any)
      ElMessage.error(error instanceof Error ? error.message : '图片上传失败')
    } finally {
      uploading.value = false
    }
  }

  const validateForm = () => {
    if (!form.categoryId) return '请选择商品分类'
    if (!form.name) return '请填写商品名称'
    if (!form.imageUrl) return '请上传商品图片'
    if (!form.saleUnit) return '请填写销售单位'
    return ''
  }

  const saveProduct = async () => {
    const message = validateForm()
    if (message) {
      ElMessage.warning(message)
      return
    }
    saving.value = true
    try {
      if (form.id) {
        await updateProduct(form.id, form)
      } else {
        await createProduct(form)
      }
      dialogVisible.value = false
      await loadProducts()
      ElMessage.success('商品已保存')
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '商品保存失败')
    } finally {
      saving.value = false
    }
  }

  const changeStock = async (row: Product, stockQty: number) => {
    if (!row.id) return
    await updateProductStock(row.id, stockQty)
    await loadProducts()
    ElMessage.success('库存已更新')
  }

  const toggleStatus = async (row: Product) => {
    if (!row.id) return
    await updateProductStatus(row.id, row.status === 1 ? 0 : 1)
    await loadProducts()
  }

  const removeProduct = async (row: Product) => {
    if (!row.id) return
    await ElMessageBox.confirm(`确认删除商品「${row.name}」？`, '删除商品', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消'
    })
    await deleteProduct(row.id)
    await loadProducts()
    ElMessage.success('商品已删除')
  }

  const goCategories = () => {
    router.push('/fresh/categories')
  }

  onMounted(loadAll)
</script>

<style scoped lang="scss">
  @use '../style.scss';

  .upload-row {
    display: flex;
    align-items: center;
    gap: 14px;
  }

  .upload-preview {
    width: 96px;
    height: 96px;
    overflow: hidden;
    border: 1px solid var(--art-border-color);
    border-radius: 10px;
    background: #f4f8f5;
  }

  .upload-placeholder {
    display: flex;
    align-items: center;
    justify-content: center;
    color: var(--art-gray-500);
  }
</style>
