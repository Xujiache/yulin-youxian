<template>
  <div class="fresh-page">
    <div class="fresh-page__head">
      <div>
        <h1 class="fresh-page__title">商品管理</h1>
        <p class="fresh-page__desc">统一维护单规格与多规格商品、价格区间、库存和上架状态。</p>
      </div>
      <ElButton type="primary" @click="openCreate">
        <ArtSvgIcon icon="ri:add-line" class="button-icon" />
        新增商品
      </ElButton>
    </div>

    <ElCard class="fresh-card" shadow="never">
      <div class="fresh-toolbar">
        <div class="fresh-toolbar__left">
          <ElInput
            v-model.trim="query.keyword"
            clearable
            placeholder="搜索商品名称或副标题"
            style="width: 260px"
          >
            <template #prefix>
              <ArtSvgIcon icon="ri:search-line" />
            </template>
          </ElInput>
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
          <ElSelect
            v-model="query.recommended"
            clearable
            placeholder="首页推荐"
            style="width: 130px"
          >
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
          <ElInputNumber
            v-model="query.minPrice"
            :min="0"
            :precision="2"
            controls-position="right"
            placeholder="最低"
            style="width: 120px"
          />
          <span class="filter-separator">至</span>
          <ElInputNumber
            v-model="query.maxPrice"
            :min="0"
            :precision="2"
            controls-position="right"
            placeholder="最高"
            style="width: 120px"
          />
          <ElSelect v-model="query.sort" style="width: 160px">
            <ElOption label="默认排序" value="default" />
            <ElOption label="价格从低到高" value="price-asc" />
            <ElOption label="价格从高到低" value="price-desc" />
            <ElOption label="库存从低到高" value="stock-asc" />
            <ElOption label="库存从高到低" value="stock-desc" />
            <ElOption label="名称 A-Z" value="name-asc" />
          </ElSelect>
          <span class="filter-result">已加载 {{ products.length }} / {{ total }} 件</span>
          <ElButton @click="resetFilters">重置筛选</ElButton>
          <ElButton :loading="loading" @click="reloadProducts">刷新数据</ElButton>
        </div>
        <div class="fresh-toolbar__right">
          <span class="result-count">共 {{ total }} 件商品</span>
          <ElButton @click="goCategories">管理分类</ElButton>
        </div>
      </div>

      <div
        v-infinite-scroll="loadNextPage"
        :infinite-scroll-disabled="infiniteScrollDisabled"
        :infinite-scroll-distance="120"
        class="product-table-scroll"
      >
      <ElTable v-loading="loading && products.length === 0" :data="products" row-key="id" border empty-text="没有符合筛选条件的商品">
        <ElTableColumn label="商品" min-width="250" fixed="left">
          <template #default="{ row }">
            <div class="product-cell">
              <FreshImage
                v-if="row.imageUrl"
                class="image-thumb"
                :src="row.imageUrl"
                fit="cover"
              />
              <div v-else class="image-thumb empty-thumb">无图</div>
              <div class="product-copy">
                <strong>{{ row.name }}</strong>
                <span>{{ row.subtitle || '未填写副标题' }}</span>
              </div>
            </div>
          </template>
        </ElTableColumn>

        <ElTableColumn label="分类" width="118">
          <template #default="{ row }">{{ categoryName(row.categoryId) }}</template>
        </ElTableColumn>

        <ElTableColumn label="规格模式" width="148">
          <template #default="{ row }">
            <div class="spec-mode">
              <ElTag :type="row.skuEnabled ? 'success' : 'info'" effect="light">
                {{ row.skuEnabled ? '多规格' : '单规格' }}
              </ElTag>
              <span v-if="row.skuEnabled">{{ row.skus?.length || 0 }} 个组合</span>
            </div>
          </template>
        </ElTableColumn>

        <ElTableColumn label="售价" min-width="150">
          <template #default="{ row }">
            <strong class="money">{{ priceText(row) }}</strong>
            <div class="cell-caption">/{{ row.saleUnit }}</div>
          </template>
        </ElTableColumn>

        <ElTableColumn label="库存" min-width="160">
          <template #default="{ row }">
            <div v-if="row.skuEnabled" class="stock-summary">
              <strong>{{ row.stockQty }}{{ row.saleUnit }}</strong>
              <span>{{ row.availableSkuCount || 0 }} 个规格可售</span>
            </div>
            <ElInputNumber
              v-else
              :model-value="Number(row.stockQty || 0)"
              :min="0"
              :step="Number(row.stepQty || 0.5)"
              size="small"
              controls-position="right"
              style="width: 132px"
              @change="(value) => changeStock(row, Number(value || 0))"
            />
          </template>
        </ElTableColumn>

        <ElTableColumn label="小程序排序" width="142">
          <template #default="{ row }">
            <ElInputNumber
              :model-value="Number(row.sortOrder ?? 0)"
              :min="0"
              :precision="0"
              :step="1"
              size="small"
              controls-position="right"
              style="width: 112px"
              @change="(value) => changeSortOrder(row, Number(value ?? 0))"
            />
          </template>
        </ElTableColumn>

        <ElTableColumn label="推荐" width="90" align="center">
          <template #default="{ row }">
            <ElTag :type="row.recommended ? 'success' : 'info'" effect="plain">
              {{ row.recommended ? '今日推荐' : '普通' }}
            </ElTag>
          </template>
        </ElTableColumn>

        <ElTableColumn label="状态" width="96" align="center">
          <template #default="{ row }">
            <ElTag :type="row.status === 1 ? 'success' : 'danger'" effect="light">
              {{ row.status === 1 ? '上架中' : '已下架' }}
            </ElTag>
          </template>
        </ElTableColumn>

        <ElTableColumn label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <ElButton type="primary" link @click="openEdit(row)">编辑商品</ElButton>
            <ElButton link @click="toggleStatus(row)">
              {{ row.status === 1 ? '下架' : '上架' }}
            </ElButton>
            <ElDropdown trigger="click">
              <ElButton link>
                更多
                <ArtSvgIcon icon="ri:arrow-down-s-line" />
              </ElButton>
              <template #dropdown>
                <ElDropdownMenu>
                  <ElDropdownItem @click="removeProduct(row)">
                    <span class="danger-action">删除商品</span>
                  </ElDropdownItem>
                </ElDropdownMenu>
              </template>
            </ElDropdown>
          </template>
        </ElTableColumn>
      </ElTable>
        <div class="load-more-state">
          <ElButton v-if="loadError && hasMore" link type="primary" @click="loadNextPage">加载失败，点击重试</ElButton>
          <span v-else-if="loading && products.length > 0">正在加载更多商品…</span>
          <span v-else-if="hasMore">向下滚动加载更多</span>
          <span v-else-if="total > 0">已加载全部 {{ total }} 件商品</span>
        </div>
      </div>
    </ElCard>
  </div>
