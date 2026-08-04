<template>
  <div v-loading="loading" class="product-editor">
    <div class="editor-head">
      <div class="editor-head__copy">
        <ElButton text class="back-button" @click="goBack">
          <ArtSvgIcon icon="ri:arrow-left-line" />
          返回商品列表
        </ElButton>
        <div>
          <h1>{{ isEdit ? '编辑商品' : '新增商品' }}</h1>
          <p>商品基础信息和规格库存会同时保存，已产生的历史订单不会被改写。</p>
        </div>
      </div>
      <div class="editor-head__actions">
        <ElButton @click="goBack">取消</ElButton>
        <ElButton type="primary" :loading="saving" @click="saveProduct">保存商品</ElButton>
      </div>
    </div>

    <div class="editor-layout">
      <main class="editor-main">
        <ElCard class="editor-section" shadow="never">
          <template #header>
            <div class="section-head">
              <div>
                <h2>基础信息</h2>
                <p>用于小程序商品列表、详情页和订单快照。</p>
              </div>
              <ElTag :type="form.status === 1 ? 'success' : 'danger'" effect="light">
                {{ form.status === 1 ? '上架中' : '已下架' }}
              </ElTag>
            </div>
          </template>

          <ElForm label-position="top">
            <div class="form-grid">
              <ElFormItem label="商品分类" required>
                <ElSelect v-model="form.categoryId" placeholder="请选择商品分类" class="form-full">
                  <ElOption
                    v-for="item in categories"
                    :key="item.id"
                    :label="item.name"
                    :value="item.id || 0"
                  />
                </ElSelect>
              </ElFormItem>
              <ElFormItem label="商品名称" required>
                <ElInput
                  v-model.trim="form.name"
                  maxlength="40"
                  show-word-limit
                  placeholder="例如：云南高山蓝莓"
                />
              </ElFormItem>
              <ElFormItem label="副标题" class="form-grid__wide">
                <ElInput
                  v-model.trim="form.subtitle"
                  maxlength="60"
                  show-word-limit
                  placeholder="突出产地、口感或配送卖点"
                />
              </ElFormItem>
              <ElFormItem label="商品主图" required class="form-grid__wide">
                <div class="upload-row">
                  <ElImage
                    v-if="form.imageUrl"
                    class="upload-preview"
                    :src="assetUrl(form.imageUrl)"
                    fit="cover"
                    :preview-src-list="[assetUrl(form.imageUrl)]"
                    preview-teleported
                  />
                  <div v-else class="upload-preview upload-placeholder">
                    <ArtSvgIcon icon="ri:image-add-line" />
                    <span>待上传</span>
                  </div>
                  <div class="upload-copy">
                    <ElUpload
                      accept=".jpg,.jpeg,.png,.webp"
                      :show-file-list="false"
                      :http-request="uploadMainImage"
                    >
                      <ElButton :loading="uploadingMain">上传商品图片</ElButton>
                    </ElUpload>
                    <span>支持 JPG、PNG、WebP，文件不超过 5MB。</span>
                  </div>
                </div>
              </ElFormItem>
              <ElFormItem label="商品标签">
                <ElInput v-model.trim="form.badge" maxlength="12" placeholder="热销、新品、今日到店" />
              </ElFormItem>
              <ElFormItem label="小程序排序">
                <ElInputNumber
                  v-model="form.sortOrder"
                  :min="0"
                  :precision="0"
                  :step="1"
                  controls-position="right"
                  class="form-full"
                />
              </ElFormItem>
              <ElFormItem label="首页今日推荐">
                <ElSwitch v-model="form.recommended" active-text="展示" inactive-text="不展示" />
              </ElFormItem>
              <ElFormItem label="商品状态">
                <ElSwitch
                  v-model="form.status"
                  :active-value="1"
                  :inactive-value="0"
                  active-text="上架"
                  inactive-text="下架"
                />
              </ElFormItem>
            </div>
          </ElForm>
        </ElCard>

        <ElCard class="editor-section" shadow="never">
          <template #header>
            <div class="section-head sku-switch-head">
              <div>
                <div class="title-with-badge">
                  <h2>销售规格</h2>
                  <ElTag v-if="form.skuEnabled" type="success" effect="light">多规格</ElTag>
                  <ElTag v-else type="info" effect="light">单规格</ElTag>
                </div>
                <p>开启后，每个 SKU 可以独立设置价格、库存、图片和购买规则。</p>
              </div>
              <ElSwitch
                v-model="form.skuEnabled"
                size="large"
                active-text="开启多规格"
                inactive-text="使用单规格"
                @change="handleSkuToggle"
              />
            </div>
          </template>

          <ElForm v-if="!form.skuEnabled" label-position="top">
            <div class="form-grid form-grid--four">
              <ElFormItem label="销售单位" required>
                <ElInput v-model.trim="form.saleUnit" placeholder="斤、份、盒" />
              </ElFormItem>
              <ElFormItem label="单价（元）" required>
                <ElInputNumber
                  :model-value="centToYuan(form.unitPrice)"
                  :min="0.01"
                  :precision="2"
                  :step="0.1"
                  controls-position="right"
                  class="form-full"
                  @update:model-value="updateBaseUnitPrice"
                />
              </ElFormItem>
              <ElFormItem label="库存" required>
                <ElInputNumber
                  v-model="form.stockQty"
                  :min="0"
                  :step="form.stepQty || 1"
                  controls-position="right"
                  class="form-full"
                />
              </ElFormItem>
              <ElFormItem label="起购数量" required>
                <ElInputNumber
                  v-model="form.minPurchaseQty"
                  :min="0.001"
                  :step="0.5"
                  controls-position="right"
                  class="form-full"
                />
              </ElFormItem>
              <ElFormItem label="每次增加" required>
                <ElInputNumber
                  v-model="form.stepQty"
                  :min="0.001"
                  :step="0.5"
                  controls-position="right"
                  class="form-full"
                />
              </ElFormItem>
            </div>
          </ElForm>

          <div v-else class="multi-sku-workspace">
            <ElForm label-position="top" class="multi-sku-unit-form">
              <div class="form-grid form-grid--four">
                <ElFormItem label="销售单位" required>
                  <ElInput
                    :model-value="form.saleUnit"
                    placeholder="斤、份、盒"
                    @update:model-value="updateMultiSkuSaleUnit"
                  />
                  <span class="multi-sku-unit-hint">修改后将同步到全部 SKU，无需切回单规格。</span>
                </ElFormItem>
              </div>
            </ElForm>

            <div class="spec-builder">
              <div class="subsection-head">
                <div>
                  <h3>规格设置</h3>
                  <p>最多 3 个规格维度，每个维度最多 20 个规格值。</p>
                </div>
                <span class="combination-count">
                  当前将生成 <strong>{{ expectedCombinationCount }}</strong> 个组合
                </span>
              </div>

              <div v-if="form.specGroups.length" class="spec-group-list">
                <div v-for="(group, groupIndex) in form.specGroups" :key="group.id" class="spec-group">
                  <div class="spec-group__meta">
                    <div class="spec-order-actions">
                      <ElButton
                        text
                        :disabled="groupIndex === 0"
                        aria-label="规格维度上移"
                        @click="moveGroup(groupIndex, -1)"
                      >
                        <ArtSvgIcon icon="ri:arrow-up-line" />
                      </ElButton>
                      <ElButton
                        text
                        :disabled="groupIndex === form.specGroups.length - 1"
                        aria-label="规格维度下移"
                        @click="moveGroup(groupIndex, 1)"
                      >
                        <ArtSvgIcon icon="ri:arrow-down-line" />
                      </ElButton>
                    </div>
                    <ElInput
                      v-model.trim="group.name"
                      maxlength="10"
                      class="spec-name-input"
                      placeholder="规格名称"
                      @change="regenerateSkus"
                    />
                    <span>{{ group.options.length }}/20 个规格值</span>
                    <ElButton type="danger" link @click="removeGroup(group)">删除规格</ElButton>
                  </div>

                  <div class="spec-options">
                    <div
                      v-for="(option, optionIndex) in group.options"
                      :key="option.id"
                      class="spec-option"
                    >
                      <ElInput
                        v-model.trim="option.name"
                        maxlength="16"
                        @change="regenerateSkus"
                      />
                      <div class="spec-option__actions">
                        <ElButton
                          text
                          :disabled="optionIndex === 0"
                          aria-label="规格值左移"
                          @click="moveOption(group, optionIndex, -1)"
                        >
                          <ArtSvgIcon icon="ri:arrow-left-line" />
                        </ElButton>
                        <ElButton
                          text
                          :disabled="optionIndex === group.options.length - 1"
                          aria-label="规格值右移"
                          @click="moveOption(group, optionIndex, 1)"
                        >
                          <ArtSvgIcon icon="ri:arrow-right-line" />
                        </ElButton>
                        <ElButton
                          text
                          type="danger"
                          aria-label="删除规格值"
                          @click="removeOption(group, option)"
                        >
                          <ArtSvgIcon icon="ri:close-line" />
                        </ElButton>
                      </div>
                    </div>
                    <div v-if="group.options.length < 20" class="add-option">
                      <ElInput
                        v-model.trim="optionDrafts[group.id]"
                        maxlength="16"
                        placeholder="输入规格值"
                        @keyup.enter="addOption(group)"
                      />
                      <ElButton @click="addOption(group)">添加规格值</ElButton>
                    </div>
                  </div>
                </div>
              </div>

              <div v-else class="inline-empty">
                <ArtSvgIcon icon="ri:git-branch-line" />
                <div>
                  <strong>还没有规格维度</strong>
                  <span>先添加“重量”“包装”等维度，再生成 SKU 组合。</span>
                </div>
              </div>

              <div v-if="form.specGroups.length < 3" class="add-spec-row">
                <ElInput
                  v-model.trim="newSpecName"
                  maxlength="10"
                  placeholder="输入规格名称，例如：重量"
                  @keyup.enter="addGroup"
                />
                <ElButton type="primary" plain @click="addGroup">
                  <ArtSvgIcon icon="ri:add-line" />
                  添加规格维度
                </ElButton>
              </div>
            </div>

            <ElAlert
              v-if="expectedCombinationCount > 200"
              title="规格组合超过 200 个，请减少规格值后再保存。"
              type="error"
              :closable="false"
              show-icon
            />

            <div class="sku-overview">
              <div class="overview-item">
                <span>规格组合</span>
                <strong>{{ skuStats.total }}</strong>
              </div>
              <div class="overview-item overview-item--success">
                <span>可售</span>
                <strong>{{ skuStats.saleable }}</strong>
              </div>
              <div class="overview-item overview-item--danger">
                <span>缺货</span>
                <strong>{{ skuStats.outOfStock }}</strong>
              </div>
              <div class="overview-item overview-item--warning">
                <span>待完善</span>
                <strong>{{ skuStats.incomplete }}</strong>
              </div>
            </div>

            <div class="sku-toolbar">
              <div class="sku-toolbar__left">
                <span>已选择 {{ selectedSkus.length }} 项</span>
                <ElPopover
                  v-model:visible="batchPopoverVisible"
                  placement="bottom-start"
                  :width="520"
                  trigger="click"
                >
                  <template #reference>
                    <ElButton :disabled="!selectedSkus.length">批量设置</ElButton>
                  </template>
                  <div class="batch-panel">
                    <div class="batch-panel__head">
                      <strong>批量设置 {{ selectedSkus.length }} 个 SKU</strong>
                      <span>留空的字段不会被修改。</span>
                    </div>
                    <div class="batch-grid">
                      <label>
                        <span>售价（元）</span>
                        <ElInputNumber
                          v-model="batchForm.unitPriceYuan"
                          :min="0.01"
                          :precision="2"
                          controls-position="right"
                        />
                      </label>
                      <label>
                        <span>库存</span>
                        <ElInputNumber
                          v-model="batchForm.stockQty"
                          :min="0"
                          :precision="3"
                          controls-position="right"
                        />
                      </label>
                      <label>
                        <span>销售单位</span>
                        <ElInput v-model.trim="batchForm.saleUnit" placeholder="不修改" />
                      </label>
                      <label>
                        <span>状态</span>
                        <ElSelect v-model="batchForm.status" clearable placeholder="不修改">
                          <ElOption label="上架" :value="1" />
                          <ElOption label="下架" :value="0" />
                        </ElSelect>
                      </label>
                      <label>
                        <span>起购数量</span>
                        <ElInputNumber
                          v-model="batchForm.minPurchaseQty"
                          :min="0.001"
                          :precision="3"
                          controls-position="right"
                        />
                      </label>
                      <label>
                        <span>每次增加</span>
                        <ElInputNumber
                          v-model="batchForm.stepQty"
                          :min="0.001"
                          :precision="3"
                          controls-position="right"
                        />
                      </label>
                    </div>
                    <div class="batch-panel__actions">
                      <ElButton @click="batchPopoverVisible = false">取消</ElButton>
                      <ElButton type="primary" @click="applyBatch">应用到已选 SKU</ElButton>
                    </div>
                  </div>
                </ElPopover>
              </div>
              <div class="sku-toolbar__right">
                <ElButton @click="regenerateSkus">
                  <ArtSvgIcon icon="ri:refresh-line" />
                  重新生成组合
                </ElButton>
              </div>
            </div>

            <ElTable
              ref="skuTableRef"
              :data="form.skus"
              :row-key="skuRowKey"
              class="sku-table"
              empty-text="添加完整规格值后，将自动生成 SKU 组合"
              @selection-change="handleSelectionChange"
            >
              <ElTableColumn type="selection" width="46" reserve-selection />
              <ElTableColumn type="expand" width="42">
                <template #default="{ row }">
                  <div class="sku-advanced">
                    <div class="advanced-field">
                      <label>SKU 编码</label>
                      <ElInput v-model.trim="row.skuCode" placeholder="保存后自动生成" />
                    </div>
                    <div class="advanced-field">
                      <label>商品条码</label>
                      <ElInput v-model.trim="row.barcode" placeholder="选填，支持扫码录入" />
                    </div>
                    <div class="advanced-field">
                      <label>销售单位</label>
                      <ElInput v-model.trim="row.saleUnit" placeholder="斤、份、盒" />
                    </div>
                    <div class="advanced-field">
                      <label>起购数量</label>
                      <ElInputNumber
                        v-model="row.minPurchaseQty"
                        :min="0.001"
                        :precision="3"
                        controls-position="right"
                      />
                    </div>
                    <div class="advanced-field">
                      <label>每次增加</label>
                      <ElInputNumber
                        v-model="row.stepQty"
                        :min="0.001"
                        :precision="3"
                        controls-position="right"
                      />
                    </div>
                    <div class="advanced-field advanced-field--image">
                      <label>SKU 图片</label>
                      <div class="sku-image-upload">
                        <ElImage
                          v-if="row.imageUrl"
                          :src="assetUrl(row.imageUrl)"
                          fit="cover"
                          class="sku-image"
                        />
                        <div v-else class="sku-image sku-image--empty">使用主图</div>
                        <ElUpload
                          accept=".jpg,.jpeg,.png,.webp"
                          :show-file-list="false"
                          :http-request="(options) => uploadSkuImage(options, row)"
                        >
                          <ElButton size="small">上传图片</ElButton>
                        </ElUpload>
                        <ElButton v-if="row.imageUrl" size="small" link @click="row.imageUrl = ''">
                          使用主图
                        </ElButton>
                      </div>
                    </div>
                  </div>
                </template>
              </ElTableColumn>
              <ElTableColumn label="规格组合" min-width="210" fixed="left">
                <template #default="{ row }">
                  <strong class="sku-spec">{{ row.specificationText }}</strong>
                  <span v-if="row.skuCode" class="sku-code">{{ row.skuCode }}</span>
                </template>
              </ElTableColumn>
              <ElTableColumn label="售价（元）" width="156">
                <template #default="{ row }">
                  <ElInputNumber
                    :model-value="centToYuan(row.unitPrice)"
                    :min="0.01"
                    :precision="2"
                    :step="0.1"
                    controls-position="right"
                    style="width: 130px"
                    @update:model-value="(value) => (row.unitPrice = yuanToCent(Number(value || 0)))"
                  />
                </template>
              </ElTableColumn>
              <ElTableColumn label="库存" width="142">
                <template #default="{ row }">
                  <ElInputNumber
                    v-model="row.stockQty"
                    :min="0"
                    :precision="3"
                    :step="row.stepQty || 1"
                    controls-position="right"
                    style="width: 116px"
                  />
                </template>
              </ElTableColumn>
              <ElTableColumn label="状态" width="112">
                <template #default="{ row }">
                  <ElSwitch
                    v-model="row.status"
                    :active-value="1"
                    :inactive-value="0"
                    inline-prompt
                    active-text="上架"
                    inactive-text="下架"
                    @change="handleSkuStatusChange(row)"
                  />
                </template>
              </ElTableColumn>
              <ElTableColumn label="默认规格" width="116" align="center">
                <template #default="{ row }">
                  <ElRadio
                    :model-value="defaultSkuKey"
                    :value="skuRowKey(row)"
                    @change="setDefaultSku(row)"
                  >
                    默认
                  </ElRadio>
                </template>
              </ElTableColumn>
            </ElTable>
          </div>
        </ElCard>
      </main>

      <aside class="editor-aside">
        <ElCard class="summary-card" shadow="never">
          <template #header>
            <div class="summary-title">商品摘要</div>
          </template>
          <div class="summary-product">
            <ElImage
              v-if="form.imageUrl"
              :src="assetUrl(form.imageUrl)"
              fit="cover"
              class="summary-image"
            />
            <div v-else class="summary-image summary-image--empty">
              <ArtSvgIcon icon="ri:image-line" />
            </div>
            <div>
              <strong>{{ form.name || '未命名商品' }}</strong>
              <span>{{ categoryName(form.categoryId) }}</span>
            </div>
          </div>
          <dl class="summary-list">
            <div>
              <dt>规格模式</dt>
              <dd>{{ form.skuEnabled ? `多规格 · ${form.skus.length} 个组合` : '单规格' }}</dd>
            </div>
            <div>
              <dt>售价</dt>
              <dd class="summary-price">{{ summaryPrice }}</dd>
            </div>
            <div>
              <dt>总库存</dt>
              <dd>{{ summaryStock }}{{ form.saleUnit }}</dd>
            </div>
            <div>
              <dt>可售规格</dt>
              <dd>{{ form.skuEnabled ? skuStats.saleable : form.stockQty > 0 ? 1 : 0 }}</dd>
            </div>
            <div>
              <dt>商品状态</dt>
              <dd>{{ form.status === 1 ? '上架' : '下架' }}</dd>
            </div>
          </dl>
          <ElDivider />
          <div class="save-checklist">
            <strong>保存检查</strong>
            <div :class="{ complete: Boolean(form.categoryId) }">
              <ArtSvgIcon :icon="form.categoryId ? 'ri:checkbox-circle-fill' : 'ri:checkbox-blank-circle-line'" />
              已选择商品分类
            </div>
            <div :class="{ complete: Boolean(form.name && form.imageUrl) }">
              <ArtSvgIcon
                :icon="form.name && form.imageUrl ? 'ri:checkbox-circle-fill' : 'ri:checkbox-blank-circle-line'"
              />
              商品名称和主图
            </div>
            <div :class="{ complete: !form.skuEnabled || skuStats.incomplete === 0 }">
              <ArtSvgIcon
                :icon="
                  !form.skuEnabled || skuStats.incomplete === 0
                    ? 'ri:checkbox-circle-fill'
                    : 'ri:checkbox-blank-circle-line'
                "
              />
              SKU 信息完整
            </div>
            <div :class="{ complete: !form.skuEnabled || defaultSkuKey }">
              <ArtSvgIcon
                :icon="
                  !form.skuEnabled || defaultSkuKey
                    ? 'ri:checkbox-circle-fill'
                    : 'ri:checkbox-blank-circle-line'
                "
              />
              已设置默认规格
            </div>
          </div>
        </ElCard>
      </aside>
    </div>

    <div class="editor-footer">
      <div>
        <strong>{{ dirty ? '有尚未保存的修改' : '当前内容已同步' }}</strong>
        <span>{{ form.skuEnabled ? `${skuStats.total} 个 SKU 将随商品一起保存` : '当前使用单规格销售' }}</span>
      </div>
      <div class="editor-footer__actions">
        <ElButton @click="goBack">取消</ElButton>
        <ElButton type="primary" :loading="saving" @click="saveProduct">保存商品</ElButton>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
  import {
    ElMessage,
    ElMessageBox,
    type ElTable,
    type UploadRequestOptions
  } from 'element-plus'
  import {
    createProduct,
    getCategories,
    getProduct,
    updateProduct,
    uploadProductImage,
    type Category,
    type Product,
    type ProductSku,
    type ProductSpecGroup,
    type ProductSpecOption
  } from '@/api/admin'
  import { resolveFreshAssetUrl } from '@/utils/fresh-assets'

  defineOptions({ name: 'FreshProductEditor' })

  const route = useRoute()
  const router = useRouter()
  const productId = computed(() => {
    const value = Array.isArray(route.query.id) ? route.query.id[0] : route.query.id
    const parsed = Number(value)
    return Number.isFinite(parsed) && parsed > 0 ? parsed : null
  })
  const isEdit = computed(() => Boolean(productId.value))
  const loading = ref(true)
  const saving = ref(false)
  const uploadingMain = ref(false)
  const dirty = ref(false)
  const initializing = ref(true)
  const categories = ref<Category[]>([])
  const skuTableRef = ref<InstanceType<typeof ElTable>>()
  const selectedSkus = ref<ProductSku[]>([])
  const newSpecName = ref('')
  const optionDrafts = reactive<Record<string, string>>({})
  const batchPopoverVisible = ref(false)
  const batchForm = reactive<{
    unitPriceYuan: number | null
    stockQty: number | null
    saleUnit: string
    status: number | null
    minPurchaseQty: number | null
    stepQty: number | null
  }>({
    unitPriceYuan: null,
    stockQty: null,
    saleUnit: '',
    status: null,
    minPurchaseQty: null,
    stepQty: null
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
      sortOrder: null,
      skuEnabled: false,
      minUnitPrice: 100,
      maxUnitPrice: 100,
      availableSkuCount: 0,
      specGroups: [],
      skus: []
    }
  }

  const centToYuan = (value: number) => Number((Number(value || 0) / 100).toFixed(2))
  const yuanToCent = (value: number) => Math.max(1, Math.round(Number(value || 0) * 100))
  const money = (value: number) => `￥${centToYuan(value).toFixed(2)}`
  const assetUrl = resolveFreshAssetUrl
  const makeLocalId = (prefix: string) =>
    `${prefix}_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 9)}`
  const combinationKey = (optionIds: string[]) => [...optionIds].sort().join('|')
  const skuRowKey = (sku: ProductSku) =>
    sku.id ? `sku-${sku.id}` : `combination-${combinationKey(sku.optionValueIds)}`

  const categoryName = (categoryId: number | null) =>
    categories.value.find((item) => item.id === categoryId)?.name || '未选择分类'

  const expectedCombinationCount = computed(() => {
    if (!form.specGroups.length) return 0
    return form.specGroups.reduce((total, group) => total * group.options.length, 1)
  })

  const isSkuComplete = (sku: ProductSku) =>
    Boolean(
      sku.specificationText &&
        sku.unitPrice > 0 &&
        Number(sku.stockQty) >= 0 &&
        sku.saleUnit &&
        Number(sku.minPurchaseQty) > 0 &&
        Number(sku.stepQty) > 0
    )

  const skuStats = computed(() => ({
    total: form.skus.length,
    saleable: form.skus.filter((sku) => sku.status === 1 && Number(sku.stockQty) > 0).length,
    outOfStock: form.skus.filter((sku) => sku.status === 1 && Number(sku.stockQty) <= 0).length,
    incomplete: form.skus.filter((sku) => !isSkuComplete(sku)).length
  }))

  const defaultSkuKey = computed(
    () => form.skus.find((sku) => Boolean(sku.defaultSku)) && skuRowKey(form.skus.find((sku) => Boolean(sku.defaultSku))!)
  )

  const summaryStock = computed(() =>
    form.skuEnabled
      ? form.skus
          .filter((sku) => sku.status === 1)
          .reduce((sum, sku) => sum + Number(sku.stockQty || 0), 0)
      : Number(form.stockQty || 0)
  )

  const summaryPrice = computed(() => {
    if (!form.skuEnabled || !form.skus.length) return money(form.unitPrice)
    const prices = form.skus.filter((sku) => sku.status === 1).map((sku) => sku.unitPrice)
    const source = prices.length ? prices : form.skus.map((sku) => sku.unitPrice)
    const min = Math.min(...source)
    const max = Math.max(...source)
    return min === max ? money(min) : `${money(min)} – ${money(max)}`
  })

  const updateBaseUnitPrice = (value: number | undefined) => {
    form.unitPrice = yuanToCent(Number(value || 0))
  }

  const updateMultiSkuSaleUnit = (value: string) => {
    form.saleUnit = value
    form.skus.forEach((sku) => {
      sku.saleUnit = value
    })
  }

  const loadData = async () => {
    loading.value = true
    initializing.value = true
    try {
      categories.value = await getCategories()
      if (productId.value) {
        const product = await getProduct(productId.value)
        Object.assign(form, emptyForm(), product, {
          skuEnabled: Boolean(product.skuEnabled),
          recommended: Boolean(product.recommended),
          specGroups: (product.specGroups || []).map((group) => ({
            ...group,
            options: (group.options || []).map((option) => ({ ...option }))
          })),
          skus: (product.skus || []).map((sku) => ({ ...sku, optionValueIds: [...sku.optionValueIds] }))
        })
      } else {
        Object.assign(form, emptyForm(), { categoryId: categories.value[0]?.id || null })
      }
      form.specGroups.forEach((group) => {
        optionDrafts[group.id] = ''
      })
      await nextTick()
      dirty.value = false
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '商品信息加载失败')
      router.replace({ name: 'FreshProducts' })
    } finally {
      initializing.value = false
      loading.value = false
    }
  }

  const uploadMainImage = async (options: UploadRequestOptions) => {
    uploadingMain.value = true
    try {
      const result = await uploadProductImage(options.file)
      form.imageUrl = result.url
      options.onSuccess(result)
      ElMessage.success('商品主图已上传')
    } catch (error) {
      options.onError(error as any)
      ElMessage.error(error instanceof Error ? error.message : '图片上传失败')
    } finally {
      uploadingMain.value = false
    }
  }

  const uploadSkuImage = async (options: UploadRequestOptions, sku: ProductSku) => {
    try {
      const result = await uploadProductImage(options.file)
      sku.imageUrl = result.url
      options.onSuccess(result)
      ElMessage.success('SKU 图片已上传')
    } catch (error) {
      options.onError(error as any)
      ElMessage.error(error instanceof Error ? error.message : 'SKU 图片上传失败')
    }
  }

  const handleSkuToggle = async (value: string | number | boolean) => {
    const enabled = Boolean(value)
    if (enabled) {
      if (!form.specGroups.length) {
        const groupId = makeLocalId('spec')
        const optionId = makeLocalId('option')
        form.specGroups = [
          {
            id: groupId,
            name: '规格',
            sortOrder: 10,
            options: [{ id: optionId, name: '默认', imageUrl: '', sortOrder: 10 }]
          }
        ]
        optionDrafts[groupId] = ''
      }
      regenerateSkus()
      return
    }
    if (!form.skus.length) return
    try {
      await ElMessageBox.confirm(
        '关闭多规格后，本次保存将移除现有 SKU 明细，购物车中的多规格商品需要重新选择。是否继续？',
        '关闭多规格',
        {
          type: 'warning',
          confirmButtonText: '关闭多规格',
          cancelButtonText: '继续使用多规格'
        }
      )
    } catch {
      form.skuEnabled = true
    }
  }

  const addGroup = () => {
    const name = newSpecName.value.trim()
    if (!name) {
      ElMessage.warning('请填写规格名称')
      return
    }
    if (form.specGroups.length >= 3) {
      ElMessage.warning('每个商品最多设置 3 个规格维度')
      return
    }
    if (form.specGroups.some((group) => group.name === name)) {
      ElMessage.warning('规格名称不能重复')
      return
    }
    const id = makeLocalId('spec')
    form.specGroups.push({
      id,
      name,
      sortOrder: (form.specGroups.length + 1) * 10,
      options: []
    })
    optionDrafts[id] = ''
    newSpecName.value = ''
    regenerateSkus()
  }

  const removeGroup = async (group: ProductSpecGroup) => {
    try {
      await ElMessageBox.confirm(
        `删除规格「${group.name}」会重新生成 SKU 组合，已填写的对应组合信息可能被合并。`,
        '删除规格维度',
        {
          type: 'warning',
          confirmButtonText: '删除规格',
          cancelButtonText: '保留规格'
        }
      )
      form.specGroups = form.specGroups.filter((item) => item.id !== group.id)
      delete optionDrafts[group.id]
      normalizeSpecSort()
      regenerateSkus()
    } catch {}
  }

  const addOption = (group: ProductSpecGroup) => {
    const name = (optionDrafts[group.id] || '').trim()
    if (!name) {
      ElMessage.warning('请填写规格值')
      return
    }
    if (group.options.length >= 20) {
      ElMessage.warning('每个规格维度最多设置 20 个规格值')
      return
    }
    if (group.options.some((option) => option.name === name)) {
      ElMessage.warning(`规格「${group.name}」中不能添加重复规格值`)
      return
    }
    group.options.push({
      id: makeLocalId('option'),
      name,
      imageUrl: '',
      sortOrder: (group.options.length + 1) * 10
    })
    optionDrafts[group.id] = ''
    regenerateSkus()
  }

  const removeOption = async (group: ProductSpecGroup, option: ProductSpecOption) => {
    try {
      await ElMessageBox.confirm(
        `删除规格值「${option.name}」后，包含该规格值的 SKU 组合会被移除。`,
        '删除规格值',
        {
          type: 'warning',
          confirmButtonText: '删除规格值',
          cancelButtonText: '保留规格值'
        }
      )
      group.options = group.options.filter((item) => item.id !== option.id)
      normalizeSpecSort()
      regenerateSkus()
    } catch {}
  }

  const moveGroup = (index: number, offset: number) => {
    const target = index + offset
    if (target < 0 || target >= form.specGroups.length) return
    const [group] = form.specGroups.splice(index, 1)
    form.specGroups.splice(target, 0, group)
    normalizeSpecSort()
    regenerateSkus()
  }

  const moveOption = (group: ProductSpecGroup, index: number, offset: number) => {
    const target = index + offset
    if (target < 0 || target >= group.options.length) return
    const [option] = group.options.splice(index, 1)
    group.options.splice(target, 0, option)
    normalizeSpecSort()
    regenerateSkus()
  }

  const normalizeSpecSort = () => {
    form.specGroups.forEach((group, groupIndex) => {
      group.sortOrder = (groupIndex + 1) * 10
      group.options.forEach((option, optionIndex) => {
        option.sortOrder = (optionIndex + 1) * 10
      })
    })
  }

  const buildCombinations = () => {
    if (!form.specGroups.length || form.specGroups.some((group) => !group.options.length)) {
      return [] as ProductSpecOption[][]
    }
    return form.specGroups.reduce<ProductSpecOption[][]>(
      (combinations, group) =>
        combinations.flatMap((combination) =>
          group.options.map((option) => [...combination, option])
        ),
      [[]]
    )
  }

  const regenerateSkus = () => {
    if (!form.skuEnabled) return
    normalizeSpecSort()
    const combinations = buildCombinations()
    if (combinations.length > 200) {
      form.skus = []
      selectedSkus.value = []
      skuTableRef.value?.clearSelection()
      return
    }

    const existingByKey = new Map(form.skus.map((sku) => [combinationKey(sku.optionValueIds), sku]))
    const unusedExisting = [...form.skus]
    const usedIds = new Set<number>()
    const nextSkus = combinations.map((combination, index) => {
      const optionValueIds = combination.map((option) => option.id)
      const key = combinationKey(optionValueIds)
      let existing = existingByKey.get(key)
      if (!existing) {
        existing = unusedExisting.find(
          (candidate) =>
            (!candidate.id || !usedIds.has(candidate.id)) &&
            candidate.optionValueIds.every((optionId) => optionValueIds.includes(optionId))
        )
      }
      if (existing?.id) usedIds.add(existing.id)
      return {
        id: existing?.id,
        skuCode: existing?.skuCode || '',
        barcode: existing?.barcode || '',
        optionValueIds,
        specificationText: combination.map((option) => option.name).join(' · '),
        imageUrl: existing?.imageUrl || '',
        unitPrice: existing?.unitPrice || form.unitPrice || 100,
        stockQty: Number(existing?.stockQty || 0),
        saleUnit: existing?.saleUnit || form.saleUnit || '份',
        minPurchaseQty: Number(existing?.minPurchaseQty || form.minPurchaseQty || 1),
        stepQty: Number(existing?.stepQty || form.stepQty || 1),
        status: existing?.status ?? 1,
        defaultSku: Boolean(existing?.defaultSku),
        sortOrder: (index + 1) * 10
      } satisfies ProductSku
    })

    if (nextSkus.length && !nextSkus.some((sku) => sku.defaultSku)) {
      const firstSaleable = nextSkus.find((sku) => sku.status === 1) || nextSkus[0]
      firstSaleable.defaultSku = true
    }
    if (nextSkus.filter((sku) => sku.defaultSku).length > 1) {
      let found = false
      nextSkus.forEach((sku) => {
        if (sku.defaultSku && !found) {
          found = true
        } else {
          sku.defaultSku = false
        }
      })
    }
    form.skus = nextSkus
    selectedSkus.value = []
    nextTick(() => skuTableRef.value?.clearSelection())
  }

  const handleSelectionChange = (rows: ProductSku[]) => {
    selectedSkus.value = rows
  }

  const setDefaultSku = (target: ProductSku) => {
    form.skus.forEach((sku) => {
      sku.defaultSku = skuRowKey(sku) === skuRowKey(target)
    })
  }

  const handleSkuStatusChange = (target: ProductSku) => {
    if (target.status === 1) {
      const currentDefault = form.skus.find((sku) => sku.defaultSku)
      if (!currentDefault || currentDefault.status !== 1) {
        setDefaultSku(target)
      }
      return
    }
    if (!target.defaultSku) return
    const nextActive = form.skus.find(
      (sku) => sku.status === 1 && skuRowKey(sku) !== skuRowKey(target)
    )
    if (nextActive) {
      setDefaultSku(nextActive)
    }
  }

  const applyBatch = () => {
    if (!selectedSkus.value.length) {
      ElMessage.warning('请先选择需要批量设置的 SKU')
      return
    }
    selectedSkus.value.forEach((sku) => {
      if (batchForm.unitPriceYuan !== null) sku.unitPrice = yuanToCent(batchForm.unitPriceYuan)
      if (batchForm.stockQty !== null) sku.stockQty = batchForm.stockQty
      if (batchForm.saleUnit) sku.saleUnit = batchForm.saleUnit
      if (batchForm.status !== null) sku.status = batchForm.status
      if (batchForm.minPurchaseQty !== null) sku.minPurchaseQty = batchForm.minPurchaseQty
      if (batchForm.stepQty !== null) sku.stepQty = batchForm.stepQty
    })
    Object.assign(batchForm, {
      unitPriceYuan: null,
      stockQty: null,
      saleUnit: '',
      status: null,
      minPurchaseQty: null,
      stepQty: null
    })
    batchPopoverVisible.value = false
    ElMessage.success(`已更新 ${selectedSkus.value.length} 个 SKU`)
  }

  const validateForm = () => {
    if (!form.categoryId) return '请选择商品分类'
    if (!form.name.trim()) return '请填写商品名称'
    if (!form.imageUrl) return '请上传商品主图'
    if (!form.saleUnit.trim()) return '请填写销售单位'
    if (!form.skuEnabled) {
      if (form.unitPrice < 1) return '商品单价必须大于 0'
      if (Number(form.minPurchaseQty) <= 0) return '起购数量必须大于 0'
      if (Number(form.stepQty) <= 0) return '每次增加数量必须大于 0'
      if (Number(form.stockQty) < 0) return '库存不能小于 0'
      return ''
    }
    if (!form.specGroups.length) return '请至少添加一个规格维度'
    if (form.specGroups.some((group) => !group.name.trim())) return '请填写完整的规格名称'
    if (form.specGroups.some((group) => !group.options.length)) return '每个规格维度至少需要一个规格值'
    if (expectedCombinationCount.value > 200) return '规格组合不能超过 200 个'
    if (form.skus.length !== expectedCombinationCount.value) return '请重新生成完整的 SKU 组合'
    if (skuStats.value.incomplete > 0) return `还有 ${skuStats.value.incomplete} 个 SKU 信息不完整`
    if (!form.skus.some((sku) => sku.defaultSku)) return '请设置默认规格'
    const codes = form.skus.map((sku) => sku.skuCode.trim()).filter(Boolean)
    if (new Set(codes.map((code) => code.toLowerCase())).size !== codes.length) return 'SKU 编码不能重复'
    const barcodes = form.skus.map((sku) => sku.barcode.trim()).filter(Boolean)
    if (new Set(barcodes.map((code) => code.toLowerCase())).size !== barcodes.length) return 'SKU 条码不能重复'
    return ''
  }

  const buildPayload = () => {
    const payload = JSON.parse(JSON.stringify(form)) as Product
    if (payload.skuEnabled && payload.skus.length) {
      const defaultSku = payload.skus.find((sku) => sku.defaultSku) || payload.skus[0]
      const saleableSkus = payload.skus.filter((sku) => sku.status === 1)
      const priceSource = saleableSkus.length ? saleableSkus : payload.skus
      payload.saleUnit = defaultSku.saleUnit
      payload.unitPrice = Math.min(...priceSource.map((sku) => sku.unitPrice))
      payload.minPurchaseQty = defaultSku.minPurchaseQty
      payload.stepQty = defaultSku.stepQty
      payload.stockQty = saleableSkus.reduce((sum, sku) => sum + Number(sku.stockQty || 0), 0)
    }
    return payload
  }

  const saveProduct = async () => {
    const message = validateForm()
    if (message) {
      ElMessage.warning(message)
      return
    }
    saving.value = true
    try {
      const payload = buildPayload()
      if (productId.value) {
        await updateProduct(productId.value, payload)
      } else {
        await createProduct(payload)
      }
      dirty.value = false
      ElMessage.success('商品和 SKU 信息已保存')
      await router.replace({ name: 'FreshProducts' })
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '商品保存失败')
    } finally {
      saving.value = false
    }
  }

  const goBack = async () => {
    if (!dirty.value) {
      await router.push({ name: 'FreshProducts' })
      return
    }
    try {
      await ElMessageBox.confirm('当前修改尚未保存，离开后将无法恢复。', '放弃未保存修改', {
        type: 'warning',
        confirmButtonText: '放弃修改',
        cancelButtonText: '继续编辑'
      })
      dirty.value = false
      await router.push({ name: 'FreshProducts' })
    } catch {}
  }

  watch(
    form,
    () => {
      if (!initializing.value) dirty.value = true
    },
    { deep: true }
  )

  onBeforeRouteLeave(async () => {
    if (!dirty.value || saving.value) return true
    try {
      await ElMessageBox.confirm('当前修改尚未保存，离开后将无法恢复。', '放弃未保存修改', {
        type: 'warning',
        confirmButtonText: '放弃修改',
        cancelButtonText: '继续编辑'
      })
      dirty.value = false
      return true
    } catch {
      return false
    }
  })

  onMounted(loadData)
