import request from '@/utils/http'

export interface PageResult<T> {
  items: T[]
  total: number
  page: number
  pageSize: number
}

export interface DashboardSummary {
  todayOrderCount: number
  todaySalesAmount: number
  pendingOrderCount: number
  refundPendingCount: number
}

export interface DashboardExport {
  filename: string
  content: string
}

export interface Category {
  id?: number
  name: string
  sortOrder: number
}

export interface Product {
  id?: number
  categoryId: number | null
  name: string
  subtitle: string
  imageUrl: string
  saleUnit: string
  unitPrice: number
  minPurchaseQty: number
  stepQty: number
  stockQty: number
  badge: string
  status: number
  recommended: boolean
}

export interface OrderSummary {
  id: number
  orderNo: string
  status: string
  totalAmount: number
  deliverySlot: string
  summary: string
  images: string[]
}

export interface Address {
  id: number
  name: string
  phone: string
  detail: string
  locationName: string
  latitude?: number
  longitude?: number
  isDefault: boolean
}

export interface OrderItem {
  id: number
  productId: number
  productName: string
  imageUrl: string
  saleUnit: string
  unitPrice: number
  quantity: number
  amount: number
}

export interface OrderDetail {
  id: number
  orderNo: string
  status: string
  address: Address
  deliverySlot: string
  items: OrderItem[]
  productAmount: number
  deliveryFee: number
  packageFee: number
  payableAmount: number
  paidAmount: number
  refundedAmount: number
  remark: string
  createdAt?: string
  latestRefundStatus?: string
  latestRefundReason?: string
}

export interface Refund {
  id: number
  orderId: number
  refundNo: string
  refundAmount: number
  reason: string
  status: string
  evidenceImages: string[]
}

export interface DeliverySlot {
  id?: number
  label: string
  maxOrders: number
  available: boolean
}

export interface StoreSettings {
  storeName: string
  logoUrl: string
  minOrderAmount: number
  deliveryFee: number
  packageFee: number
  businessHours: string
  contactPhone: string
  firstOrderFreeDelivery: boolean
  freeDeliveryCampaigns: FreeDeliveryCampaign[]
}

export interface FreeDeliveryCampaign {
  id?: number
  reason: string
  startDate: string
  endDate: string
  enabled: boolean
}

export interface StockOverviewItem {
  productId: number
  productName: string
  imageUrl: string
  saleUnit: string
  quantity: number
  orderCount: number
  amount: number
  orderNos: string[]
}

export interface Banner {
  id?: number
  title: string
  subtitle: string
  imageUrl: string
  linkType: string
  linkTarget: string
  sortOrder: number
  enabled: boolean
}

const productPayload = (data: Product) => {
  const { id: _id, ...payload } = data
  return payload
}

const deliverySlotPayload = (data: DeliverySlot) => {
  const { id: _id, ...payload } = data
  return payload
}

export function getDashboardSummary(date?: string) {
  return request.get<DashboardSummary>({
    url: '/api/admin/dashboard/summary',
    params: date ? { date } : undefined
  })
}

export function exportDashboard(startDate?: string, endDate?: string) {
  return request.get<DashboardExport>({
    url: '/api/admin/dashboard/export',
    params: {
      ...(startDate ? { startDate } : {}),
      ...(endDate ? { endDate } : {})
    }
  })
}

export function getCategories() {
  return request.get<Category[]>({
    url: '/api/admin/categories'
  })
}

export function createCategory(data: Category) {
  return request.post<Category>({
    url: '/api/admin/categories',
    data
  })
}

export function updateCategory(id: number, data: Category) {
  return request.put<Category>({
    url: `/api/admin/categories/${id}`,
    data
  })
}

export function deleteCategory(id: number) {
  return request.del<void>({
    url: `/api/admin/categories/${id}`
  })
}

export function getProducts(params?: { categoryId?: number | null }) {
  return request.get<PageResult<Product>>({
    url: '/api/admin/products',
    params
  })
}

export function createProduct(data: Product) {
  return request.post<Product>({
    url: '/api/admin/products',
    data: productPayload(data)
  })
}

export function updateProduct(id: number, data: Product) {
  return request.put<Product>({
    url: `/api/admin/products/${id}`,
    data: productPayload(data)
  })
}

