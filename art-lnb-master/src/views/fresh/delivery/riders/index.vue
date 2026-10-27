<template>
  <div class="fresh-page">
    <div class="fresh-page__head">
      <div>
        <h1 class="fresh-page__title">骑手管理</h1>
        <p class="fresh-page__desc">骑手账号、装备与健康证管理，查看统计与班次历史。</p>
      </div>
      <div class="fresh-toolbar__right">
        <ElButton :loading="loading" @click="loadRiders">刷新</ElButton>
        <ElButton type="primary" @click="openCreate">新建骑手</ElButton>
      </div>
    </div>

    <ElCard class="fresh-card" shadow="never">
      <div class="fresh-toolbar">
        <div class="fresh-toolbar__left">
          <ElInput
            v-model="keyword"
            clearable
            class="filter-keyword"
            placeholder="搜索姓名、工号或手机号"
            @keyup.enter="reload"
          />
          <ElSelect
            v-model="status"
            clearable
            placeholder="全部账号状态"
            class="filter-item"
            @change="reload"
          >
            <ElOption
              v-for="item in ACCOUNT_STATUS_OPTIONS"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </ElSelect>
          <ElButton @click="resetFilters">重置筛选</ElButton>
        </div>
      </div>

      <ElTable
        v-loading="loading"
        :data="riders"
        row-key="id"
        empty-text="还没有骑手，点击右上角新建"
      >
        <ElTableColumn label="骑手" min-width="200">
          <template #default="{ row }">
            <div class="rider-cell">
              <ElAvatar :size="34" :src="resolveFreshAssetUrl(row.avatarUrl)">
                {{ row.name?.slice(0, 1) }}
              </ElAvatar>
              <div>
                <strong>{{ row.name }}</strong>
                <div class="muted">{{ row.riderNo }} · {{ row.phone }}</div>
              </div>
            </div>
          </template>
        </ElTableColumn>
        <ElTableColumn label="账号状态" width="100">
          <template #default="{ row }">
            <ElTag :type="accountStatusTag(row.accountStatus)" effect="light" size="small">
              {{ accountStatusText(row.accountStatus) }}
            </ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="在岗状态" width="110">
          <template #default="{ row }">
            <ElTag :type="workStatusTag(row.workStatus)" effect="plain" size="small">
              {{ workStatusText(row.workStatus) }}
            </ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="车辆" width="130">
          <template #default="{ row }">
            <div class="rider-stack">
              <span>{{ vehicleTypeText(row.vehicleType) }}</span>
              <span class="muted">{{ row.vehiclePlate || '无车牌' }}</span>
            </div>
          </template>
        </ElTableColumn>
        <ElTableColumn label="运力上限" width="130">
          <template #default="{ row }">
            <div class="rider-stack">
              <span>并发 {{ row.maxConcurrentTask }} 单</span>
              <span class="muted">载重 {{ Number(row.capacityWeightKg || 0).toFixed(0) }} kg</span>
            </div>
          </template>
        </ElTableColumn>
        <ElTableColumn label="服务分 / 准时率" width="140">
          <template #default="{ row }">
            <div class="rider-stack">
              <span>服务分 {{ row.serviceScore }}</span>
              <span class="muted"
                >准时 {{ percent(row.onTimeRate, 0) }} · 累计 {{ row.totalTaskCount }} 单</span
              >
            </div>
          </template>
        </ElTableColumn>
        <ElTableColumn label="健康证" width="140">
          <template #default="{ row }">
            <span v-if="!row.healthCertExpireAt" class="muted">未登记</span>
            <ElTag
              v-else
              :type="isHealthCertExpiringSoon(row.healthCertExpireAt) ? 'danger' : 'success'"
              effect="light"
              size="small"
            >
              {{ row.healthCertExpireAt }} 到期
            </ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="标签" width="110">
          <template #default="{ row }">
            <ElTag v-if="row.probation" type="warning" effect="light" size="small">试用期</ElTag>
            <ElTag v-if="row.workStatus === 'ON_DUTY'" type="success" effect="dark" size="small">
              在岗
            </ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="操作" width="290" fixed="right">
          <template #default="{ row }">
            <div class="rider-actions">
              <ElButton size="small" @click="goDetail(row)">详情</ElButton>
              <ElButton size="small" @click="openEdit(row)">编辑</ElButton>
              <ElButton size="small" plain @click="handleResetPassword(row)">重置密码</ElButton>
              <ElButton
                v-if="row.accountStatus === 'ACTIVE'"
                size="small"
                type="danger"
                plain
                @click="handleSuspend(row)"
              >
                停用
              </ElButton>
              <ElButton v-else size="small" type="success" plain @click="handleActivate(row)">
                启用
              </ElButton>
              <ElButton
                v-if="row.workStatus !== 'OFF_DUTY'"
                size="small"
                text
                type="danger"
                @click="handleForceOff(row)"
              >
                强制下班
              </ElButton>
            </div>
          </template>
        </ElTableColumn>
      </ElTable>

      <div class="rider-pager">
        <ElPagination
          v-model:current-page="page"
          v-model:page-size="pageSize"
          :total="total"
          :page-sizes="[20, 50, 100]"
          layout="total, sizes, prev, pager, next"
          background
          @current-change="loadRiders"
          @size-change="reload"
        />
      </div>
    </ElCard>

    <ElDialog v-model="formVisible" :title="editing ? '编辑骑手' : '新建骑手'" width="560px">
      <ElForm label-width="110px">
        <ElFormItem label="姓名" required>
          <ElInput v-model="form.name" maxlength="20" placeholder="真实姓名" />
        </ElFormItem>
        <ElFormItem label="手机号" required>
          <ElInput v-model="form.phone" maxlength="11" placeholder="登录账号，同时用于接收通知" />
        </ElFormItem>
        <ElFormItem label="车辆类型">
          <ElSelect v-model="form.vehicleType" class="form-full">
            <ElOption
              v-for="item in VEHICLE_TYPE_OPTIONS"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="车牌号">
          <ElInput v-model="form.vehiclePlate" maxlength="16" placeholder="电动车牌照，选填" />
        </ElFormItem>
        <ElFormItem label="并发上限">
          <ElInputNumber v-model="form.maxConcurrentTask" :min="1" :max="30" />
          <span class="form-hint">同时可携带的任务数</span>
        </ElFormItem>
        <ElFormItem label="载重上限">
          <ElInputNumber v-model="form.capacityWeightKg" :min="1" :max="200" :step="5" />
          <span class="form-hint">单位 kg</span>
        </ElFormItem>
        <ElFormItem label="健康证到期">
          <ElDatePicker
            v-model="form.healthCertExpireAt"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="选择到期日期"
            class="form-full"
          />
        </ElFormItem>
        <ElFormItem label="试用期">
          <ElSwitch v-model="form.probation" />
          <span class="form-hint">试用期骑手派单量会自动限制</span>
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="formVisible = false">取消</ElButton>
        <ElButton type="primary" :loading="saving" @click="submitForm">保存</ElButton>
      </template>
    </ElDialog>

    <ElDialog
      v-model="passwordVisible"
      title="初始密码"
      width="520px"
      :close-on-click-modal="false"
      :close-on-press-escape="false"
    >
      <ElAlert
        type="warning"
        :closable="false"
        show-icon
        title="请立即复制并交给骑手。关闭后无法再次查看，只能重新重置密码。"
      />
      <div class="password-row">
        <ElInput :model-value="initialPassword" type="password" show-password readonly />
        <ElButton type="primary" @click="copyPassword">复制密码</ElButton>
      </div>
      <p class="muted">骑手首次登录后会被强制修改密码。</p>
      <template #footer>
        <ElButton type="primary" @click="passwordVisible = false">我已保存</ElButton>
      </template>
    </ElDialog>
  </div>