</script>

<style scoped lang="scss">
  @use '../style.scss';

  .product-editor {
    --sku-green: #008a52;
    --sku-green-deep: #006d42;
    --sku-green-soft: #e2f5eb;
    --sku-border: #d8e4dc;
    --sku-warning: #d97706;
    --sku-danger: #c94143;
    padding-bottom: 92px;
  }

  .editor-head,
  .editor-head__copy,
  .editor-head__actions,
  .section-head,
  .title-with-badge,
  .subsection-head,
  .sku-toolbar,
  .sku-toolbar__left,
  .sku-toolbar__right,
  .editor-footer,
  .editor-footer__actions {
    display: flex;
    align-items: center;
  }

  .editor-head,
  .section-head,
  .subsection-head,
  .sku-toolbar,
  .editor-footer {
    justify-content: space-between;
  }

  .editor-head {
    gap: 20px;
    margin-bottom: 16px;
  }

  .editor-head__copy {
    align-items: flex-start;
    gap: 12px;

    h1 {
      margin: 0;
      color: var(--art-gray-900);
      font-size: 22px;
      line-height: 30px;
    }

    p {
      margin: 5px 0 0;
      color: var(--art-gray-500);
      font-size: 13px;
    }
  }

  .back-button {
    margin-top: 1px;
    padding-right: 6px;
    padding-left: 0;
    color: var(--art-gray-600);
  }

  .editor-head__actions,
  .editor-footer__actions,
  .sku-toolbar__left,
  .sku-toolbar__right,
  .title-with-badge {
    gap: 10px;
  }

  .editor-layout {
    display: grid;
    grid-template-columns: minmax(0, 1fr) 292px;
    gap: 16px;
    align-items: start;
  }

  .editor-main {
    display: flex;
    min-width: 0;
    flex-direction: column;
    gap: 16px;
  }

  .editor-section,
  .summary-card {
    border-color: var(--art-border-color);
    border-radius: 10px;
  }

  .section-head {
    gap: 16px;

    h2,
    h3,
    p {
      margin: 0;
    }

    h2 {
      color: var(--art-gray-900);
      font-size: 17px;
      line-height: 24px;
    }

    p {
      margin-top: 4px;
      color: var(--art-gray-500);
      font-size: 12px;
    }
  }

  .sku-switch-head {
    align-items: flex-start;
  }

  .form-grid {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    column-gap: 20px;

    &--four {
      grid-template-columns: repeat(4, minmax(0, 1fr));
    }
  }

  .form-grid__wide {
    grid-column: 1 / -1;
  }

  .upload-row {
    display: flex;
    align-items: center;
    gap: 16px;
  }

  .upload-preview {
    width: 104px;
    height: 104px;
    flex: 0 0 auto;
    overflow: hidden;
    border: 1px solid var(--sku-border);
    border-radius: 10px;
    background: #f4f8f5;
  }

  .upload-placeholder {
    display: flex;
    align-items: center;
    justify-content: center;
    flex-direction: column;
    gap: 6px;
    color: var(--art-gray-500);
    font-size: 12px;

    svg {
      font-size: 24px;
    }
  }

  .upload-copy {
    display: flex;
    align-items: flex-start;
    flex-direction: column;
    gap: 8px;

    span {
      color: var(--art-gray-500);
      font-size: 12px;
    }
  }

  .multi-sku-workspace {
    display: flex;
    flex-direction: column;
    gap: 16px;
  }

  .multi-sku-unit-form {
    padding: 14px 16px 0;
    background: #f7faf8;
    border: 1px solid var(--sku-border);
    border-radius: 8px;
  }

  .multi-sku-unit-hint {
    display: block;
    margin-top: 6px;
    color: var(--art-gray-500);
    font-size: 12px;
    line-height: 18px;
  }

  .spec-builder {
    padding-bottom: 2px;
  }

  .subsection-head {
    gap: 16px;
    margin-bottom: 14px;

    h3 {
      margin: 0;
      color: var(--art-gray-900);
      font-size: 15px;
    }

    p {
      margin: 4px 0 0;
      color: var(--art-gray-500);
      font-size: 12px;
    }
  }

  .combination-count {
    color: var(--art-gray-500);
    font-size: 12px;

    strong {
      color: var(--sku-green-deep);
      font-size: 15px;
      font-variant-numeric: tabular-nums;
    }
  }

  .spec-group-list {
    border: 1px solid var(--sku-border);
    border-radius: 8px;
  }

  .spec-group {
    display: grid;
    grid-template-columns: 240px minmax(0, 1fr);
    min-height: 92px;

    & + & {
      border-top: 1px solid var(--sku-border);
    }
  }

  .spec-group__meta {
    display: grid;
    grid-template-columns: 54px minmax(0, 1fr);
    gap: 6px 8px;
    align-content: center;
    padding: 14px;
    border-right: 1px solid var(--sku-border);
    background: #f7faf8;

    > span {
      align-self: center;
      color: var(--art-gray-500);
      font-size: 11px;
    }
  }

  .spec-order-actions {
    display: flex;
    align-items: center;
    grid-row: span 2;

    :deep(.el-button + .el-button) {
      margin-left: 0;
    }
  }

  .spec-name-input {
    width: 100%;
  }

  .spec-options {
    display: flex;
    flex-wrap: wrap;
    gap: 10px;
    align-content: center;
    padding: 14px;
  }

  .spec-option {
    display: flex;
    align-items: center;
    width: 222px;
    border: 1px solid var(--sku-border);
    border-radius: 7px;
    background: #fff;

    :deep(.el-input__wrapper) {
      box-shadow: none;
    }
  }

  .spec-option__actions {
    display: flex;
    align-items: center;
    padding-right: 3px;

    :deep(.el-button) {
      width: 25px;
      padding: 0;
    }

    :deep(.el-button + .el-button) {
      margin-left: 0;
    }
  }

  .add-option {
    display: flex;
    width: 310px;
    gap: 8px;
  }

  .add-spec-row {
    display: flex;
    width: min(460px, 100%);
    gap: 10px;
    margin-top: 12px;
  }

  .inline-empty {
    display: flex;
    align-items: center;
    gap: 12px;
    min-height: 82px;
    padding: 16px;
    color: var(--art-gray-500);
    border: 1px dashed var(--sku-border);
    border-radius: 8px;
    background: #f8faf9;

    > svg {
      color: var(--sku-green);
      font-size: 24px;
    }

    div {
      display: flex;
      flex-direction: column;
      gap: 4px;
    }

    strong {
      color: var(--art-gray-800);
    }

    span {
      font-size: 12px;
    }
  }

  .sku-overview {
    display: grid;
    grid-template-columns: repeat(4, minmax(0, 1fr));
    border: 1px solid var(--sku-border);
    border-radius: 8px;
  }

  .overview-item {
    display: flex;
    align-items: center;
    justify-content: space-between;
    min-height: 62px;
    padding: 0 16px;

    & + & {
      border-left: 1px solid var(--sku-border);
    }

    span {
      color: var(--art-gray-500);
      font-size: 12px;
    }

    strong {
      color: var(--art-gray-900);
      font-size: 20px;
      font-variant-numeric: tabular-nums;
    }

    &--success strong {
      color: var(--sku-green);
    }

    &--danger strong {
      color: var(--sku-danger);
    }

    &--warning strong {
      color: var(--sku-warning);
    }
  }

  .sku-toolbar {
    min-height: 40px;
    gap: 16px;
  }

  .sku-toolbar__left > span {
    color: var(--art-gray-500);
    font-size: 12px;
  }

  .batch-panel__head {
    display: flex;
    flex-direction: column;
    gap: 4px;
    margin-bottom: 14px;

    span {
      color: var(--art-gray-500);
      font-size: 12px;
    }
  }

  .batch-grid {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 12px;

    label {
      display: flex;
      min-width: 0;
      flex-direction: column;
      gap: 6px;

      > span {
        color: var(--art-gray-700);
        font-size: 12px;
      }
    }

    :deep(.el-input-number),
    :deep(.el-select) {
      width: 100%;
    }
  }

  .batch-panel__actions {
    display: flex;
    justify-content: flex-end;
    gap: 8px;
    margin-top: 16px;
  }

  .sku-table {
    border: 1px solid var(--sku-border);
    border-radius: 8px;

    :deep(.el-table__inner-wrapper::before) {
      display: none;
    }
  }

  .sku-spec,
  .sku-code {
    display: block;
  }

  .sku-spec {
    color: var(--art-gray-900);
  }

  .sku-code {
    margin-top: 3px;
    color: var(--art-gray-500);
    font-size: 11px;
    font-variant-numeric: tabular-nums;
  }

  .sku-advanced {
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 14px 18px;
    padding: 16px 64px 18px;
    background: #f7faf8;
    border-top: 1px solid var(--sku-border);
    border-bottom: 1px solid var(--sku-border);
  }

  .advanced-field {
    display: flex;
    min-width: 0;
    flex-direction: column;
    gap: 6px;

    > label {
      color: var(--art-gray-600);
      font-size: 12px;
    }

    :deep(.el-input-number) {
      width: 100%;
    }
  }

  .advanced-field--image {
    grid-column: span 3;
  }

  .sku-image-upload {
    display: flex;
    align-items: center;
    gap: 10px;
  }

  .sku-image {
    width: 52px;
    height: 52px;
    overflow: hidden;
    border: 1px solid var(--sku-border);
    border-radius: 7px;
  }

  .sku-image--empty {
    display: flex;
    align-items: center;
    justify-content: center;
    color: var(--art-gray-500);
    font-size: 10px;
    background: #fff;
  }

  .editor-aside {
    position: sticky;
    top: 16px;
  }

  .summary-title {
    color: var(--art-gray-900);
    font-size: 15px;
    font-weight: 700;
  }

  .summary-product {
    display: flex;
    align-items: center;
    gap: 12px;
    padding-bottom: 16px;

    > div:last-child {
      display: flex;
      min-width: 0;
      flex-direction: column;
      gap: 5px;
    }

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

  .summary-image {
    width: 64px;
    height: 64px;
    flex: 0 0 auto;
    overflow: hidden;
    border: 1px solid var(--sku-border);
    border-radius: 8px;
    background: #f4f8f5;
  }

  .summary-image--empty {
    display: flex;
    align-items: center;
    justify-content: center;
    color: var(--art-gray-400);
    font-size: 24px;
  }

  .summary-list {
    margin: 0;

    > div {
      display: flex;
      justify-content: space-between;
      gap: 12px;
      padding: 10px 0;
      border-top: 1px solid var(--art-border-color);
    }

    dt,
    dd {
      margin: 0;
      font-size: 12px;
    }

    dt {
      color: var(--art-gray-500);
    }

    dd {
      color: var(--art-gray-800);
      text-align: right;
    }

    .summary-price {
      color: var(--sku-green-deep);
      font-weight: 700;
    }
  }

  .save-checklist {
    display: flex;
    flex-direction: column;
    gap: 10px;

    > strong {
      margin-bottom: 2px;
      color: var(--art-gray-800);
      font-size: 13px;
    }

    > div {
      display: flex;
      align-items: center;
      gap: 7px;
      color: var(--art-gray-500);
      font-size: 12px;

      svg {
        color: var(--art-gray-400);
        font-size: 16px;
      }

      &.complete {
        color: var(--art-gray-700);

        svg {
          color: var(--sku-green);
        }
      }
    }
  }

  .editor-footer {
    position: fixed;
    right: 0;
    bottom: 0;
    left: var(--art-sidebar-width, 230px);
    z-index: 20;
    min-height: 72px;
    gap: 20px;
    padding: 12px 28px;
    background: rgba(255, 255, 255, 0.96);
    border-top: 1px solid var(--art-border-color);
    box-shadow: 0 -8px 24px rgb(18 32 25 / 6%);
    backdrop-filter: blur(12px);

    > div:first-child {
      display: flex;
      flex-direction: column;
      gap: 3px;

      strong {
        color: var(--art-gray-800);
        font-size: 13px;
      }

      span {
        color: var(--art-gray-500);
        font-size: 11px;
      }
    }
  }

  @media (max-width: 1280px) {
    .editor-layout {
      grid-template-columns: minmax(0, 1fr);
    }

    .editor-aside {
      position: static;
    }

    .summary-card {
      :deep(.el-card__body) {
        display: grid;
        grid-template-columns: 240px minmax(0, 1fr) minmax(220px, 0.8fr);
        gap: 20px;
      }

      :deep(.el-divider) {
        display: none;
      }
    }
  }

  @media (max-width: 960px) {
    .form-grid,
    .form-grid--four,
    .sku-advanced {
      grid-template-columns: repeat(2, minmax(0, 1fr));
    }

    .advanced-field--image {
      grid-column: span 2;
    }

    .spec-group {
      grid-template-columns: 1fr;
    }

    .spec-group__meta {
      border-right: 0;
      border-bottom: 1px solid var(--sku-border);
    }

    .summary-card :deep(.el-card__body) {
      grid-template-columns: 1fr;
    }

    .editor-footer {
      left: 0;
    }
  }
</style>
