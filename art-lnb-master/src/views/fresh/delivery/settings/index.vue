<template>
  <div class="fresh-page">
    <div class="fresh-page__head">
      <div>
        <h1 class="fresh-page__title">调度参数</h1>
        <p class="fresh-page__desc">
          表单按后端下发的元数据动态渲染，后端新增配置项前端零改动。鼠标悬停问号可查看每项说明。
        </p>
      </div>
      <div class="fresh-toolbar__right">
        <span v-if="dirtyKeys.length > 0" class="settings-dirty">
          有 {{ dirtyKeys.length }} 项未保存
        </span>
        <ElButton :disabled="dirtyKeys.length === 0" @click="resetDraft">放弃修改</ElButton>
        <ElButton :loading="loading" @click="loadConfigs">刷新</ElButton>
        <ElButton
          type="primary"
          :loading="saving"
          :disabled="dirtyKeys.length === 0"
          @click="saveConfigs"
        >
          保存修改
        </ElButton>
      </div>
    </div>

    <ElCard v-loading="loading" class="fresh-card" shadow="never">
      <ElEmpty v-if="!loading && groups.length === 0" description="没有读取到配置项" />
      <ElTabs v-else v-model="activeCategory" tab-position="left" class="settings-tabs">
        <ElTabPane
          v-for="group in groups"
          :key="group.category"
          :name="group.category"
          :label="`${group.label}（${group.items.length}）`"
        >
          <div class="settings-list">
            <div v-for="item in group.items" :key="item.key" class="settings-item">
              <div class="settings-item__label">
                <div class="settings-item__title">
                  <strong>{{ item.displayName || item.key }}</strong>
                  <ElTooltip v-if="item.description" :content="item.description" placement="top">
                    <span class="settings-item__help">?</span>
                  </ElTooltip>
                  <ElTag v-if="!item.editable" size="small" type="info" effect="plain">只读</ElTag>
                </div>
                <code>{{ item.key }}</code>
              </div>

              <div class="settings-item__control">
                <ElSwitch
                  v-if="item.valueType === 'BOOL'"
                  :model-value="boolOf(item)"
                  :disabled="!item.editable"
                  @change="(value: boolean | string | number) => setValue(item, value)"
                />
                <ElInputNumber
                  v-else-if="item.valueType === 'INT' || item.valueType === 'DECIMAL'"
                  :model-value="numberOf(item)"
                  :min="item.minValue ?? undefined"
                  :max="item.maxValue ?? undefined"
                  :step="decimalStep(item)"
                  :precision="decimalPrecision(item)"
                  :disabled="!item.editable"
                  controls-position="right"
                  @change="(value: number | undefined) => setValue(item, value ?? '')"
                />
                <ElInput
                  v-else
                  :model-value="draft[item.key] || ''"
                  :type="isSensitiveConfig(item) ? 'password' : 'text'"
                  :show-password="isSensitiveConfig(item)"
                  :disabled="!item.editable"
                  class="settings-item__input"
                  @update:model-value="(value: string) => setValue(item, value)"
                />

                <span v-if="rangeHint(item)" class="settings-item__range">{{
                  rangeHint(item)
                }}</span>
                <ElTag v-if="isDirty(item.key)" size="small" type="warning" effect="light">
                  已修改
                </ElTag>
              </div>

              <p v-if="complianceNote(item)" class="settings-item__compliance">
                {{ complianceNote(item) }}
              </p>
            </div>
          </div>
        </ElTabPane>
      </ElTabs>
    </ElCard>
  </div>
</template>

