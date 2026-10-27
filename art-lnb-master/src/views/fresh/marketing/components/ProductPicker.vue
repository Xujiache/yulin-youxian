<template>
  <div class="product-picker">
    <div v-if="productId" class="product-picker__selection">
      <ElImage
        v-if="displayImage"
        class="product-picker__thumb"
        :src="assetUrl(displayImage)"
        fit="cover"
      />
      <div v-else class="product-picker__thumb product-picker__thumb--empty">
        <ArtSvgIcon icon="ri:gift-line" />
      </div>
      <div class="product-picker__copy">
        <strong>{{ displayName || `商品 #${productId}` }}</strong>
        <span>
          商品 ID {{ productId }}
          <template v-if="skuId"> · SKU {{ skuId }}</template>
          <template v-if="skuName"> · {{ skuName }}</template>
        </span>
      </div>
      <ElButton link type="primary" @click="openPicker">更换</ElButton>
    </div>
    <ElButton v-else plain class="product-picker__empty-button" @click="openPicker">
      <ArtSvgIcon icon="ri:shopping-basket-2-line" />
      选择商品与 SKU
    </ElButton>

    <ElDialog
      v-model="visible"
      title="选择赠品商品"
      width="880px"
      append-to-body
      destroy-on-close
      class="product-picker-dialog"
    >
      <div class="picker-lead">
        <ArtSvgIcon icon="ri:information-line" />
        <span>只建议选择上架且有库存的商品；多规格商品必须明确选择一个可用 SKU。</span>
      </div>

      <div class="picker-layout">
        <section class="product-column">
          <div class="picker-section-head">
            <div>
              <strong>1. 选择商品</strong>
              <span>{{ filteredProducts.length }} 件可查看</span>
            </div>
            <ElButton link :loading="loading" @click="loadProducts">刷新</ElButton>
          </div>
          <ElInput
            v-model.trim="keyword"
            clearable
            placeholder="搜索商品名称、副标题或 ID"
            class="product-search"
          >
            <template #prefix><ArtSvgIcon icon="ri:search-line" /></template>
          </ElInput>

          <ElScrollbar height="390px" class="product-list-scroll">
            <div v-loading="loading" class="product-list">
              <button
                v-for="product in filteredProducts"
                :key="product.id"
                type="button"
                class="product-option"
                :class="{ 'is-selected': Number(product.id) === draftProductId }"
                @click="selectProduct(product)"
              >
                <ElImage
                  v-if="product.imageUrl"
                  class="product-option__image"
                  :src="assetUrl(product.imageUrl)"
                  fit="cover"
                />
                <div v-else class="product-option__image product-option__image--empty">
                  <ArtSvgIcon icon="ri:image-line" />
                </div>
                <div class="product-option__copy">
                  <strong>{{ product.name || `商品 #${product.id}` }}</strong>
                  <span>{{ product.subtitle || '未填写商品副标题' }}</span>
                  <div>
                    <ElTag
                      size="small"
                      effect="light"
                      :type="product.status === 1 ? 'success' : 'info'"
                    >
                      {{ product.status === 1 ? '上架中' : '已下架' }}
                    </ElTag>
                    <small>{{ product.skuEnabled ? '多规格' : '单规格' }}</small>
                  </div>
                </div>
                <ArtSvgIcon
                  v-if="Number(product.id) === draftProductId"
                  icon="ri:checkbox-circle-fill"
                  class="product-option__check"
                />
              </button>
              <ElEmpty
                v-if="!loading && filteredProducts.length === 0"
                description="没有匹配的商品"
                :image-size="72"
              />
            </div>
          </ElScrollbar>
        </section>

        <section class="sku-column">
          <div class="picker-section-head">
            <div>
              <strong>2. 选择规格</strong>
              <span v-if="selectedProduct">{{ selectedProduct.name }}</span>
              <span v-else>请先从左侧选择商品</span>
            </div>
          </div>

          <div v-if="!selectedProduct" class="sku-placeholder">
            <ArtSvgIcon icon="ri:git-branch-line" />
            <span>商品选定后，这里会显示可用规格。</span>
          </div>
          <div v-else-if="!selectedProduct.skuEnabled" class="single-sku-card">
            <ArtSvgIcon icon="ri:checkbox-circle-fill" />
            <div>
              <strong>使用商品默认规格</strong>
              <span>该商品未开启多规格，无需额外绑定 SKU。</span>
            </div>
          </div>
          <div v-else v-loading="detailLoading" class="sku-list">
            <button
              v-for="sku in availableSkus"
              :key="sku.id"
              type="button"
              class="sku-option"
              :class="{ 'is-selected': Number(sku.id) === draftSkuId }"
              :disabled="sku.status !== 1"
              @click="draftSkuId = Number(sku.id)"
            >
              <div>
                <strong>{{ sku.specificationText || sku.skuCode || `SKU #${sku.id}` }}</strong>
                <span>
                  {{ money(sku.unitPrice) }} · 库存 {{ Number(sku.stockQty || 0) }}
                  {{ sku.saleUnit || selectedProduct.saleUnit || '' }}
                </span>
              </div>
              <ElTag
                size="small"
                effect="plain"
                :type="sku.status === 1 && Number(sku.stockQty || 0) > 0 ? 'success' : 'info'"
              >
                {{
                  sku.status !== 1
                    ? '已停用'
                    : Number(sku.stockQty || 0) > 0
                      ? '可选择'
                      : '库存为 0'
                }}
              </ElTag>
            </button>
            <ElEmpty
              v-if="!detailLoading && availableSkus.length === 0"
              description="该商品没有 SKU 数据"
              :image-size="72"
            />
          </div>
        </section>
      </div>

      <template #footer>
        <div class="picker-footer">
          <span v-if="confirmHint">{{ confirmHint }}</span>
          <span v-else>将同步商品主图；奖项名称仍可在表格中单独修改。</span>
          <div>
            <ElButton @click="visible = false">取消</ElButton>
            <ElButton type="primary" :disabled="confirmDisabled" @click="confirmSelection">
              确认选择
            </ElButton>
          </div>
        </div>
      </template>
    </ElDialog>
  </div>