export function deleteProduct(id: number) {
  return request.del<void>({
    url: `/api/admin/products/${id}`
  })
}

export function updateProductStatus(id: number, status: number) {
  return request.put<Product>({
    url: `/api/admin/products/${id}/status`,
    data: { status }
  })
}

export function updateProductStock(id: number, stockQty: number) {
  return request.put<Product>({
    url: `/api/admin/products/${id}/stock`,
    data: { stockQty }
  })
}

export function uploadProductImage(file: File) {
  const data = new FormData()
  data.append('file', file)
  return request.post<{ url: string }>({
    url: '/api/admin/products/images',
    data
  })
}

export function getOrders(status?: string) {
  return request.get<PageResult<OrderSummary>>({
    url: '/api/admin/orders',
    params: status && status !== '全部' ? { status } : undefined
  })
}

export function getOrderDetail(id: number) {
  return request.get<OrderDetail>({
    url: `/api/admin/orders/${id}`
  })
}

export function acceptOrder(id: number) {
  return request.post<OrderDetail>({
    url: `/api/admin/orders/${id}/accept`
  })
}

export function prepareOrder(id: number) {
  return request.post<OrderDetail>({
    url: `/api/admin/orders/${id}/prepare`
  })
}

export function deliverOrder(id: number) {
  return request.post<OrderDetail>({
    url: `/api/admin/orders/${id}/deliver`
  })
}

export function completeOrder(id: number) {
  return request.post<OrderDetail>({
    url: `/api/admin/orders/${id}/complete`
  })
}

export function cancelOrder(id: number) {
  return request.post<OrderDetail>({
    url: `/api/admin/orders/${id}/cancel`
  })
}

export function getRefunds() {
  return request.get<PageResult<Refund>>({
    url: '/api/admin/refunds'
  })
}

export function getRefundDetail(id: number) {
  return request.get<Refund>({
    url: `/api/admin/refunds/${id}`
  })
}

export function approveRefund(id: number) {
  return request.post<Refund>({
    url: `/api/admin/refunds/${id}/approve`
  })
}

export function rejectRefund(id: number, reason: string) {
  return request.post<Refund>({
    url: `/api/admin/refunds/${id}/reject`,
    data: { reason }
  })
}

export function getDeliverySlots() {
  return request.get<PageResult<DeliverySlot>>({
    url: '/api/admin/delivery-slots'
  })
}

export function createDeliverySlot(data: DeliverySlot) {
  return request.post<DeliverySlot>({
    url: '/api/admin/delivery-slots',
    data: deliverySlotPayload(data)
  })
}

export function updateDeliverySlot(id: number, data: DeliverySlot) {
  return request.put<DeliverySlot>({
    url: `/api/admin/delivery-slots/${id}`,
    data: deliverySlotPayload(data)
  })
}

export function deleteDeliverySlot(id: number) {
  return request.del<void>({
    url: `/api/admin/delivery-slots/${id}`
  })
}

export function updateDeliverySlotStatus(id: number, available: boolean) {
  return request.put<DeliverySlot>({
    url: `/api/admin/delivery-slots/${id}/status`,
    data: { available }
  })
}

export function getSettings() {
  return request.get<StoreSettings>({
    url: '/api/admin/settings'
  })
}

export function updateSettings(data: StoreSettings) {
  return request.put<StoreSettings>({
    url: '/api/admin/settings',
    data
  })
}

export function getStockOverview(date?: string) {
  return request.get<StockOverviewItem[]>({
    url: '/api/admin/stock/overview',
    params: date ? { date } : undefined
  })
}

export function getBanners() {
  return request.get<Banner[]>({
    url: '/api/admin/settings/banners'
  })
}

export function updateBanners(data: Banner[]) {
  return request.put<Banner[]>({
    url: '/api/admin/settings/banners',
    data
  })
}

export function uploadBannerImage(file: File) {
  const data = new FormData()
  data.append('file', file)
  return request.post<{ url: string }>({
    url: '/api/admin/settings/banners/images',
    data
  })
}

export function uploadLogoImage(file: File) {
  const data = new FormData()
  data.append('file', file)
  return request.post<{ url: string }>({
    url: '/api/admin/settings/logo-image',
    data
  })
}

export function seedDemoData() {
  return request.post<Record<string, number>>({
    url: '/api/admin/settings/demo-data/seed'
  })
}
