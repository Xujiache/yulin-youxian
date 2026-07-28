<template>
  <div class="fresh-page">
    <div class="fresh-page__head">
      <div>
        <h1 class="fresh-page__title">商品管理</h1>
        <p class="fresh-page__desc">维护商品图片、价格、库存、上下架状态和首页今日推荐。</p>
      </div>
      <ElButton type="primary" @click="openCreate">新增商品</ElButton>
    </div>

    <ElCard class="fresh-card" shadow="never">
      <div class="fresh-toolbar">
        <div class="fresh-toolbar__left">
          <ElInput
            v-model.trim="query.keyword"
            clearable
            placeholder="搜索商品名称或副标题"
            style="width: 260px"
          />
          <ElSelect
            v-model="query.categoryId"
            clearable
            placeholder="全部分类"
            style="width: 180px"
          >
            <ElOption
              v-for="item in categories"
              :key="item.id"
              :label="item.name"
              :value="item.id || 0"
            />
          </ElSelect>
          <ElSelect v-model="query.status" clearable placeholder="全部状态" style="width: 130px">
            <ElOption label="上架中" value="on-sale" />
            <ElOption label="已下架" value="off-sale" />
          </ElSelect>
          <ElSelect v-model="query.recommended" clearable placeholder="首页推荐" style="width: 130px">
            <ElOption label="仅看推荐" value="recommended" />
            <ElOption label="普通商品" value="normal" />
          </ElSelect>
          <ElSelect v-model="query.stock" clearable placeholder="全部库存" style="width: 130px">
            <ElOption label="有库存" value="in-stock" />
            <ElOption label="已售罄" value="sold-out" />
            <ElOption label="低库存（≤10）" value="low-stock" />
          </ElSelect>
        </div>
        <div class="fresh-toolbar__left fresh-toolbar__filters">
          <span class="filter-label">价格（元）</span>
          <ElInputNumber v-model="query.minPrice" :min="0" :precision="2" controls-position="right" placeholder="最低" style="width: 120px" />
          <span class="filter-separator">至</span>
          <ElInputNumber v-model="query.maxPrice" :min="0" :precision="2" controls-position="right" placeholder="最高" style="width: 120px" />
          <ElSelect v-model="query.sort" style="width: 160px">
            <ElOption label="默认排序" value="default" />
            <ElOption label="价格从低到高" value="price-asc" />
            <ElOption label="价格从高到低" value="price-desc" />
            <ElOption label="库存从低到高" value="stock-asc" />
            <ElOption label="库存从高到低" value="stock-desc" />
            <ElOption label="名称 A-Z" value="name-asc" />
          </ElSelect>
          <span class="filter-result">匹配 {{ filteredProducts.length }} / {{ products.length }} 件</span>
          <ElButton @click="resetFilters">重置筛选</ElButton>
          <ElButton :loading="loading" @click="loadProducts">刷新数据</ElButton>
        </div>
        <div class="fresh-toolbar__right">
          <ElButton @click="goCategories">管理分类</ElButton>
        </div>
      </div>

      <ElTable v-loading="loading" :data="filteredProducts" border empty-text="没有符合筛选条件的商品">
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
        <ElTableColumn label="首页推荐" width="110">
          <template #default="{ row }">
            <ElTag :type="row.recommended ? 'success' : 'info'" effect="light">
              {{ row.recommended ? '推荐' : '普通' }}
            </ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="重量规则" min-width="170">
          <template #default="{ row }">
            起购 {{ row.minPurchaseQty }}{{ row.saleUnit }}，每次 {{ row.stepQty }}{{ row.saleUnit }}
          </template>
        </ElTableColumn>
        <ElTableColumn label="小程序排序" width="150">
          <template #default="{ row }">
            <ElInputNumber
              :model-value="Number(row.sortOrder ?? 0)"
              :min="0"
              :precision="0"
              :step="1"
              size="small"
              style="width: 118px"
              @change="(value) => changeSortOrder(row, Number(value ?? 0))"
            />
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
        <ElFormItem label="单价（元）" required>
          <ElInputNumber
            :model-value="centToYuan(form.unitPrice)"
            :min="0.01"
            :precision="2"
            :step="0.1"
            class="form-full"
            @update:model-value="updateUnitPrice"
          />
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
        <ElFormItem label="小程序排序">
          <ElInputNumber
            v-model="form.sortOrder"
            :min="0"
            :precision="0"
            :step="1"
            placeholder="不填则排在末尾"
            class="form-full"
          />
        </ElFormItem>
        <ElFormItem label="首页今日推荐">
          <ElSwitch v-model="form.recommended" active-text="展示" inactive-text="不展示" />
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
    updateProductSortOrder,
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
  const query = reactive<{
    categoryId?: number | null
    keyword: string
    status: '' | 'on-sale' | 'off-sale'
    recommended: '' | 'recommended' | 'normal'
    stock: '' | 'in-stock' | 'sold-out' | 'low-stock'
    minPrice: number | undefined
    maxPrice: number | undefined
    sort: 'default' | 'price-asc' | 'price-desc' | 'stock-asc' | 'stock-desc' | 'name-asc'
  }>({
    categoryId: null,
    keyword: '',
    status: '',
    recommended: '',
    stock: '',
    minPrice: undefined,
    maxPrice: undefined,
    sort: 'default'
  })
  const form = reactive<Product>(emptyForm())

  function emptyForm(): Product {
    return {
      id: undefined,
      categoryId: null,
      name: '',
      subtitle: '',
      imageUrl: '',
      saleUnit: '斤',
      unitPrice: 100,
      minPurchaseQty: 0.5,
      stepQty: 0.5,
      stockQty: 0,
      badge: '',
      status: 1,
      recommended: false,
      sortOrder: null
    }
  }

  const centToYuan = (value: number) => Number((Number(value || 0) / 100).toFixed(2))
  const yuanToCent = (value: number) => Math.max(1, Math.round(Number(value || 0) * 100))
  const updateUnitPrice = (value: number | undefined) => {
    form.unitPrice = yuanToCent(Number(value || 0))
  }
  const money = (value: number) => `￥${centToYuan(value).toFixed(2)}`
  const imageUrl = resolveFreshAssetUrl

  const categoryName = (categoryId: number | null) =>
    categories.value.find((item) => item.id === categoryId)?.name || '未分类'

  const filteredProducts = computed(() => {
    const keyword = query.keyword.trim().toLocaleLowerCase()
    const minPrice = query.minPrice == null ? undefined : yuanToCent(query.minPrice)
    const maxPrice = query.maxPrice == null ? undefined : yuanToCent(query.maxPrice)
    const rows = products.value.filter((item) => {
      const stock = Number(item.stockQty || 0)
      const text = `${item.name || ''} ${item.subtitle || ''} ${item.badge || ''}`.toLocaleLowerCase()
      if (query.categoryId && item.categoryId !== query.categoryId) return false
      if (keyword && !text.includes(keyword)) return false
      if (query.status === 'on-sale' && item.status !== 1) return false
      if (query.status === 'off-sale' && item.status === 1) return false
      if (query.recommended === 'recommended' && !item.recommended) return false
      if (query.recommended === 'normal' && item.recommended) return false
      if (query.stock === 'in-stock' && stock <= 0) return false
      if (query.stock === 'sold-out' && stock > 0) return false
      if (query.stock === 'low-stock' && (stock <= 0 || stock > 10)) return false
      if (minPrice != null && Number(item.unitPrice || 0) < minPrice) return false
      if (maxPrice != null && Number(item.unitPrice || 0) > maxPrice) return false
      return true
    })

    return [...rows].sort((left, right) => {
      if (query.sort === 'price-asc') return Number(left.unitPrice || 0) - Number(right.unitPrice || 0)
      if (query.sort === 'price-desc') return Number(right.unitPrice || 0) - Number(left.unitPrice || 0)
      if (query.sort === 'stock-asc') return Number(left.stockQty || 0) - Number(right.stockQty || 0)
      if (query.sort === 'stock-desc') return Number(right.stockQty || 0) - Number(left.stockQty || 0)
      if (query.sort === 'name-asc') return left.name.localeCompare(right.name, 'zh-CN')
      return Number(left.sortOrder || 0) - Number(right.sortOrder || 0) || Number(left.id || 0) - Number(right.id || 0)
    })
  })

  const loadCategories = async () => {
    categories.value = await getCategories()
  }

  const loadProducts = async () => {
    loading.value = true
    try {
      const result = await getProducts({})
      products.value = (result.items || []).map((item) => ({
        ...item,
        recommended: Boolean(item.recommended)
      }))
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

  const resetFilters = () => {
    query.categoryId = null
    query.keyword = ''
    query.status = ''
    query.recommended = ''
    query.stock = ''
    query.minPrice = undefined
    query.maxPrice = undefined
    query.sort = 'default'
  }

  const openCreate = () => {
    Object.assign(form, emptyForm(), { categoryId: categories.value[0]?.id || null })
    dialogVisible.value = true
  }

  const openEdit = (row: Product) => {
    Object.assign(form, emptyForm(), row, { recommended: Boolean(row.recommended) })
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

  const changeSortOrder = async (row: Product, sortOrder: number) => {
    if (!row.id) return
    try {
      await updateProductSortOrder(row.id, sortOrder)
      await loadProducts()
      ElMessage.success('小程序排序已更新')
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '商品排序更新失败')
      await loadProducts()
    }
  }

  const toggleStatus = async (row: Product) => {
    if (!row.id) return
    await updateProductStatus(row.id, row.status === 1 ? 0 : 1)
    await loadProducts()
  }

  const removeProduct = async (row: Product) => {
    if (!row.id) return
    try {
      await ElMessageBox.confirm(
        `确认删除商品「${row.name}」？商品将从商城和购物车移除，历史订单记录仍会完整保留。`,
        '删除商品',
        {
          type: 'warning',
          confirmButtonText: '确认删除',
          cancelButtonText: '取消'
        }
      )
      await deleteProduct(row.id)
      await loadProducts()
      ElMessage.success('商品已删除，历史订单不受影响')
    } catch (error) {
      if (error === 'cancel' || error === 'close') return
      ElMessage.error(error instanceof Error ? error.message : '商品删除失败')
    }
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

  .fresh-toolbar {
    align-items: flex-start;
    flex-wrap: wrap;

    &__filters {
      width: 100%;
    }
  }

  .filter-label,
  .filter-separator,
  .filter-result {
    color: var(--art-gray-600);
    font-size: 13px;
    white-space: nowrap;
  }

  .filter-result {
    margin-left: auto;
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
