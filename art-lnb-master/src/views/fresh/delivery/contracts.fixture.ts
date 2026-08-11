/**
 * 配送管理端的编译期契约夹具。
 *
 * 项目未安装 Vitest；这些 `satisfies` 夹具由 `vue-tsc --noEmit` 校验，
 * 用来防止管理端再次把后端信封、分页和扁平统计误写成其他形状。
 */
import type { PageResult } from '@/api/admin'
import type {
  AdminRider,
  DeliveryTaskCard,
  DeliveryTaskDetail,
  SettlementGenerateResult,
  WaveDetail,
  WaveReplayData
} from '@/api/delivery'

export const taskCardContractFixture = {
  taskId: 101,
  taskNo: 'PS202608120001',
  orderNo: 'DD202608120001',
  status: 'PENDING',
  statusText: '待派单',
  seqNo: null,
  totalStops: null,
  receiverName: '测试收货人',
  receiverPhoneMasked: '138****0000',
  callNumber: '',
  phoneDegraded: false,
  addressDetail: '测试路 1 号',
  areaLabel: '测试小区',
  buildingLabel: '1 栋',
  unitNo: null,
  floorNo: null,
  roomNo: '101',
  location: { lat: 30.1, lng: 120.1 },
  distanceFromRiderMeters: null,
  itemCount: 2,
  totalWeightKg: 1.5,
  packageCount: 1,
  coldChainLevel: 'NORMAL',
  coldChainText: '常温',
  goodsSummary: '测试商品',
  customerRemark: '',
  deliveryInstruction: '',
  highlightNotes: [],
  slotLabel: '09:00-10:00',
  promisedAt: '2026-08-12T10:00:00',
  etaAt: null,
  remainingSeconds: null,
  overtimeRisk: 'LOW',
  requireVerifyCode: false,
  requirePhoto: false,
  sameAddressTaskCount: 1
} satisfies DeliveryTaskCard

export const taskDetailContractFixture = {
  card: taskCardContractFixture,
  items: [
    {
      productName: '测试商品',
      quantity: 2,
      unit: '件',
      weightKg: 1.5,
      amount: 1200
    }
  ],
  events: [
    {
      id: 1,
      eventType: 'STATUS_CHANGE',
      fromStatus: null,
      toStatus: 'PENDING',
      operatorType: 'SYSTEM',
      operatorName: null,
      reason: null,
      clientEventAt: null,
      createdAt: '2026-08-12T08:00:00'
    }
  ],
  evidences: [
    {
      id: 1,
      fileUrl: '/uploads/test.jpg',
      evidenceType: 'DELIVERY_PHOTO',
      capturedAt: '2026-08-12T09:00:00'
    }
  ]
} satisfies DeliveryTaskDetail

export const taskPageContractFixture = {
  items: [taskCardContractFixture],
  total: 1,
  page: 1,
  pageSize: 20
} satisfies PageResult<DeliveryTaskCard>

export const waveDetailContractFixture = {
  waveId: 201,
  waveNo: 'BC202608120001',
  riderId: 1,
  riderName: '测试骑手',
  status: 'PLANNED',
  deliveryDate: '2026-08-12',
  taskCount: 1,
  completedCount: 0,
  totalWeightKg: 1.5,
  totalItemCount: 2,
  maxColdChainLevel: 'NORMAL',
  planDistanceMeters: 1200,
  planDurationSeconds: 600,
  actualDistanceMeters: null,
  planReturnAt: '2026-08-12T10:30:00',
  optimizerName: 'DEFAULT',
  matrixProvider: 'HAVERSINE',
  assignedAt: null,
  startedAt: null,
  completedAt: null,
  stops: [
    {
      taskId: 101,
      seqNo: 1,
      originalSeqNo: 1,
      location: { lat: 30.1, lng: 120.1 },
      legDistanceMeters: 1200,
      legDurationSeconds: 600,
      handoffEstimateSeconds: 180,
      planArriveAt: '2026-08-12T09:30:00',
      planDepartAt: '2026-08-12T09:33:00',
      actualArriveAt: null,
      actualDepartAt: null,
      adjustedByRider: false
    }
  ],
  route: null,
  track: []
} satisfies WaveDetail

export const wavePageContractFixture = {
  items: [waveDetailContractFixture],
  total: 1,
  page: 1,
  pageSize: 20
} satisfies PageResult<WaveDetail>

export const waveReplayContractFixture = {
  waveId: 201,
  waveNo: 'BC202608120001',
  speed: 2,
  from: null,
  to: null,
  points: [{ lat: 30.1, lng: 120.1, locatedAt: '2026-08-12T09:00:00' }],
  stops: waveDetailContractFixture.stops
} satisfies WaveReplayData

export const riderAdminContractFixture = {
  id: 1,
  riderNo: 'QS000001',
  name: '测试骑手',
  phone: '13800000000',
  avatarUrl: '',
  idCardMasked: '330***********0000',
  role: 'RIDER',
  accountStatus: 'ACTIVE',
  workStatus: 'OFF_DUTY',
  healthCertNo: null,
  healthCertExpireAt: '2026-09-01',
  vehiclePlate: '',
  vehicleType: 'EBIKE',
  maxConcurrentTask: 8,
  capacityWeightKg: 30,
  insulatedBoxCount: 1,
  probation: false,
  hiredAt: '2026-01-01',
  serviceScore: 100,
  levelCode: 'L1',
  totalTaskCount: 10,
  onTimeTaskCount: 9,
  onTimeRate: 0.9,
  locationConsentAt: null,
  remark: null,
  createdAt: '2026-01-01T00:00:00',
  todayDeliveredCount: 2,
  todayOnTimeRate: 1,
  weekDeliveredCount: 8,
  weekOnTimeRate: 0.875,
  initialPassword: null
} satisfies AdminRider

export const settlementGenerateContractFixture = {
  generatedCount: 2,
  settlementIds: [301, 302]
} satisfies SettlementGenerateResult