</template>

<script setup lang="ts">
  import { ElMessage } from 'element-plus'
  import { getProduct, getProducts, type Product, type ProductSku } from '@/api/admin'
  import { resolveFreshAssetUrl } from '@/utils/fresh-assets'

  interface ProductPickerSelection {
    productId: number
    skuId: number | null
    productName: string
    skuName: string
    imageUrl: string
  }

  const props = withDefaults(
    defineProps<{
      productId?: number | null
      skuId?: number | null
      productName?: string
      skuName?: string
      imageUrl?: string
    }>(),
    {
      productId: null,
      skuId: null,
      productName: '',
      skuName: '',
      imageUrl: ''
    }
  )

  const emit = defineEmits<{
    select: [selection: ProductPickerSelection]
  }>()

  const visible = ref(false)
  const loading = ref(false)
  const detailLoading = ref(false)
  const keyword = ref('')
  const products = ref<Product[]>([])
  const draftProductId = ref<number | null>(null)
  const draftSkuId = ref<number | null>(null)

  const normalizedProduct = (product: Product): Product => ({
    ...product,
    specGroups: Array.isArray(product.specGroups) ? product.specGroups : [],
    skus: Array.isArray(product.skus) ? product.skus : [],
    skuEnabled: Boolean(product.skuEnabled)
  })

  const filteredProducts = computed(() => {
    const query = keyword.value.trim().toLocaleLowerCase()
    if (!query) return products.value
    return products.value.filter((product) =>
      [product.id, product.name, product.subtitle, product.badge]
        .filter((value) => value !== null && value !== undefined)
        .join(' ')
        .toLocaleLowerCase()
        .includes(query)
    )
  })

  const selectedProduct = computed(
    () => products.value.find((product) => Number(product.id) === draftProductId.value) || null
  )
  const availableSkus = computed<ProductSku[]>(() =>
    Array.isArray(selectedProduct.value?.skus) ? selectedProduct.value.skus : []
  )
  const selectedSku = computed(
    () => availableSkus.value.find((sku) => Number(sku.id) === draftSkuId.value) || null
  )
  const cachedProduct = computed(
    () => products.value.find((product) => Number(product.id) === Number(props.productId)) || null
  )
  const cachedSku = computed(
    () => cachedProduct.value?.skus?.find((sku) => Number(sku.id) === Number(props.skuId)) || null
  )
  const displayName = computed(() => props.productName || cachedProduct.value?.name || '')
  const displayImage = computed(
    () => props.imageUrl || cachedSku.value?.imageUrl || cachedProduct.value?.imageUrl || ''
  )

  const confirmDisabled = computed(
    () =>
      !selectedProduct.value ||
      selectedProduct.value.status !== 1 ||
      (selectedProduct.value.skuEnabled && !selectedSku.value)
  )
  const confirmHint = computed(() => {
    if (!selectedProduct.value) return '请选择赠品商品'
    if (selectedProduct.value.status !== 1) return '已下架商品不能作为新赠品'
    if (selectedProduct.value.skuEnabled && !selectedSku.value) return '请选择一个可用 SKU'
    return ''
  })

  const assetUrl = resolveFreshAssetUrl
  const money = (value?: number | null) => `￥${(Number(value || 0) / 100).toFixed(2)}`

  const loadProducts = async () => {
    loading.value = true
    try {
      const result = await getProducts({})
      products.value = (Array.isArray(result?.items) ? result.items : []).map(normalizedProduct)
      if (
        draftProductId.value &&
        !products.value.some((product) => Number(product.id) === draftProductId.value)
      ) {
        const current = await getProduct(draftProductId.value)
        products.value.unshift(normalizedProduct(current))
      }
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '商品列表加载失败')
    } finally {
      loading.value = false
    }
  }

  const hydrateProduct = async (id: number) => {
    detailLoading.value = true
    try {
      const detail = normalizedProduct(await getProduct(id))
      const index = products.value.findIndex((product) => Number(product.id) === id)
      if (index >= 0) products.value.splice(index, 1, detail)
      else products.value.unshift(detail)
      return detail
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '商品规格加载失败')
      return products.value.find((product) => Number(product.id) === id) || null
    } finally {
      detailLoading.value = false
    }
  }

  const chooseDefaultSku = (product: Product, preferredSkuId?: number | null) => {
    if (!product.skuEnabled) {
      draftSkuId.value = null
      return
    }
    const skus = Array.isArray(product.skus) ? product.skus : []
    const preferred = skus.find(
      (sku) => Number(sku.id) === Number(preferredSkuId) && sku.status === 1
    )
    const fallback =
      skus.find((sku) => sku.defaultSku && sku.status === 1) || skus.find((sku) => sku.status === 1)
    draftSkuId.value = preferred ? Number(preferred.id) : fallback ? Number(fallback.id) : null
  }

  const selectProduct = async (product: Product) => {
    if (!product.id) return
    draftProductId.value = Number(product.id)
    draftSkuId.value = null
    const detail = await hydrateProduct(Number(product.id))
    if (detail) chooseDefaultSku(detail)
  }

  const openPicker = async () => {
    visible.value = true
    keyword.value = ''
    draftProductId.value = props.productId ? Number(props.productId) : null
    draftSkuId.value = props.skuId ? Number(props.skuId) : null
    await loadProducts()
    if (draftProductId.value) {
      const detail = await hydrateProduct(draftProductId.value)
      if (detail) chooseDefaultSku(detail, props.skuId)
    }
  }

  const confirmSelection = () => {
    const product = selectedProduct.value
    if (!product?.id || confirmDisabled.value) return
    const sku = selectedSku.value
    emit('select', {
      productId: Number(product.id),
      skuId: sku?.id ? Number(sku.id) : null,
      productName: product.name || `商品 #${product.id}`,
      skuName: sku?.specificationText || sku?.skuCode || '',
      imageUrl: sku?.imageUrl || product.imageUrl || ''
    })
    visible.value = false
  }