<script setup lang="ts">
  import { ElMessage, ElMessageBox } from 'element-plus'
  import {
    getDeliveryConfigs,
    updateDeliveryConfigs,
    type DeliveryConfigItem
  } from '@/api/delivery'

  defineOptions({ name: 'FreshDeliverySettings' })

  /** 分类展示名与排序；未收录的分类按原样兜底展示，保证后端新增分类也能渲染 */
  const CATEGORY_LABELS: Record<string, string> = {
    STORE: '门店',
    DISPATCH: '派单',
    ROUTING: '路径规划',
    ETA: 'ETA 预估',
    TRACKING: '轨迹上报',
    GEOFENCE: '电子围栏',
    FATIGUE: '疲劳管理',
    EARNING: '骑手收入',
    DELIVERY: '配送交付',
    WEIGHT: '公平秤',
    PRIVACY: '隐私保护',
    PUSH: '消息推送',
    SCORE: '服务分',
    AMAP: '地图'
  }

  const CATEGORY_ORDER = Object.keys(CATEGORY_LABELS)

  const loading = ref(false)
  const saving = ref(false)
  const items = ref<DeliveryConfigItem[]>([])
  const draft = ref<Record<string, string>>({})
  const activeCategory = ref('')

  const groups = computed(() => {
    const map = new Map<string, DeliveryConfigItem[]>()
    items.value.forEach((item) => {
      const category = String(item.category || 'OTHER').toUpperCase()
      const list = map.get(category) || []
      list.push(item)
      map.set(category, list)
    })
    return [...map.entries()]
      .map(([category, list]) => ({
        category,
        label: CATEGORY_LABELS[category] || category,
        items: list.slice().sort((a, b) => a.key.localeCompare(b.key))
      }))
      .sort((a, b) => {
        const left = CATEGORY_ORDER.indexOf(a.category)
        const right = CATEGORY_ORDER.indexOf(b.category)
        return (left < 0 ? 999 : left) - (right < 0 ? 999 : right)
      })
  })

  const dirtyKeys = computed(() =>
    items.value.filter((item) => draft.value[item.key] !== item.value).map((item) => item.key)
  )

  const isDirty = (key: string) => dirtyKeys.value.includes(key)
  const isSensitiveConfig = (item: DeliveryConfigItem) =>
    item.valueType === 'STRING' && /(key|secret|token|password)/i.test(item.key)

  const displayConfigValue = (item: DeliveryConfigItem, value: string) => {
    if (!isSensitiveConfig(item)) return value
    return value ? '••••••' : '（空）'
  }

  const boolOf = (item: DeliveryConfigItem) =>
    ['true', '1', 'yes', 'on'].includes(String(draft.value[item.key] ?? '').toLowerCase())

  const numberOf = (item: DeliveryConfigItem) => {
    const value = Number(draft.value[item.key])
    return Number.isFinite(value) ? value : undefined
  }

  const isGeoDecimal = (item: DeliveryConfigItem) =>
    item.valueType === 'DECIMAL' && /(^|\.)(lat|lng)$/i.test(item.key)

  const decimalPrecision = (item: DeliveryConfigItem) => {
    if (item.valueType === 'INT') return 0
    return isGeoDecimal(item) ? 7 : 2
  }

  const decimalStep = (item: DeliveryConfigItem) => {
    if (item.valueType === 'INT') return 1
    return isGeoDecimal(item) ? 0.000001 : 0.01
  }

  const setValue = (item: DeliveryConfigItem, value: unknown) => {
    draft.value[item.key] = value === null || value === undefined ? '' : String(value)
  }

  const rangeHint = (item: DeliveryConfigItem) => {
    if (item.valueType !== 'INT' && item.valueType !== 'DECIMAL') return ''
    if (item.minValue === null && item.maxValue === null) return ''
    const min = item.minValue === null ? '不限' : item.minValue
    const max = item.maxValue === null ? '不限' : item.maxValue
    return `取值范围 ${min} ~ ${max}`
  }

  const complianceNote = (item: DeliveryConfigItem) => {
    if (/ebike_speed/i.test(item.key)) {
      return '依据 GB/T 46862-2025，电动车平均速度不得高于 15 km/h。调高该值会让 ETA 失真并诱导超速，属于合规红线。'
    }
    if (/retention_days/i.test(item.key)) {
      return '轨迹保留期受《个人信息保护法》约束，延长前需要重新评估告知与授权文案。'
    }
    return ''
  }

  const syncDraft = () => {
    const next: Record<string, string> = {}
    items.value.forEach((item) => {
      next[item.key] = item.value ?? ''
    })
    draft.value = next
  }

  const loadConfigs = async () => {
    loading.value = true
    try {
      const result = await getDeliveryConfigs()
      items.value = result.filter((item) => {
        if (!/^amap\./i.test(item.key)) return true
        return item.key.toLowerCase() === 'amap.js_key'
      })
      syncDraft()
      if (!activeCategory.value || !groups.value.some((g) => g.category === activeCategory.value)) {
        activeCategory.value = groups.value[0]?.category || ''
      }
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '配置加载失败')
    } finally {
      loading.value = false
    }
  }

  const resetDraft = async () => {
    try {
      await ElMessageBox.confirm('放弃当前未保存的修改？', '放弃修改', { type: 'warning' })
      syncDraft()
      ElMessage.success('已还原为已保存的值')
    } catch (error) {
      if (error !== 'cancel' && error !== 'close') {
        ElMessage.error(error instanceof Error ? error.message : '操作失败')
      }
    }
  }

  const saveConfigs = async () => {
    const changed = items.value.filter((item) => draft.value[item.key] !== item.value)
    if (changed.length === 0) return
    const preview = changed
      .slice(0, 5)
      .map(
        (item) =>
          `${item.displayName || item.key}：${displayConfigValue(item, item.value)} → ${displayConfigValue(
            item,
            draft.value[item.key]
          )}`
      )
      .join('\n')
    try {
      await ElMessageBox.confirm(
        `确认保存 ${changed.length} 项调度参数？参数会立即对下一轮调度生效。\n\n${preview}${
          changed.length > 5 ? '\n…' : ''
        }`,
        '保存调度参数',
        { type: 'warning', confirmButtonText: '确认保存', cancelButtonText: '取消' }
      )
      saving.value = true
      await updateDeliveryConfigs(
        changed.map((item) => ({ key: item.key, value: draft.value[item.key] }))
      )
      ElMessage.success(`已保存 ${changed.length} 项配置`)
      await loadConfigs()
    } catch (error) {
      if (error !== 'cancel' && error !== 'close') {
        ElMessage.error(error instanceof Error ? error.message : '配置保存失败')
      }
    } finally {
      saving.value = false
    }
  }

  onMounted(loadConfigs)
