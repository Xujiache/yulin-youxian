<template>
  <div class="fresh-page">
    <div class="fresh-page__head">
      <div>
        <h1 class="fresh-page__title">骑手 Android 版本</h1>
        <p class="fresh-page__desc">上传签名包、发布并推送给全部骑手。已发布记录会保留，回滚只改当前指针。</p>
      </div>
      <div class="fresh-toolbar__right">
        <ElButton :loading="loading" @click="reload">刷新</ElButton>
        <ElButton type="primary" @click="dialogVisible = true">上传 APK</ElButton>
      </div>
    </div>

    <ElAlert
      title="混合设备：公司纳管机能静默安装，普通手机必须经过系统确认"
      description="纳管用 Device Owner。出厂或恢复后执行 adb shell dpm set-device-owner com.yulin.rider/.core.update.RiderDeviceAdminReceiver。未纳管设备会先申请「安装未知应用」，再弹出系统安装确认，不能假装成静默更新。"
      type="info"
      :closable="false"
      show-icon
    />

    <ElCard v-if="coverage" class="fresh-card" shadow="never">
      <div class="metric-grid">
        <div>
          <div class="muted">当前版本</div>
          <div class="fresh-page__title">{{ coverage.currentVersionName || '尚未发布' }}</div>
          <div class="muted">versionCode {{ coverage.currentVersionCode ?? '—' }}</div>
        </div>
        <div>
          <div class="muted">近 7 日活跃设备</div>
          <div class="fresh-page__title">{{ coverage.activeDevices }}</div>
        </div>
        <div>
          <div class="muted">已到最新</div>
          <div class="fresh-page__title">{{ coverage.onLatest }}</div>
          <div class="muted">落后 {{ coverage.behind }} · 未知 {{ coverage.unknownVersion }}</div>
        </div>
        <div>
          <div class="muted">纳管 / 普通</div>
          <div class="fresh-page__title">{{ coverage.deviceOwner + coverage.profileOwner }} / {{ coverage.standard }}</div>
          <div class="muted">Device Owner {{ coverage.deviceOwner }} · Profile Owner {{ coverage.profileOwner }}</div>
        </div>
      </div>
    </ElCard>

    <ElCard class="fresh-card" shadow="never">
      <ElTable v-loading="loading" :data="releases" border empty-text="还没有版本记录">
        <ElTableColumn label="版本" min-width="160">
          <template #default="{ row }">
            <div>{{ row.versionName }}</div>
            <div class="muted">{{ row.versionCode }}</div>
          </template>
        </ElTableColumn>
        <ElTableColumn prop="title" label="更新标题" min-width="160" />
        <ElTableColumn label="策略" width="100">
          <template #default="{ row }">
            <ElTag :type="row.policy === 'FORCE' ? 'danger' : 'success'">
              {{ row.policy === 'FORCE' ? '强制' : '可选' }}
            </ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="状态" width="120">
          <template #default="{ row }">
            <ElTag :type="statusType(row)">{{ statusLabel(row) }}</ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="大小" width="110">
            <template #default="{ row }">{{ formatBytes(row.fileSize) }}</template>
        </ElTableColumn>
        <ElTableColumn label="SHA-256" min-width="140">
          <template #default="{ row }">
            <ElTooltip :content="row.fileSha256" placement="top">
              <span class="checksum">{{ (row.fileSha256 || '').slice(0, 12) }}…</span>
            </ElTooltip>
          </template>
        </ElTableColumn>
        <ElTableColumn label="源码 SHA" min-width="120">
          <template #default="{ row }">
            <span class="checksum">{{ (row.sourceSha || '—').slice(0, 10) }}</span>
          </template>
        </ElTableColumn>
        <ElTableColumn label="发布" min-width="170">
          <template #default="{ row }">
            <div>{{ row.publishedBy || '—' }}</div>
            <div class="muted">{{ formatDate(row.publishedAt) }}</div>
          </template>
        </ElTableColumn>
        <ElTableColumn label="推送" width="90">
          <template #default="{ row }">{{ row.notifyCount }}</template>
        </ElTableColumn>
        <ElTableColumn label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <ElButton
              v-if="row.status === 'DRAFT'"
              size="small"
              type="primary"
              :loading="actingId === row.id"
              @click="publish(row)"
            >
              发布并推送
            </ElButton>
            <ElButton
              v-if="row.status === 'PUBLISHED'"
              size="small"
              :loading="actingId === row.id"
              @click="notify(row)"
            >
              重新推送
            </ElButton>
            <ElButton
              v-if="row.status === 'PUBLISHED' && !row.current"
              size="small"
              @click="activate(row)"
            >
              设为当前
            </ElButton>
            <ElButton
              v-if="row.status !== 'DISABLED'"
              size="small"
              type="danger"
              plain
              @click="disable(row)"
            >
              停用
            </ElButton>
          </template>
        </ElTableColumn>
      </ElTable>
    </ElCard>

    <ElDialog v-model="dialogVisible" title="上传骑手 APK" width="560px" @closed="resetForm">
      <ElForm label-width="96px">
        <ElFormItem label="APK 文件" required>
          <ElUpload
            accept=".apk"
            :auto-upload="false"
            :limit="1"
            :on-change="onFileChange"
            :on-remove="() => (form.file = null)"
          >
            <ElButton>选择文件</ElButton>
            <template #tip>
              <div class="muted">仅接受已签名的 com.yulin.rider 安装包，最大 150 MB。</div>
            </template>
          </ElUpload>
          <ElProgress v-if="uploading" :percentage="uploadPercent" />
        </ElFormItem>
        <ElFormItem label="更新标题">
          <ElInput v-model.trim="form.title" maxlength="64" show-word-limit placeholder="骑手端 2026.08.15.1" />
        </ElFormItem>
        <ElFormItem label="更新日志">
          <ElInput v-model="form.notes" type="textarea" :rows="5" maxlength="2000" show-word-limit />
        </ElFormItem>
        <ElFormItem label="更新策略">
          <ElRadioGroup v-model="form.policy">
            <ElRadio value="OPTIONAL">可选（默认）</ElRadio>
            <ElRadio value="FORCE">强制（旧版无法进入业务页）</ElRadio>
          </ElRadioGroup>
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="dialogVisible = false">取消</ElButton>
        <ElButton type="primary" :loading="uploading" :disabled="!form.file" @click="upload">
          上传为草稿
        </ElButton>
      </template>
    </ElDialog>
  </div>
