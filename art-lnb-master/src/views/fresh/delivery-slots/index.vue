<template>
  <div class="fresh-page">
    <div class="fresh-page__head">
      <div>
        <h1 class="fresh-page__title">预约配送</h1>
        <p class="fresh-page__desc">维护每日循环规则，日期和星期由系统自动计算，无需每天修改。</p>
      </div>
      <ElButton type="primary" @click="openCreate">新增时间段</ElButton>
    </div>

    <ElCard class="fresh-card" shadow="never">
      <div class="fresh-toolbar">
        <ElButton :loading="loading" @click="loadSlots">刷新</ElButton>
      </div>

      <ElTable v-loading="loading" :data="slots" border empty-text="暂无配送时间段">
        <ElTableColumn prop="label" label="循环规则" min-width="180" />
        <ElTableColumn label="小程序当前显示" min-width="280">
          <template #default="{ row }">
            <strong class="slot-preview">{{ smartSlotLabel(row.label) }}</strong>
          </template>
        </ElTableColumn>
        <ElTableColumn prop="maxOrders" label="最大订单数" width="130" />
        <ElTableColumn label="状态" width="110">
          <template #default="{ row }">
            <ElTag :type="row.available ? 'success' : 'info'">
              {{ row.available ? '启用' : '停用' }}
            </ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="操作" width="240" fixed="right">
          <template #default="{ row }">
            <ElButton size="small" @click="openEdit(row)">编辑</ElButton>
            <ElButton size="small" @click="toggleStatus(row)">
              {{ row.available ? '停用' : '启用' }}
            </ElButton>
            <ElButton size="small" type="danger" plain @click="removeSlot(row)">删除</ElButton>
          </template>
        </ElTableColumn>
      </ElTable>
    </ElCard>

    <ElDialog v-model="dialogVisible" :title="form.id ? '编辑时间段' : '新增时间段'" width="480px">
      <ElForm :model="form" label-width="110px">
        <ElFormItem label="时间段" required>
          <ElInput v-model.trim="form.label" placeholder="例如：今日 09:00-11:00" />
          <div v-if="form.label" class="form-tip"> 当前预览：{{ smartSlotLabel(form.label) }} </div>
          <div class="form-tip">使用“今日”或“明日”作为循环规则，具体日期和星期会自动更新。</div>
        </ElFormItem>
        <ElFormItem label="最大订单数" required>
          <ElInputNumber v-model="form.maxOrders" :min="1" class="form-full" />
        </ElFormItem>
        <ElFormItem label="是否启用">
          <ElSwitch v-model="form.available" />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="dialogVisible = false">取消</ElButton>
        <ElButton type="primary" :loading="saving" @click="saveSlot">保存</ElButton>
      </template>
    </ElDialog>
  </div>
</template>

<script setup lang="ts">
  import { ElMessage, ElMessageBox } from 'element-plus'
  import {
    createDeliverySlot,
    deleteDeliverySlot,
    getDeliverySlots,
    updateDeliverySlot,
    updateDeliverySlotStatus,
    type DeliverySlot
  } from '@/api/admin'

  defineOptions({ name: 'FreshDeliverySlots' })

  const loading = ref(false)
  const saving = ref(false)
  const dialogVisible = ref(false)
  const slots = ref<DeliverySlot[]>([])
  const form = reactive<DeliverySlot>(emptyForm())
  const weekdays = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']

  const smartSlotLabel = (value: string) => {
    const source = String(value || '').trim()
    const match = source.match(/(\d{1,2}:\d{2})\s*[-~—至]\s*(\d{1,2}:\d{2})/)
    if (!match) return source
    const target = new Date()
    const tomorrow = source.includes('明日') || source.includes('明天')
    if (tomorrow) target.setDate(target.getDate() + 1)
    const relative = tomorrow ? '明日' : '今日'
    return `${relative} ${match[1]}-${match[2]}（${target.getMonth() + 1}月${target.getDate()}日 ${weekdays[target.getDay()]}）`
  }

  function emptyForm(): DeliverySlot {
    return {
      id: undefined,
      label: '',
      maxOrders: 30,
      available: true
    }
  }

  const loadSlots = async () => {
    loading.value = true
    try {
      const result = await getDeliverySlots()
      slots.value = result.items || []
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '配送时间段加载失败')
    } finally {
      loading.value = false
    }
  }

  const openCreate = () => {
    Object.assign(form, emptyForm())
    dialogVisible.value = true
  }

  const openEdit = (row: DeliverySlot) => {
    Object.assign(form, emptyForm(), row)
    dialogVisible.value = true
  }

  const saveSlot = async () => {
    if (!form.label) {
      ElMessage.warning('请填写配送时间段')
      return
    }
    saving.value = true
    try {
      if (form.id) {
        await updateDeliverySlot(form.id, form)
      } else {
        await createDeliverySlot(form)
      }
      dialogVisible.value = false
      await loadSlots()
      ElMessage.success('时间段已保存')
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '时间段保存失败')
    } finally {
      saving.value = false
    }
  }

  const toggleStatus = async (row: DeliverySlot) => {
    if (!row.id) return
    await updateDeliverySlotStatus(row.id, !row.available)
    await loadSlots()
  }

  const removeSlot = async (row: DeliverySlot) => {
    if (!row.id) return
    await ElMessageBox.confirm(`确认删除时间段「${row.label}」？`, '删除时间段', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消'
    })
    await deleteDeliverySlot(row.id)
    await loadSlots()
    ElMessage.success('时间段已删除')
  }

  onMounted(loadSlots)
</script>

<style scoped lang="scss">
  @use '../style.scss';

  .slot-preview {
    color: var(--el-color-primary);
  }

  .form-tip {
    width: 100%;
    margin-top: 6px;
    color: var(--el-text-color-secondary);
    font-size: 12px;
    line-height: 1.5;
  }
</style>
