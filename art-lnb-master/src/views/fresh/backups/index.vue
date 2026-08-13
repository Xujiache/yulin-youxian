<template>
  <div class="fresh-page backup-page">
    <div class="fresh-page__head">
      <div>
        <h1 class="fresh-page__title">数据备份</h1>
        <p class="fresh-page__desc"
          >系统会在数据变更后自动备份，也可以手动创建或恢复完整数据快照。</p
        >
      </div>
      <div class="backup-actions">
        <ElButton :loading="loading" @click="loadBackups">刷新列表</ElButton>
        <ElButton type="primary" :loading="creating" @click="handleCreate">立即备份</ElButton>
      </div>
    </div>

    <ElAlert
      title="恢复会覆盖当前业务数据"
      description="恢复前系统会自动生成一份恢复保护备份。请确认备份时间和校验值后再执行恢复。"
      type="warning"
      :closable="false"
      show-icon
    />

    <ElCard class="fresh-card" shadow="never">
      <ElTable v-loading="loading" :data="backups" border empty-text="暂无备份">
        <ElTableColumn prop="fileName" label="备份文件" min-width="290" />
        <ElTableColumn prop="type" label="类型" width="120">
          <template #default="{ row }">
            <ElTag
              :type="
                row.type === 'PRE_RESTORE' ? 'warning' : row.type === 'MANUAL' ? 'success' : 'info'
              "
            >
              {{ typeLabel(row.type) }}
            </ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="创建时间" min-width="190">
          <template #default="{ row }">{{ formatDate(row.createdAt) }}</template>
        </ElTableColumn>
        <ElTableColumn label="大小" width="110">
          <template #default="{ row }">{{ formatBytes(row.sizeBytes) }}</template>
        </ElTableColumn>
        <ElTableColumn label="校验值" min-width="160">
          <template #default="{ row }">
            <ElTooltip :content="row.sha256" placement="top">
              <span class="checksum">{{ row.sha256.slice(0, 12) }}…</span>
            </ElTooltip>
          </template>
        </ElTableColumn>
        <ElTableColumn label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <ElButton
              size="small"
              type="warning"
              plain
              :loading="restoringFileName === row.fileName"
              @click="handleRestore(row)"
            >
              恢复
            </ElButton>
          </template>
        </ElTableColumn>
      </ElTable>
    </ElCard>
  </div>
</template>

<script setup lang="ts">
  import { ElMessage, ElMessageBox } from 'element-plus'
  import { createBackup, getBackups, restoreBackup, type BackupMetadata } from '@/api/admin'

  defineOptions({ name: 'FreshBackups' })

  const loading = ref(false)
  const creating = ref(false)
  const restoringFileName = ref('')
  const backups = ref<BackupMetadata[]>([])

  const formatDate = (value: string) => new Date(value).toLocaleString('zh-CN', { hour12: false })
  const formatBytes = (value: number) => {
    if (value < 1024) return `${value} B`
    if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`
    return `${(value / 1024 / 1024).toFixed(1)} MB`
  }
  const typeLabel = (type: string) =>
    ({ MANUAL: '手动', REALTIME: '实时', SCHEDULED: '定时', PRE_RESTORE: '恢复保护' })[type] || type

  const loadBackups = async () => {
    loading.value = true
    try {
      backups.value = await getBackups()
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '备份列表加载失败')
    } finally {
      loading.value = false
    }
  }

  const handleCreate = async () => {
    creating.value = true
    try {
      await createBackup()
      await loadBackups()
      ElMessage.success('手动备份已完成')
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '手动备份失败')
    } finally {
      creating.value = false
    }
  }

  const handleRestore = async (backup: BackupMetadata) => {
    try {
      await ElMessageBox.confirm(
        `恢复「${backup.fileName}」会覆盖当前业务数据，是否继续？恢复前系统会自动创建保护备份。`,
        '确认恢复数据',
        { type: 'warning', confirmButtonText: '确认恢复', cancelButtonText: '取消' }
      )
      restoringFileName.value = backup.fileName
      const result = await restoreBackup(backup.fileName)
      await loadBackups()
      ElMessage.success(`恢复完成，保护备份为 ${result.preRestoreBackup.fileName}`)
    } catch (error) {
      if (error !== 'cancel' && error !== 'close') {
        ElMessage.error(error instanceof Error ? error.message : '数据恢复失败')
      }
    } finally {
      restoringFileName.value = ''
    }
  }

  onMounted(loadBackups)
</script>

<style scoped lang="scss">
  @use '../style.scss';

  .backup-actions {
    display: flex;
    gap: 10px;
  }

  .checksum {
    color: var(--art-text-gray-600);
    font-family: monospace;
    font-size: 12px;
  }
</style>