</template>

<script setup lang="ts">
  import { ElMessage, ElMessageBox, type UploadFile } from 'element-plus'
  import {
    activateRiderAppRelease,
    disableRiderAppRelease,
    getRiderAppCoverage,
    getRiderAppReleases,
    notifyRiderAppRelease,
    publishRiderAppRelease,
    uploadRiderAppRelease,
    type RiderAppCoverage,
    type RiderAppRelease
  } from '@/api/rider-app'

  defineOptions({ name: 'FreshRiderApp' })

  const loading = ref(false)
  const uploading = ref(false)
  const uploadPercent = ref(0)
  const actingId = ref<number | null>(null)
  const dialogVisible = ref(false)
  const releases = ref<RiderAppRelease[]>([])
  const coverage = ref<RiderAppCoverage | null>(null)
  const form = reactive({
    file: null as File | null,
    title: '',
    notes: '',
    policy: 'OPTIONAL' as 'OPTIONAL' | 'FORCE'
  })

  const formatDate = (value: string | null) => {
    if (!value) return '—'
    const parsed = new Date(value.includes('T') ? value : value.replace(' ', 'T'))
    if (Number.isNaN(parsed.getTime())) return value
    return parsed.toLocaleString('zh-CN', { hour12: false })
  }

  const formatBytes = (value: number) => {
    if (value < 1024) return `${value} B`
    if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`
    return `${(value / 1024 / 1024).toFixed(1)} MB`
  }

  const statusLabel = (row: RiderAppRelease) => {
    if (row.current) return '当前'
    if (row.status === 'DRAFT') return '草稿'
    if (row.status === 'DISABLED') return '已停用'
    if (row.status === 'PUBLISHED') return '已发布'
    return row.status
  }

  const statusType = (row: RiderAppRelease) => {
    if (row.current) return 'success'
    if (row.status === 'DRAFT') return 'info'
    if (row.status === 'DISABLED') return 'danger'
    return 'warning'
  }

  const reload = async () => {
    loading.value = true
    try {
      const [page, stats] = await Promise.all([
        getRiderAppReleases({ channel: 'production', page: 1, pageSize: 50 }),
        getRiderAppCoverage({ channel: 'production' })
      ])
      releases.value = page.items
      coverage.value = stats
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '版本列表加载失败')
    } finally {
      loading.value = false
    }
  }

  const onFileChange = (file: UploadFile) => {
    form.file = (file.raw as File) || null
  }

  const resetForm = () => {
    form.file = null
    form.title = ''
    form.notes = ''
    form.policy = 'OPTIONAL'
    uploadPercent.value = 0
  }

  const upload = async () => {
    if (!form.file) return
    uploading.value = true
    uploadPercent.value = 0
    try {
      const data = new FormData()
      data.append('file', form.file)
      data.append('channel', 'production')
      data.append('policy', form.policy)
      if (form.title) data.append('title', form.title)
      if (form.notes) data.append('notes', form.notes)
      await uploadRiderAppRelease(data, (percent) => {
        uploadPercent.value = percent
      })
      ElMessage.success('已保存为草稿，确认后再发布推送')
      dialogVisible.value = false
      await reload()
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '上传失败')
    } finally {
      uploading.value = false
    }
  }

  const publish = async (row: RiderAppRelease) => {
    try {
      await ElMessageBox.confirm(
        `发布 ${row.versionName}（${row.versionCode}）并推送给全部骑手？默认是可选更新。`,
        '发布并推送',
        { type: 'warning', confirmButtonText: '发布', cancelButtonText: '取消' }
      )
      actingId.value = row.id
      await publishRiderAppRelease(row.id)
      ElMessage.success('已发布并推送')
      await reload()
    } catch (error) {
      if (error !== 'cancel') ElMessage.error(error instanceof Error ? error.message : '发布失败')
    } finally {
      actingId.value = null
    }
  }

  const notify = async (row: RiderAppRelease) => {
    actingId.value = row.id
    try {
      const result = await notifyRiderAppRelease(row.id)
      ElMessage.success(`已重新推送 ${result.sentCount} 人`)
      await reload()
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '推送失败')
    } finally {
      actingId.value = null
    }
  }

  const activate = async (row: RiderAppRelease) => {
    actingId.value = row.id
    try {
      await activateRiderAppRelease(row.id)
      ElMessage.success('已把当前指针改到该版本')
      await reload()
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '回滚失败')
    } finally {
      actingId.value = null
    }
  }

  const disable = async (row: RiderAppRelease) => {
    try {
      await ElMessageBox.confirm(`停用 ${row.versionName}？历史记录会保留。`, '停用版本', {
        type: 'warning',
        confirmButtonText: '停用',
        cancelButtonText: '取消'
      })
      actingId.value = row.id
      await disableRiderAppRelease(row.id)
      await reload()
    } catch (error) {
      if (error !== 'cancel') ElMessage.error(error instanceof Error ? error.message : '停用失败')
    } finally {
      actingId.value = null
    }
  }

  onMounted(reload)
</script>

<style scoped>
  .checksum {
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
    font-size: 12px;
  }
</style>