</script>

<style scoped lang="scss">
  @use '../../style.scss';

  .settings-dirty {
    color: var(--el-color-warning);
    font-size: 13px;
  }

  .settings-tabs {
    min-height: 420px;
  }

  .settings-list {
    display: grid;
    gap: 14px;
    padding-right: 8px;
  }

  .settings-item {
    display: grid;
    grid-template-columns: minmax(220px, 1fr) minmax(280px, 1fr);
    gap: 8px 20px;
    padding-bottom: 14px;
    border-bottom: 1px dashed var(--art-border-color);

    &:last-child {
      border-bottom: none;
    }
  }

  .settings-item__label {
    display: grid;
    gap: 4px;

    code {
      color: var(--art-gray-500);
      font-size: 12px;
    }
  }

  .settings-item__title {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 6px;

    strong {
      color: var(--art-gray-900);
    }
  }

  .settings-item__help {
    display: inline-grid;
    width: 16px;
    height: 16px;
    place-items: center;
    border-radius: 50%;
    color: var(--art-gray-600);
    font-size: 11px;
    background: var(--el-fill-color-dark);
    cursor: help;
  }

  .settings-item__control {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 10px;
  }

  .settings-item__input {
    width: 260px;
  }

  .settings-item__range {
    color: var(--art-gray-600);
    font-size: 12px;
  }

  .settings-item__compliance {
    grid-column: 1 / -1;
    padding: 8px 10px;
    margin: 0;
    border-radius: 8px;
    color: #a15c00;
    font-size: 12px;
    line-height: 18px;
    background: rgb(230 162 60 / 14%);
  }

  @media (max-width: 900px) {
    .settings-item {
      grid-template-columns: 1fr;
    }
  }
</style>
