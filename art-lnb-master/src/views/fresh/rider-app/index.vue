<template>
  <div class="fresh-page rider-app-page">
    <div class="fresh-page__head">
      <div>
        <h1 class="fresh-page__title">骑手 Android 版本</h1>
        <p class="fresh-page__desc">发布签名包、查看覆盖情况，并把安装包直接发给手机。</p>
      </div>
      <div class="fresh-toolbar__right">
        <ElButton :loading="loading" @click="reload">
          <ArtSvgIcon icon="ri:refresh-line" />
          刷新
        </ElButton>
        <ElButton type="primary" @click="dialogVisible = true">
          <ArtSvgIcon icon="ri:upload-cloud-2-line" />
          上传 APK
        </ElButton>
      </div>
    </div>

    <section v-loading="loading && !coverage" class="hero-card">
      <div class="hero-card__main">
        <div class="hero-card__badge">
          <ArtSvgIcon icon="ri:android-line" />
          当前线上版本
        </div>
        <div class="hero-card__version">
          <strong>{{ currentRelease?.versionName || coverage?.currentVersionName || '尚未发布' }}</strong>
          <ElTag v-if="currentRelease" :type="currentRelease.policy === 'FORCE' ? 'danger' : 'success'" effect="light">
            {{ currentRelease.policy === 'FORCE' ? '强制更新' : '可选更新' }}
          </ElTag>
        </div>
        <p class="hero-card__meta">
          versionCode {{ currentRelease?.versionCode ?? coverage?.currentVersionCode ?? '—' }}
          <span>·</span>
          {{ currentRelease ? formatBytes(currentRelease.fileSize) : '暂无安装包' }}
          <span v-if="currentRelease?.publishedAt">· {{ formatDate(currentRelease.publishedAt) }}</span>
        </p>
        <p class="hero-card__notes">
          {{ currentRelease?.notes || currentRelease?.title || '还没有已发布的骑手安装包。' }}
        </p>
        <div class="hero-card__actions">
          <ElButton
            type="primary"
            :disabled="!canDownload(currentRelease)"
            @click="downloadApk(currentRelease)"
          >
            <ArtSvgIcon icon="ri:download-2-line" />
            下载安装包
          </ElButton>
          <ElButton :disabled="!canDownload(currentRelease)" @click="copyLink(currentRelease)">
            复制下载链接
          </ElButton>
          <ElButton text type="primary" @click="showManagedHelp = !showManagedHelp">
            {{ showManagedHelp ? '收起纳管说明' : '纳管 / 普通手机说明' }}
          </ElButton>
        </div>
        <div v-if="showManagedHelp" class="hero-card__help">
          公司纳管机能静默安装，普通手机必须经过系统确认。纳管用 Device Owner，出厂或恢复后执行
          <code>adb shell dpm set-device-owner com.yulin.rider/.core.update.RiderDeviceAdminReceiver</code>
          。未纳管设备会先申请「安装未知应用」，再弹出系统安装确认。
        </div>
      </div>
      <div class="hero-card__qr">
        <div v-if="canDownload(currentRelease)" class="qr-frame">
          <QrcodeVue
            :value="currentRelease!.fileUrl"
            :size="148"
            level="M"
            render-as="svg"
            foreground="#0f3d24"
            background="#ffffff"
          />
        </div>
        <div v-else class="qr-frame qr-frame--empty">暂无安装包</div>
        <strong>手机扫码安装</strong>
        <span>用手机浏览器打开后即可下载当前包</span>
      </div>
    </section>

    <div class="metric-grid">
      <div class="metric-card">
        <div class="metric-card__label">近 7 日活跃设备</div>
        <div class="metric-card__value">{{ coverage?.activeDevices ?? '—' }}</div>
      </div>
      <div class="metric-card">
        <div class="metric-card__label">已到最新</div>
        <div class="metric-card__value">{{ coverage?.onLatest ?? '—' }}</div>
        <div class="metric-card__hint">覆盖率 {{ coverageRate }}%</div>
      </div>
      <div class="metric-card">
        <div class="metric-card__label">仍需更新</div>
        <div class="metric-card__value">{{ coverage?.behind ?? '—' }}</div>
        <div class="metric-card__hint">未知版本 {{ coverage?.unknownVersion ?? 0 }}</div>
      </div>
      <div class="metric-card">
        <div class="metric-card__label">纳管 / 普通</div>
        <div class="metric-card__value">{{ managedCount }} / {{ coverage?.standard ?? 0 }}</div>
        <div class="metric-card__hint">
          Device Owner {{ coverage?.deviceOwner ?? 0 }} · Profile Owner {{ coverage?.profileOwner ?? 0 }}
        </div>
      </div>
    </div>

    <ElCard class="fresh-card history-card" shadow="never">
      <template #header>
        <div class="fresh-toolbar">
          <div>
            <strong>版本历史</strong>
            <p class="history-card__desc">已发布记录会保留，回滚只改当前指针，不会删包。</p>
          </div>
          <ElTag effect="light" type="success">{{ releases.length }} 条记录</ElTag>
        </div>
      </template>
      <ElTable
        v-loading="loading"
        :data="releases"
        border
        empty-text="还没有版本记录"
        :row-class-name="rowClassName"
      >
        <ElTableColumn label="版本" min-width="170">
          <template #default="{ row }">
            <div class="version-cell">
              <strong>{{ row.versionName }}</strong>
              <span>{{ row.versionCode }}</span>
            </div>
          </template>
        </ElTableColumn>
        <ElTableColumn label="更新说明" min-width="240">
          <template #default="{ row }">
            <div class="notes-cell">
              <strong>{{ row.title || '—' }}</strong>
              <span>{{ row.notes || '没有更新日志' }}</span>
            </div>
          </template>
        </ElTableColumn>
        <ElTableColumn label="策略 / 状态" width="140">
          <template #default="{ row }">
            <div class="tag-stack">
              <ElTag :type="row.policy === 'FORCE' ? 'danger' : 'success'" effect="plain" size="small">
                {{ row.policy === 'FORCE' ? '强制' : '可选' }}
              </ElTag>
              <ElTag :type="statusType(row)" size="small">{{ statusLabel(row) }}</ElTag>
            </div>
          </template>
        </ElTableColumn>
        <ElTableColumn label="安装包" min-width="170">
          <template #default="{ row }">
            <div class="package-cell">
              <strong>{{ formatBytes(row.fileSize) }}</strong>
              <ElButton
                v-if="canDownload(row)"
                link
                type="primary"
                @click="downloadApk(row)"
              >
                下载
              </ElButton>
              <span v-else class="muted">文件已清理</span>
              <ElPopover placement="top" :width="320" trigger="hover">
                <template #reference>
                  <button type="button" class="checksum-link">校验信息</button>
                </template>
                <div class="checksum-pop">
                  <div><span>SHA-256</span><code>{{ row.fileSha256 || '—' }}</code></div>
                  <div><span>证书</span><code>{{ row.certSha256 || '—' }}</code></div>
                  <div><span>源码</span><code>{{ row.sourceSha || '—' }}</code></div>
                </div>
              </ElPopover>
            </div>
          </template>
        </ElTableColumn>
        <ElTableColumn label="发布" min-width="160">
          <template #default="{ row }">
            <div class="notes-cell">
              <strong>{{ row.publishedBy || '未发布' }}</strong>
              <span>{{ formatDate(row.publishedAt) }} · 推送 {{ row.notifyCount ?? 0 }} 次</span>
            </div>
          </template>
        </ElTableColumn>
        <ElTableColumn label="操作" width="248" fixed="right">
          <template #default="{ row }">
            <div class="action-cell">
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
            </div>
          </template>
        </ElTableColumn>
      </ElTable>
    </ElCard>

    <ElDialog v-model="dialogVisible" title="上传骑手 APK" width="560px" @closed="resetForm">
      <ElForm label-position="top">
        <ElFormItem label="APK 文件" required>
          <ElUpload
            class="apk-uploader"
            drag
            accept=".apk"
            :auto-upload="false"
            :limit="1"
            :on-change="onFileChange"
            :on-remove="() => (form.file = null)"
          >
            <ArtSvgIcon icon="ri:android-line" class="apk-uploader__icon" />
            <div class="el-upload__text">把签名包拖到这里，或 <em>点击选择</em></div>
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
          <ElInput v-model="form.notes" type="textarea" :rows="4" maxlength="2000" show-word-limit />
        </ElFormItem>
        <ElFormItem label="更新策略">
          <ElRadioGroup v-model="form.policy" class="policy-group">
            <ElRadioButton value="OPTIONAL">可选（默认）</ElRadioButton>
            <ElRadioButton value="FORCE">强制（旧版无法进入业务页）</ElRadioButton>
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
  import QrcodeVue from 'qrcode.vue'
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
  const showManagedHelp = ref(false)
  const releases = ref<RiderAppRelease[]>([])
  const coverage = ref<RiderAppCoverage | null>(null)
  const form = reactive({
    file: null as File | null,
    title: '',
    notes: '',
    policy: 'OPTIONAL' as 'OPTIONAL' | 'FORCE'
  })

  const currentRelease = computed(
    () => releases.value.find((item) => item.current) || releases.value.find((item) => item.status === 'PUBLISHED') || null
  )
  const managedCount = computed(
    () => (coverage.value?.deviceOwner ?? 0) + (coverage.value?.profileOwner ?? 0)
  )
  const coverageRate = computed(() => {
    const active = coverage.value?.activeDevices ?? 0
    if (active <= 0) return 0
    return Math.round(((coverage.value?.onLatest ?? 0) / active) * 100)
  })

  const formatDate = (value: string | null) => {
    if (!value) return '—'
    const parsed = new Date(value.includes('T') ? value : value.replace(' ', 'T'))
    if (Number.isNaN(parsed.getTime())) return value
    return parsed.toLocaleString('zh-CN', { hour12: false })
  }

  const formatBytes = (value: number) => {
    if (!value) return '0 B'
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

  const canDownload = (row?: RiderAppRelease | null) => Boolean(row?.hasApkFile && row.fileUrl)

  const rowClassName = ({ row }: { row: RiderAppRelease }) => (row.current ? 'is-current-row' : '')

  const downloadApk = (row?: RiderAppRelease | null) => {
    if (!canDownload(row) || !row) return
    window.open(row.fileUrl, '_blank', 'noopener')
  }

  const copyLink = async (row?: RiderAppRelease | null) => {
    if (!canDownload(row) || !row) return
    try {
      await navigator.clipboard.writeText(row.fileUrl)
      ElMessage.success('下载链接已复制')
    } catch {
      ElMessage.error('复制失败，请手动复制地址栏链接')
    }
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

<style scoped lang="scss">
  @use '../style.scss';

  .rider-app-page {
    --rider-green: #0f7a3f;
    --rider-ink: #163126;
  }

  .hero-card {
    display: grid;
    grid-template-columns: minmax(0, 1fr) 210px;
    gap: 28px;
    padding: 22px 24px;
    border: 1px solid var(--art-border-color);
    border-radius: 16px;
    background:
      radial-gradient(circle at top right, rgba(15, 122, 63, 0.12), transparent 42%),
      linear-gradient(180deg, #f7fbf8 0%, #ffffff 70%);
  }

  .hero-card__badge {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    color: var(--rider-green);
    font-size: 12px;
    font-weight: 700;
    letter-spacing: 0.04em;
  }

  .hero-card__version {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 10px;
    margin-top: 8px;

    strong {
      color: var(--rider-ink);
      font-size: 34px;
      font-weight: 800;
      line-height: 1.1;
      letter-spacing: -0.03em;
    }
  }

  .hero-card__meta,
  .hero-card__notes,
  .history-card__desc {
    margin: 8px 0 0;
    color: var(--art-gray-600);
    font-size: 13px;
    line-height: 1.6;
  }

  .hero-card__notes {
    max-width: 640px;
  }

  .hero-card__actions {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 10px;
    margin-top: 18px;
  }

  .hero-card__help {
    margin-top: 14px;
    padding: 12px 14px;
    border-radius: 10px;
    color: #4b5c53;
    font-size: 12px;
    line-height: 1.7;
    background: #eef6f1;

    code {
      display: inline-block;
      margin-top: 4px;
      padding: 2px 6px;
      border-radius: 6px;
      color: var(--rider-ink);
      font-size: 11px;
      background: #fff;
    }
  }

  .hero-card__qr {
    display: grid;
    justify-items: center;
    align-content: start;
    gap: 8px;
    text-align: center;

    strong {
      color: var(--rider-ink);
      font-size: 14px;
    }

    span {
      color: var(--art-gray-600);
      font-size: 12px;
      line-height: 1.5;
    }
  }

  .qr-frame {
    display: grid;
    width: 168px;
    height: 168px;
    place-items: center;
    border-radius: 16px;
    background: #fff;
    box-shadow: 0 10px 30px rgba(15, 61, 36, 0.08);
  }

  .qr-frame--empty {
    color: var(--art-gray-500);
    font-size: 13px;
  }

  .metric-card__hint {
    margin-top: 6px;
    color: var(--art-gray-600);
    font-size: 12px;
  }

  .history-card {
    :deep(.el-card__header) {
      padding-bottom: 12px;
    }

    :deep(.is-current-row) {
      background: #f3faf5;
    }
  }

  .history-card__desc {
    margin-top: 4px;
  }

  .version-cell,
  .notes-cell,
  .package-cell,
  .tag-stack,
  .action-cell {
    display: flex;
    flex-direction: column;
    align-items: flex-start;
    gap: 4px;
  }

  .version-cell strong,
  .notes-cell strong,
  .package-cell strong {
    color: var(--rider-ink);
  }

  .version-cell span,
  .notes-cell span {
    color: var(--art-gray-600);
    font-size: 12px;
    line-height: 1.5;
  }

  .notes-cell span {
    display: -webkit-box;
    overflow: hidden;
    -webkit-box-orient: vertical;
    -webkit-line-clamp: 2;
  }

  .tag-stack,
  .action-cell {
    gap: 8px;
  }

  .action-cell {
    flex-direction: row;
    flex-wrap: wrap;
  }

  .checksum-link {
    padding: 0;
    border: 0;
    color: var(--rider-green);
    font-size: 12px;
    background: none;
    cursor: pointer;
  }

  .checksum-pop {
    display: grid;
    gap: 10px;
    font-size: 12px;

    span {
      display: block;
      margin-bottom: 4px;
      color: var(--art-gray-600);
    }

    code {
      display: block;
      overflow-wrap: anywhere;
      color: var(--rider-ink);
      font-size: 11px;
      line-height: 1.5;
    }
  }

  .apk-uploader {
    width: 100%;

    :deep(.el-upload),
    :deep(.el-upload-dragger) {
      width: 100%;
    }
  }

  .apk-uploader__icon {
    margin-bottom: 8px;
    color: var(--rider-green);
    font-size: 32px;
  }

  .policy-group {
    display: flex;
    flex-wrap: wrap;
  }

  @media (max-width: 900px) {
    .hero-card {
      grid-template-columns: 1fr;
    }

    .hero-card__qr {
      justify-items: start;
      text-align: left;
    }
  }
</style>
