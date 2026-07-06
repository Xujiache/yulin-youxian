<template>
  <div>
    <div class="toolbar">
      <h1 class="page-title">商品管理</h1>
      <div class="toolbar-actions">
        <el-button @click="createCategoryByPrompt">新增分类</el-button>
        <el-button type="primary" @click="openCreate">新增商品</el-button>
      </div>
    </div>

    <div class="card-section">
      <el-table :data="products" border>
        <el-table-column label="图片" width="90">
          <template #default="{ row }">
            <el-image v-if="row.imageUrl" class="product-thumb" :src="row.imageUrl" fit="cover" />
            <div v-else class="empty-thumb">无图</div>
          </template>
        </el-table-column>
        <el-table-column prop="name" label="商品名称" min-width="160" />
        <el-table-column prop="saleUnit" label="单位" width="80" />
        <el-table-column label="单价" width="130">
          <template #default="{ row }">
            <span class="price-text">￥{{ (row.unitPrice / 100).toFixed(2) }}/{{ row.saleUnit }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="minPurchaseQty" label="起购量" width="100" />
        <el-table-column prop="stepQty" label="步进" width="90" />
        <el-table-column label="库存" width="150">
          <template #default="{ row }">
            <el-input-number
              v-model="row.stockQty"
              :min="0"
              :step="0.5"
              size="small"
              class="stock-input"
              @change="(value) => handleStockChange(row, value)"
            />
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'">{{ row.status === 1 ? "上架" : "下架" }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="250">
          <template #default="{ row }">
            <el-button size="small" @click="openEdit(row)">编辑</el-button>
            <el-button size="small" @click="toggleStatus(row)">{{ row.status === 1 ? "下架" : "上架" }}</el-button>
            <el-button size="small" type="danger" @click="removeProduct(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑商品' : '新增商品'" width="620px">
      <el-form :model="form" label-width="110px">
        <el-form-item label="分类">
          <div class="inline-field">
            <el-select v-model="form.categoryId" class="full" placeholder="请选择分类">
              <el-option v-for="item in categories" :key="item.id" :label="item.name" :value="item.id" />
            </el-select>
            <el-button @click="createCategoryByPrompt">新增</el-button>
          </div>
        </el-form-item>
        <el-form-item label="名称">
          <el-input v-model="form.name" maxlength="40" show-word-limit />
        </el-form-item>
        <el-form-item label="副标题">
          <el-input v-model="form.subtitle" maxlength="60" show-word-limit />
        </el-form-item>
        <el-form-item label="商品图片">
          <div class="image-uploader">
            <el-image v-if="form.imageUrl" class="image-preview" :src="form.imageUrl" fit="cover" />
            <div v-else class="image-placeholder">请上传商品图</div>
            <el-upload
              accept=".jpg,.jpeg,.png,.webp"
              :show-file-list="false"
              :http-request="handleImageUpload"
            >
              <el-button :loading="uploading">上传图片</el-button>
            </el-upload>
          </div>
        </el-form-item>
        <el-form-item label="销售单位">
          <el-input v-model="form.saleUnit" placeholder="斤、份、盒等" />
        </el-form-item>
        <el-form-item label="单价（分）">
          <el-input-number v-model="form.unitPrice" :min="1" class="full" />
        </el-form-item>
        <el-form-item label="起购量">
          <el-input-number v-model="form.minPurchaseQty" :min="0.001" :step="0.5" class="full" />
        </el-form-item>
        <el-form-item label="步进值">
          <el-input-number v-model="form.stepQty" :min="0.001" :step="0.5" class="full" />
        </el-form-item>
        <el-form-item label="库存">
          <el-input-number v-model="form.stockQty" :min="0" :step="0.5" class="full" />
        </el-form-item>
        <el-form-item label="标签">
          <el-input v-model="form.badge" placeholder="热销、新鲜等，可不填" />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="form.status" :active-value="1" :inactive-value="0" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="saveProduct">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import {
  createCategory,
  createProduct,
  deleteProduct,
  getCategories,
  getProducts,
  updateProduct,
  updateProductStatus,
  updateProductStock,
  uploadProductImage
} from "../api/products";

const products = ref([]);
const categories = ref([]);
const dialogVisible = ref(false);
const uploading = ref(false);
const form = reactive(emptyForm());

function emptyForm() {
  return {
    id: 0,
    categoryId: null,
    name: "",
    subtitle: "",
    imageUrl: "",
    saleUnit: "斤",
    unitPrice: 1,
    minPurchaseQty: 0.5,
    stepQty: 0.5,
    stockQty: 0,
    badge: "",
    status: 1
  };
}

function assignForm(data) {
  Object.assign(form, emptyForm(), data);
}

async function loadData() {
  const [categoryResult, productResult] = await Promise.all([getCategories(), getProducts()]);
  categories.value = categoryResult || [];
  products.value = productResult.items || [];
}

async function createCategoryByPrompt() {
  const result = await ElMessageBox.prompt("请输入分类名称", "新增分类", {
    inputPattern: /\S+/,
    inputErrorMessage: "分类名称不能为空"
  });
  const sortOrder = (categories.value.length + 1) * 10;
  const category = await createCategory({ name: result.value.trim(), sortOrder });
  await loadData();
  form.categoryId = category.id;
  ElMessage.success("分类已新增");
}

function openCreate() {
  assignForm({ categoryId: categories.value[0]?.id || null });
  dialogVisible.value = true;
}

function openEdit(row) {
  assignForm(row);
  dialogVisible.value = true;
}

async function handleImageUpload(option) {
  uploading.value = true;
  try {
    const result = await uploadProductImage(option.file);
    form.imageUrl = result.url;
    option.onSuccess?.(result);
    ElMessage.success("图片已上传");
  } catch (error) {
    option.onError?.(error);
    ElMessage.error(error.message || "图片上传失败");
  } finally {
    uploading.value = false;
  }
}

async function saveProduct() {
  if (!form.categoryId) {
    ElMessage.warning("请先选择或新增分类");
    return;
  }
  if (!form.name || !form.imageUrl || !form.saleUnit) {
    ElMessage.warning("请填写完整商品信息");
    return;
  }
  const payload = { ...form };
  if (form.id) {
    await updateProduct(form.id, payload);
  } else {
    await createProduct(payload);
  }
  dialogVisible.value = false;
  await loadData();
  ElMessage.success("已保存");
}

async function handleStockChange(row, value) {
  await updateProductStock(row.id, value || 0);
  ElMessage.success("库存已更新");
}

async function toggleStatus(row) {
  await updateProductStatus(row.id, row.status === 1 ? 0 : 1);
  await loadData();
}

async function removeProduct(row) {
  await ElMessageBox.confirm(`确认删除 ${row.name}？`, "删除商品", { type: "warning" });
  await deleteProduct(row.id);
  await loadData();
  ElMessage.success("已删除");
}

onMounted(loadData);
</script>

<style scoped>
.toolbar-actions,
.inline-field,
.image-uploader {
  display: flex;
  align-items: center;
  gap: 12px;
}

.full {
  width: 100%;
}

.stock-input {
  width: 120px;
}

.product-thumb,
.empty-thumb {
  width: 52px;
  height: 52px;
  border-radius: 8px;
}

.empty-thumb,
.image-placeholder {
  display: flex;
  align-items: center;
  justify-content: center;
  color: #7a8580;
  background: #f2f5f3;
  border: 1px dashed #cfd9d2;
}

.image-preview,
.image-placeholder {
  width: 96px;
  height: 96px;
  border-radius: 8px;
}
</style>
