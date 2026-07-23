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
  iconUrl: string
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
  createdAt?: string
  latestRefundStatus?: string
  latestRefundReason?: string
  address: Address
  deliveryDate: string
  deliveryArea: string
  deliveryBuilding: string
  deliveryGroupKey: string
  deliverySequence: number
  buildingOrderCount: number
  buildingOrderPosition: number
  sameAddressOrderCount: number
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
  userId: number
  refunds: Refund[]
}

export interface Refund {
  id: number
  orderId: number
  refundNo: string
  refundAmount: number
  reason: string
  status: string
  evidenceImages: string[]
  userId: number
  orderNo: string
  source: 'USER' | 'ADMIN'
  createdAt: string
}

export interface AdminCustomer {
  userId: number
  nickName: string
  avatarUrl: string
  orderCount: number
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

export interface PrinterConfig {
  enabled: boolean
  autoPrintOnPaid: boolean
  retryLimit: number
  printerModel: string
  accessKeyConfigured: boolean
  agentOnline: boolean
  agentName: string
  agentConnection: string
  agentVersion: string
  agentLastSeen: string
  pendingJobCount: number
  failedJobCount: number
}

export interface PrintReceiptItem {
  name: string
  quantity: string
  unitPrice: string
  amount: string
}

export interface PrintReceipt {
  storeName: string
  title: string
  orderNo: string
  createdAt: string
  deliverySlot: string
  customerName: string
  customerPhone: string
  address: string
  items: PrintReceiptItem[]
  productAmount: string
  deliveryFee: string
  packageFee: string
  payableAmount: string
  remark: string
}

export interface PrintJob {
  id: number
  orderId?: number | null
  type: string
  orderNo: string
  status: string
  attemptCount: number
  retryLimit: number
  createdAt: string
  nextAttemptAt?: string | null
  printedAt?: string | null
  lastError?: string | null
  leaseToken?: string | null
  receipt: PrintReceipt
}

const productPayload = (data: Product) => {
  const payload = { ...data }
  delete payload.id
  return payload
}

const deliverySlotPayload = (data: DeliverySlot) => {
  const payload = { ...data }
  delete payload.id
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

export function uploadCategoryImage(file: File) {
  const data = new FormData()
  data.append('file', file)
  return request.post<{ url: string }>({
    url: '/api/admin/categories/images',
    data
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

export function getOrders(status?: string, deliveryDate?: string) {
  const params: Record<string, string> = {}
  if (status && status !== '全部') params.status = status
  if (deliveryDate) params.deliveryDate = deliveryDate
  return request.get<PageResult<OrderSummary>>({
    url: '/api/admin/orders',
    params: Object.keys(params).length ? params : undefined
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

export function getRefunds(params?: { userId?: number; orderId?: number }) {
  return request.get<PageResult<Refund>>({
    url: '/api/admin/refunds',
    params
  })
}

export function searchCustomers(accountId: number) {
  return request.get<PageResult<AdminCustomer>>({
    url: '/api/admin/customers',
    params: { accountId }
  })
}

export function createAdminRefund(data: {
  userId: number
  orderId: number
  refundAmount: number
  reason: string
}) {
  return request.post<Refund>({
    url: '/api/admin/refunds',
    data
  })
}

export function updateRefundAmount(id: number, refundAmount: number) {
  return request.put<Refund>({
    url: `/api/admin/refunds/${id}/amount`,
    data: { refundAmount }
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

export function getPrinterConfig() {
  return request.get<PrinterConfig>({
    url: '/api/admin/printing/config'
  })
}

export function updatePrinterConfig(data: Pick<PrinterConfig, 'enabled' | 'autoPrintOnPaid' | 'retryLimit' | 'printerModel'>) {
  return request.put<PrinterConfig>({
    url: '/api/admin/printing/config',
    data
  })
}

export function regeneratePrinterAccessKey() {
  return request.post<{ accessKey: string }>({
    url: '/api/admin/printing/access-key'
  })
}

export function createPrinterTest() {
  return request.post<PrintJob>({
    url: '/api/admin/printing/test'
  })
}

export function getPrintJobs() {
  return request.get<PrintJob[]>({
    url: '/api/admin/printing/jobs'
  })
}

export function retryPrintJob(id: number) {
  return request.post<PrintJob>({
    url: `/api/admin/printing/jobs/${id}/retry`
  })
}