</template>

<script setup lang="ts">
  import { ElMessage, ElMessageBox } from 'element-plus'
  import {
    deleteProduct,
    getCategories,
    getProducts,
    updateProductSortOrder,
    updateProductStatus,
    updateProductStock,
    type Category,
    type Product
  } from '@/api/admin'
  import FreshImage from '@/components/business/fresh-image/index.vue'

  defineOptions({ name: 'FreshProducts' })

  const router = useRouter()
  const loading = ref(false)
  const categories = ref<Category[]>([])
  const products = ref<Product[]>([])
  const total = ref(0)
  const page = ref(0)
  const loadError = ref(false)
  let requestVersion = 0
  let filterTimer: ReturnType<typeof setTimeout> | undefined
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

  const centToYuan = (value: number) => Number((Number(value || 0) / 100).toFixed(2))
  const yuanToCent = (value: number) => Math.max(0, Math.round(Number(value || 0) * 100))
  const money = (value: number) => `￥${centToYuan(value).toFixed(2)}`
  const priceText = (product: Product) => {
    const min = Number(product.minUnitPrice ?? product.unitPrice)
    const max = Number(product.maxUnitPrice ?? product.unitPrice)
    return min === max ? money(min) : `${money(min)} – ${money(max)}`
  }

  const categoryName = (categoryId: number | null) =>
    categories.value.find((item) => item.id === categoryId)?.name || '未分类'

  const hasMore = computed(() => products.value.length < total.value)
  const infiniteScrollDisabled = computed(() => loading.value || !hasMore.value)

  const loadCategories = async () => {
    categories.value = await getCategories()
  }

  const normalizeProducts = (items: Product[]) => items.map((item) => ({
    ...item,
    skuEnabled: Boolean(item.skuEnabled),
    specGroups: item.specGroups || [],
    skus: item.skus || []
  }))

  const loadProducts = async (reset = false) => {
    if (!reset && (loading.value || !hasMore.value)) return
    const version = reset ? ++requestVersion : requestVersion
    const requestedPage = reset ? 1 : page.value + 1
    if (reset) {
      products.value = []
      total.value = 0
      page.value = 0
      loadError.value = false
    }
    loading.value = true
    try {
      const result = await getProducts({
        categoryId: query.categoryId,
        keyword: query.keyword,
        status: query.status || undefined,
        recommended: query.recommended || undefined,
        stock: query.stock || undefined,
        minPrice: query.minPrice == null ? undefined : yuanToCent(query.minPrice),
        maxPrice: query.maxPrice == null ? undefined : yuanToCent(query.maxPrice),
        sort: query.sort,
        page: requestedPage,
        pageSize: 30
      })
      if (version !== requestVersion) return
      const items = normalizeProducts(result.items || [])
      products.value = reset ? items : [...products.value, ...items.filter((item) => !products.value.some((existing) => existing.id === item.id))]
      total.value = result.total || 0
      page.value = result.page || requestedPage
      loadError.value = false
    } catch (error) {
      if (version !== requestVersion) return
      loadError.value = true
      ElMessage.error(error instanceof Error ? error.message : '商品加载失败')
    } finally {
      if (version === requestVersion) loading.value = false
    }
  }

  const reloadProducts = () => loadProducts(true)
  const loadNextPage = () => loadProducts(false)

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

  watch(query, () => {
    if (filterTimer) clearTimeout(filterTimer)
    filterTimer = setTimeout(reloadProducts, 350)
  }, { deep: true })
  onBeforeUnmount(() => filterTimer && clearTimeout(filterTimer))

  const openCreate = () => {
    router.push({ name: 'FreshProductEditor' })
  }

  const openEdit = (row: Product) => {
    router.push({ name: 'FreshProductEditor', query: { id: row.id } })
  }

  const changeStock = async (row: Product, stockQty: number) => {
    if (!row.id) return
    try {
      await updateProductStock(row.id, stockQty)
      await reloadProducts()
      ElMessage.success('库存已更新')
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '库存更新失败')
      await reloadProducts()
    }
  }

  const changeSortOrder = async (row: Product, sortOrder: number) => {
    if (!row.id) return
    try {
      await updateProductSortOrder(row.id, sortOrder)
      await reloadProducts()
      ElMessage.success('小程序排序已更新')
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '商品排序更新失败')
      await reloadProducts()
    }
  }

  const toggleStatus = async (row: Product) => {
    if (!row.id) return
    try {
      await updateProductStatus(row.id, row.status === 1 ? 0 : 1)
      await reloadProducts()
      ElMessage.success(row.status === 1 ? '商品已下架' : '商品已上架')
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '状态更新失败')
    }
  }

  const removeProduct = async (row: Product) => {
    if (!row.id) return
    try {
      await ElMessageBox.confirm(
        `删除商品「${row.name}」？商品会从商城和购物车移除，历史订单快照仍会保留。`,
        '删除商品',
        {
          type: 'warning',
          confirmButtonText: '删除商品',
          cancelButtonText: '保留商品'
        }
      )
      await deleteProduct(row.id)
      await reloadProducts()
      ElMessage.success('商品已删除')
    } catch (error) {
      if (error === 'cancel' || error === 'close') return
      ElMessage.error(error instanceof Error ? error.message : '商品删除失败')
    }
  }

  const goCategories = () => {
    router.push('/fresh/catalog/categories')
  }

  onMounted(async () => {
    await loadCategories()
    await reloadProducts()
  })