</template>

<script setup lang="ts">
  import { ElMessage, ElMessageBox } from 'element-plus'
  import {
    activateRider,
    createRider,
    forceRiderOffDuty,
    getRiders,
    resetRiderPassword,
    suspendRider,
    updateRider,
    type AdminRider,
    type RiderUpsertPayload
  } from '@/api/delivery'
  import { resolveFreshAssetUrl } from '@/utils/fresh-assets'
  import {
    ACCOUNT_STATUS_OPTIONS,
    accountStatusTag,
    accountStatusText,
    isHealthCertExpiringSoon,
    percent,
    VEHICLE_TYPE_OPTIONS,
    vehicleTypeText,
    workStatusTag,
    workStatusText
  } from '../utils'

  defineOptions({ name: 'FreshDeliveryRiders' })

  const router = useRouter()

  const keyword = ref('')
  const status = ref('')
  const loading = ref(false)
  const saving = ref(false)
  const riders = ref<AdminRider[]>([])
  const page = ref(1)
  const pageSize = ref(20)
  const total = ref(0)

  const formVisible = ref(false)
  const editing = ref<AdminRider | null>(null)
  const passwordVisible = ref(false)
  const initialPassword = ref('')

  const createEmptyForm = (): RiderUpsertPayload => ({
    name: '',
    phone: '',
    vehicleType: 'EBIKE',
    vehiclePlate: '',
    maxConcurrentTask: 8,
    capacityWeightKg: 30,
    healthCertExpireAt: '',
    probation: true
  })

  const form = ref<RiderUpsertPayload>(createEmptyForm())

  const loadRiders = async () => {
    loading.value = true
    try {
      const result = await getRiders({
        keyword: keyword.value.trim() || undefined,
        status: status.value || undefined,
        page: page.value,
        pageSize: pageSize.value
      })
      riders.value = result.items || []
      total.value = result.total || 0
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '骑手列表加载失败')
    } finally {
      loading.value = false
    }
  }

  const reload = async () => {
    page.value = 1
    await loadRiders()
  }

  const resetFilters = async () => {
    keyword.value = ''
    status.value = ''
    await reload()
  }

  const openCreate = () => {
    editing.value = null
    form.value = createEmptyForm()
    formVisible.value = true
  }

  const openEdit = (row: AdminRider) => {
    editing.value = row
    form.value = {
      name: row.name,
      phone: row.phone,
      vehicleType: row.vehicleType || 'EBIKE',
      vehiclePlate: row.vehiclePlate || '',
      maxConcurrentTask: row.maxConcurrentTask,
      capacityWeightKg: row.capacityWeightKg ?? 30,
      healthCertExpireAt: row.healthCertExpireAt || '',
      probation: row.probation
    }
    formVisible.value = true
  }

  const submitForm = async () => {
    const payload = form.value
    if (!payload.name.trim() || !payload.phone.trim()) {
      ElMessage.warning('请填写姓名和手机号')
      return
    }
    if (!/^1\d{10}$/.test(payload.phone.trim())) {
      ElMessage.warning('请填写正确的 11 位手机号')
      return
    }
    const data: RiderUpsertPayload = {
      ...payload,
      name: payload.name.trim(),
      phone: payload.phone.trim(),
      vehiclePlate: payload.vehiclePlate?.trim() || undefined,
      healthCertExpireAt: payload.healthCertExpireAt || undefined
    }
    try {
      await ElMessageBox.confirm(
        editing.value
          ? `确认保存 ${data.name} 的资料修改？`
          : `确认新建骑手 ${data.name}？系统会生成初始密码。`,
        editing.value ? '编辑骑手' : '新建骑手',
        { type: 'warning' }
      )
      saving.value = true
      if (editing.value) {
        await updateRider(editing.value.id, data)
        ElMessage.success('骑手资料已更新')
      } else {
        const result = await createRider(data)
        initialPassword.value = result.initialPassword || ''
        passwordVisible.value = true
        ElMessage.success('骑手已创建')
      }
      formVisible.value = false
      await loadRiders()
    } catch (error) {
      if (error !== 'cancel' && error !== 'close') {
        ElMessage.error(error instanceof Error ? error.message : '操作失败')
      }
    } finally {
      saving.value = false
    }
  }

  const handleResetPassword = async (row: AdminRider) => {
    try {
      await ElMessageBox.confirm(
        `重置后 ${row.name} 的旧密码立即失效，需要用新密码重新登录。是否继续？`,
        '重置密码',
        { type: 'warning' }
      )
      const result = await resetRiderPassword(row.id)
      initialPassword.value = result.initialPassword
      passwordVisible.value = true
      ElMessage.success('密码已重置')
    } catch (error) {
      if (error !== 'cancel' && error !== 'close') {
        ElMessage.error(error instanceof Error ? error.message : '操作失败')
      }
    }
  }

  const copyPassword = async () => {
    try {
      await navigator.clipboard.writeText(initialPassword.value)
      ElMessage.success('初始密码已复制')
    } catch {
      ElMessage.warning('复制失败，请手动复制')
    }
  }

  const handleSuspend = async (row: AdminRider) => {
    try {
      await ElMessageBox.confirm(
        `停用后 ${row.name} 无法登录骑手端，在途任务需要先改派。是否继续？`,
        '停用骑手',
        { type: 'warning' }
      )
      await suspendRider(row.id)
      ElMessage.success('骑手已停用')
      await loadRiders()
    } catch (error) {
      if (error !== 'cancel' && error !== 'close') {
        ElMessage.error(error instanceof Error ? error.message : '操作失败')
      }
    }
  }

  const handleActivate = async (row: AdminRider) => {
    try {
      await ElMessageBox.confirm(`确认启用 ${row.name} 的账号？`, '启用骑手', { type: 'warning' })
      await activateRider(row.id)
      ElMessage.success('骑手已启用')
      await loadRiders()
    } catch (error) {
      if (error !== 'cancel' && error !== 'close') {
        ElMessage.error(error instanceof Error ? error.message : '操作失败')
      }
    }
  }

  const handleForceOff = async (row: AdminRider) => {
    try {
      const { value } = await ElMessageBox.prompt(
        `强制下班会立即撤销 ${row.name} 的登录会话，请填写原因。`,
        '强制下班',
        {
          type: 'warning',
          confirmButtonText: '确认下班',
          cancelButtonText: '取消',
          inputPlaceholder: '如：健康证过期',
          inputValidator: (input: string) => (input && input.trim() ? true : '请填写原因')
        }
      )
      await forceRiderOffDuty(row.id, value.trim())
      ElMessage.success('骑手已强制下班')
      await loadRiders()
    } catch (error) {
      if (error !== 'cancel' && error !== 'close') {
        ElMessage.error(error instanceof Error ? error.message : '操作失败')
      }
    }
  }

  const goDetail = (row: AdminRider) => {
    router.push(`/fresh/delivery/riders/${row.id}`)
  }

  onMounted(loadRiders)
</script>

<style scoped lang="scss">
  @use '../../style.scss';

  .filter-keyword {
    width: 260px;
  }

  .filter-item {
    width: 180px;
  }

  .rider-cell {
    display: flex;
    align-items: center;
    gap: 10px;

    strong {
      color: var(--art-gray-900);
    }
  }

  .rider-stack {
    display: grid;
    gap: 2px;
    font-size: 13px;
  }

  .rider-actions {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;

    :deep(.el-button + .el-button) {
      margin-left: 0;
    }

    :deep(.el-button) {
      padding-right: 8px;
      padding-left: 8px;
    }
  }

  .rider-pager {
    display: flex;
    justify-content: flex-end;
    margin-top: 16px;
  }

  .form-hint {
    margin-left: 10px;
    color: var(--art-gray-600);
    font-size: 12px;
  }

  .password-row {
    display: flex;
    gap: 10px;
    margin: 16px 0 8px;
  }
</style>