</script>

<style scoped lang="scss">
  .product-picker {
    min-width: 250px;
  }

  .product-picker__selection {
    display: flex;
    min-width: 0;
    align-items: center;
    gap: 9px;
    padding: 8px;
    border: 1px solid var(--el-border-color);
    border-radius: 9px;
    background: var(--el-fill-color-lighter);
  }

  .product-picker__thumb {
    display: grid;
    width: 38px;
    height: 38px;
    flex: none;
    overflow: hidden;
    place-items: center;
    border-radius: 8px;
    color: var(--el-color-primary);
    background: var(--el-color-primary-light-9);
  }

  .product-picker__copy {
    display: grid;
    min-width: 0;
    flex: 1;
    gap: 2px;

    strong,
    span {
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    strong {
      color: var(--el-text-color-primary);
      font-size: 13px;
    }

    span {
      color: var(--el-text-color-secondary);
      font-size: 11px;
    }
  }

  .product-picker__empty-button {
    width: 100%;
    border-style: dashed;
  }

  .picker-lead {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 10px 12px;
    margin-bottom: 14px;
    border-radius: 9px;
    color: var(--el-color-primary);
    font-size: 13px;
    background: var(--el-color-primary-light-9);
  }

  .picker-layout {
    display: grid;
    grid-template-columns: minmax(0, 1.15fr) minmax(280px, 0.85fr);
    gap: 14px;
  }

  .product-column,
  .sku-column {
    min-width: 0;
    padding: 14px;
    border: 1px solid var(--el-border-color);
    border-radius: 12px;
    background: var(--el-bg-color);
  }

  .picker-section-head {
    display: flex;
    min-height: 38px;
    align-items: flex-start;
    justify-content: space-between;
    gap: 12px;
    margin-bottom: 10px;

    > div {
      display: grid;
      min-width: 0;
      gap: 3px;
    }

    strong {
      color: var(--el-text-color-primary);
      font-size: 14px;
    }

    span {
      overflow: hidden;
      color: var(--el-text-color-secondary);
      font-size: 12px;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
  }

  .product-search {
    margin-bottom: 10px;
  }

  .product-list {
    display: grid;
    min-height: 360px;
    align-content: start;
    gap: 8px;
    padding-right: 5px;
  }

  .product-option,
  .sku-option {
    width: 100%;
    border: 1px solid var(--el-border-color-lighter);
    color: inherit;
    font: inherit;
    text-align: left;
    cursor: pointer;
    background: var(--el-fill-color-lighter);
    transition:
      border-color 0.16s ease,
      background-color 0.16s ease;

    &:hover,
    &.is-selected {
      border-color: var(--el-color-primary-light-5);
      background: var(--el-color-primary-light-9);
    }

    &:focus-visible {
      outline: 2px solid var(--el-color-primary-light-3);
      outline-offset: 2px;
    }
  }

  .product-option {
    position: relative;
    display: grid;
    grid-template-columns: 52px minmax(0, 1fr);
    gap: 10px;
    padding: 9px;
    border-radius: 10px;

    &__image {
      display: grid;
      width: 52px;
      height: 52px;
      overflow: hidden;
      place-items: center;
      border-radius: 9px;
      color: var(--el-text-color-secondary);
      background: var(--el-fill-color);
    }

    &__copy {
      display: grid;
      min-width: 0;
      gap: 3px;

      strong,
      > span {
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }

      strong {
        color: var(--el-text-color-primary);
        font-size: 13px;
      }

      > span,
      small {
        color: var(--el-text-color-secondary);
        font-size: 11px;
      }

      > div {
        display: flex;
        align-items: center;
        gap: 8px;
      }
    }

    &__check {
      position: absolute;
      top: 8px;
      right: 8px;
      color: var(--el-color-primary);
      font-size: 18px;
    }
  }

  .sku-list {
    display: grid;
    align-content: start;
    gap: 8px;
    min-height: 390px;
  }

  .sku-option {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 10px;
    padding: 12px;
    border-radius: 10px;

    &:disabled {
      cursor: not-allowed;
      opacity: 0.6;
    }

    > div {
      display: grid;
      min-width: 0;
      gap: 4px;

      strong {
        color: var(--el-text-color-primary);
        font-size: 13px;
      }

      span {
        color: var(--el-text-color-secondary);
        font-size: 11px;
      }
    }
  }

  .sku-placeholder,
  .single-sku-card {
    display: grid;
    min-height: 390px;
    place-content: center;
    justify-items: center;
    gap: 10px;
    padding: 24px;
    border: 1px dashed var(--el-border-color);
    border-radius: 10px;
    color: var(--el-text-color-secondary);
    text-align: center;

    > svg {
      color: var(--el-color-primary);
      font-size: 30px;
    }
  }

  .single-sku-card > div {
    display: grid;
    gap: 4px;

    strong {
      color: var(--el-text-color-primary);
    }

    span {
      font-size: 12px;
    }
  }

  .picker-footer {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 16px;

    > span {
      color: var(--el-text-color-secondary);
      font-size: 12px;
      text-align: left;
    }
  }

  @media (max-width: 760px) {
    .picker-layout {
      grid-template-columns: 1fr;
    }

    .picker-footer {
      align-items: flex-end;
      flex-direction: column;
    }
  }
</style>