</script>

<style scoped lang="scss">
  @use '../style.scss';

  .button-icon {
    margin-right: 4px;
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

  .result-count,
  .cell-caption,
  .stock-summary span,
  .spec-mode span {
    color: var(--art-gray-500);
    font-size: 12px;
  }

  .product-cell {
    display: flex;
    align-items: center;
    gap: 12px;
  }

  .product-copy {
    display: flex;
    min-width: 0;
    flex-direction: column;
    gap: 5px;

    strong,
    span {
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    strong {
      color: var(--art-gray-900);
    }

    span {
      color: var(--art-gray-500);
      font-size: 12px;
    }
  }

  .spec-mode,
  .stock-summary {
    display: flex;
    align-items: flex-start;
    flex-direction: column;
    gap: 5px;
  }

  .stock-summary strong {
    color: var(--art-gray-900);
    font-variant-numeric: tabular-nums;
  }

  .danger-action {
    color: var(--el-color-danger);
  }

  .product-table-scroll {
    max-height: calc(100vh - 290px);
    min-height: 360px;
    overflow: auto;
  }

  .load-more-state {
    display: flex;
    min-height: 44px;
    align-items: center;
    justify-content: center;
    color: var(--art-gray-500);
    font-size: 13px;
  }

  :deep(.el-table__cell) {
    padding-top: 12px;
    padding-bottom: 12px;
  }
</style>
