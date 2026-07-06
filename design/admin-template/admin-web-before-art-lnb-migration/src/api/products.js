import request from "./request";

export function getCategories() {
  return request.get("/categories");
}

export function createCategory(data) {
  return request.post("/categories", data);
}

export function deleteCategory(id) {
  return request.delete(`/categories/${id}`);
}

export function getProducts(params = {}) {
  return request.get("/products", { params });
}

export function createProduct(data) {
  return request.post("/products", data);
}

export function updateProduct(id, data) {
  return request.put(`/products/${id}`, data);
}

export function deleteProduct(id) {
  return request.delete(`/products/${id}`);
}

export function updateProductStatus(id, status) {
  return request.put(`/products/${id}/status`, { status });
}

export function updateProductStock(id, stockQty) {
  return request.put(`/products/${id}/stock`, { stockQty });
}

export function uploadProductImage(file) {
  const formData = new FormData();
  formData.append("file", file);
  return request.post("/products/images", formData, {
    headers: {
      "Content-Type": "multipart/form-data"
    }
  });
}
