<template>
  <div class="fresh-page printing-page">
    <div class="fresh-page__head">
      <div>
        <h1 class="fresh-page__title">小票打印</h1>
        <p class="fresh-page__desc">支付成功后由门店 Windows 打印代理自动输出芯烨 XP-58 系列小票。</p>
      </div>
      <div class="page-actions">
        <ElButton :loading="loading" @click="loadAll">刷新状态</ElButton>
        <ElButton type="primary" :disabled="!form.enabled" :loading="testing" @click="createTest">测试打印</ElButton>
      </div>
    </div>

    <ElSkeleton v-if="loading && !ready" :rows="12" animated />
    <template v-else>
      <section class="status-grid">
        <ElCard shadow="never" class="status-card">
          <span>自动打印</span>
          <strong :class="form.enabled && form.autoPrintOnPaid ? 'is-success' : 'is-muted'">
            {{ form.enabled && form.autoPrintOnPaid ? '已启用' : '未启用' }}
          </strong>
          <small>仅在订单支付成功后创建小票任务</small>
        </ElCard>
        <ElCard shadow="never" class="status-card">
          <span>门店打印代理</span>
          <strong :class="config.agentOnline ? 'is-success' : 'is-warning'">
            {{ config.agentOnline ? '在线' : '离线' }}
          </strong>
          <small>{{ config.agentOnline ? `${config.agentName || 'Windows 代理'} · ${config.agentConnection}` : '请在门店电脑启动打印代理' }}</small>
        </ElCard>
        <ElCard shadow="never" class="status-card">
          <span>待处理任务</span>
          <strong>{{ config.pendingJobCount }}</strong>
          <small>失败任务 {{ config.failedJobCount }} 条，可手动重新打印</small>
        </ElCard>
      </section>

      <ElCard shadow="never" class="fresh-card config-card">
        <template #header>
          <div class="card-header">
            <div>
              <strong>打印配置</strong>
              <span>型号已预设为芯烨 XP-58III NT，支持 USB 和 Wi-Fi 局域网连接。</span>
            </div>
            <ElButton type="primary" :loading="saving" @click="saveConfig">保存配置</ElButton>
          </div>
        </template>
        <ElForm :model="form" label-width="150px" class="printing-form">
          <ElFormItem label="启用小票打印">
            <ElSwitch v-model="form.enabled" active-text="开启" inactive-text="关闭" />
            <span class="form-tip">关闭后不再创建新订单小票，打印代理也会暂停领取任务。</span>
          </ElFormItem>
          <ElFormItem label="支付成功自动打印">
            <ElSwitch v-model="form.autoPrintOnPaid" :disabled="!form.enabled" active-text="开启" inactive-text="关闭" />
          </ElFormItem>
          <ElFormItem label="失败重试次数">
            <ElInputNumber v-model="form.retryLimit" :min="1" :max="10" :disabled="!form.enabled" />
            <span class="form-tip">打印代理离线、缺纸或网络异常时自动重试。</span>
          </ElFormItem>
          <ElFormItem label="打印机型号">
            <ElInput v-model.trim="form.printerModel" maxlength="60" />
          </ElFormItem>
        </ElForm>

        <ElDivider />

        <div class="agent-setup">
          <div class="agent-setup__head">
            <div>
              <strong>门店 Windows 打印代理</strong>
              <p>代理使用已导入的芯烨 Windows SDK，在门店内网直接连接打印机；任务通过 HTTPS 传输，服务器仅保存代理密钥哈希。</p>
            </div>
            <ElButton type="primary" plain :loading="generatingKey" @click="generateKey">生成代理密钥</ElButton>
          </div>
          <ElAlert
            v-if="accessKey"
            class="access-key-alert"
            title="请立即复制并保存此密钥。页面关闭后无法再次查看，可重新生成。"
            type="warning"
            :closable="false"
          >
            <div class="access-key-row">
              <ElInput :model-value="accessKey" readonly />
              <ElButton type="primary" @click="copyKey">复制密钥</ElButton>
            </div>
          </ElAlert>
          <div class="agent-guide">
            <div><b>1</b><span>下载或复制 <code>print-agent/release</code> 整个目录到门店 Windows 电脑。</span></div>
            <div><b>2</b><span>双击 <code>setup-agent.cmd</code>，填写 API 地址、代理密钥以及 Wi-Fi IP 或 USB 设备路径。</span></div>
            <div><b>3</b><span>执行 <code>YulinPrintAgent.exe self-test</code> 确认硬件后，双击 <code>install-autostart.cmd</code> 设置门店电脑登录后自动启动。</span></div>
          </div>
        </div>
      </ElCard>

      <ElCard shadow="never" class="fresh-card jobs-card">
        <template #header>
          <div class="card-header">
            <div>
              <strong>打印任务</strong>
              <span>保留最近 200 条记录；失败任务可重新排队并由代理再次输出。</span>
            </div>
            <ElTag :type="config.agentOnline ? 'success' : 'warning'">{{ config.agentOnline ? '代理在线' : '代理离线' }}</ElTag>
          </div>
        </template>
        <ElTable :data="jobs" v-loading="loading" empty-text="暂无打印任务">
          <ElTableColumn prop="orderNo" label="订单/任务号" min-width="190">
            <template #default="{ row }">
              <strong>{{ row.orderNo }}</strong>
              <div class="table-sub">{{ row.type === 'TEST' ? '测试小票' : '订单小票' }}</div>
            </template>
          </ElTableColumn>
          <ElTableColumn label="状态" width="120">
            <template #default="{ row }">
              <ElTag :type="statusType(row.status)">{{ statusLabel(row.status) }}</ElTag>
            </template>
          </ElTableColumn>
          <ElTableColumn label="尝试次数" width="110">
            <template #default="{ row }">{{ row.attemptCount }}/{{ row.retryLimit }}</template>
          </ElTableColumn>
          <ElTableColumn prop="createdAt" label="创建时间" min-width="180" />
          <ElTableColumn label="结果" min-width="220">
            <template #default="{ row }">
              <span v-if="row.printedAt" class="is-success">{{ row.printedAt }} 已完成</span>
              <span v-else-if="row.lastError" class="error-text">{{ row.lastError }}</span>
              <span v-else>{{ row.nextAttemptAt || '等待代理领取' }}</span>
            </template>
          </ElTableColumn>
          <ElTableColumn label="操作" width="120" fixed="right">
            <template #default="{ row }">
              <ElButton size="small" plain :disabled="row.status === 'PRINTING'" :loading="retryingId === row.id" @click="retryJob(row)">补打</ElButton>
            </template>
          </ElTableColumn>
        </ElTable>
      </ElCard>
    </template>
  </div>
</template>

<script setup lang="ts">
  import { ElMessage, ElMessageBox } from 'element-plus'
  import {
    createPrinterTest,
    getPrintJobs,
    getPrinterConfig,
    regeneratePrinterAccessKey,
    retryPrintJob,
    updatePrinterConfig,
    type PrintJob,
    type PrinterConfig
  } from '@/api/admin'

  defineOptions({ name: 'FreshPrinting' })

  const loading = ref(false)
  const ready = ref(false)
  const saving = ref(false)
  const testing = ref(false)
  const generatingKey = ref(false)
  const retryingId = ref<number | null>(null)
  const accessKey = ref('')
  const jobs = ref<PrintJob[]>([])
  const config = reactive<PrinterConfig>({
    enabled: false,
    autoPrintOnPaid: true,
    retryLimit: 3,
    printerModel: 'XP-58III NT',
    accessKeyConfigured: false,
    agentOnline: false,
    agentName: '',
    agentConnection: '',
    agentVersion: '',
    agentLastSeen: '',
    pendingJobCount: 0,
    failedJobCount: 0
  })
  const form = reactive({
    enabled: false,
    autoPrintOnPaid: true,
    retryLimit: 3,
    printerModel: 'XP-58III NT'
  })

  const applyConfig = (value: PrinterConfig) => {
    Object.assign(config, value)
    Object.assign(form, {
      enabled: Boolean(value.enabled),
      autoPrintOnPaid: Boolean(value.autoPrintOnPaid),
      retryLimit: Number(value.retryLimit || 3),
      printerModel: value.printerModel || 'XP-58III NT'
    })
  }

  const loadAll = async () => {
    loading.value = true
    try {
      const [nextConfig, nextJobs] = await Promise.all([getPrinterConfig(), getPrintJobs()])
      applyConfig(nextConfig)
      jobs.value = nextJobs
      ready.value = true
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '小票打印配置加载失败')
    } finally {
      loading.value = false
    }
  }

  const saveConfig = async () => {
    saving.value = true
    try {
      applyConfig(await updatePrinterConfig({ ...form }))
      ElMessage.success('小票打印配置已保存')
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '保存失败')
    } finally {
      saving.value = false
    }
  }

  const generateKey = async () => {
    try {
      await ElMessageBox.confirm('重新生成后，旧代理将无法继续连接。是否继续？', '生成代理密钥', { type: 'warning' })
      generatingKey.value = true
      const result = await regeneratePrinterAccessKey()
      accessKey.value = result.accessKey
      await loadAll()
      ElMessage.success('代理密钥已生成')
    } catch (error) {
      if (error !== 'cancel' && error !== 'close') {
        ElMessage.error(error instanceof Error ? error.message : '生成代理密钥失败')
      }
    } finally {
      generatingKey.value = false
    }
  }

  const copyKey = async () => {
    try {
      await navigator.clipboard.writeText(accessKey.value)
      ElMessage.success('代理密钥已复制')
    } catch {
      ElMessage.warning('复制失败，请手动复制密钥')
    }
  }

  const createTest = async () => {
    testing.value = true
    try {
      await createPrinterTest()
      ElMessage.success('测试任务已加入队列')
      await loadAll()
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '创建测试任务失败')
    } finally {
      testing.value = false
    }
  }

  const retryJob = async (row: PrintJob) => {
    retryingId.value = row.id
    try {
      await retryPrintJob(row.id)
      ElMessage.success('任务已重新排队')
      await loadAll()
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '补打失败')
    } finally {
      retryingId.value = null
    }
  }

  const statusLabel = (status: string) => ({
    PENDING: '待打印',
    PRINTING: '打印中',
    RETRYING: '重试中',
    SUCCESS: '已完成',
    FAILED: '已失败'
  })[status] || status

  const statusType = (status: string) => ({
    PENDING: 'info',
    PRINTING: 'warning',
    RETRYING: 'warning',
    SUCCESS: 'success',
    FAILED: 'danger'
  })[status] as 'success' | 'warning' | 'info' | 'danger' | undefined

  let timer: number | undefined
  onMounted(() => {
    loadAll()
    timer = window.setInterval(loadAll, 10000)
  })
  onBeforeUnmount(() => {
    if (timer) window.clearInterval(timer)
  })
</script>

<style scoped lang="scss">
  @use '../style.scss';

  .page-actions,
  .access-key-row {
    display: flex;
    gap: 12px;
  }

  .status-grid {
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 16px;
    margin-bottom: 16px;
  }

  .status-card :deep(.el-card__body) {
    display: flex;
    flex-direction: column;
    min-height: 108px;
    gap: 8px;
  }

  .status-card span,
  .status-card small,
  .card-header span,
  .agent-setup p,
  .form-tip,
  .table-sub {
    color: var(--art-text-gray-600);
  }

  .status-card strong {
    font-size: 26px;
    line-height: 32px;
  }

  .status-card small,
  .table-sub,
  .form-tip {
    font-size: 12px;
  }

  .is-success {
    color: var(--el-color-success);
  }

  .is-warning {
    color: var(--el-color-warning);
  }

  .is-muted {
    color: var(--art-text-gray-600);
  }

  .error-text {
    color: var(--el-color-danger);
  }

  .config-card,
  .jobs-card {
    margin-top: 16px;
  }

  .card-header,
  .agent-setup__head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 16px;

    > div {
      display: flex;
      flex-direction: column;
      gap: 4px;
    }
  }

  .card-header span {
    font-size: 13px;
    font-weight: 400;
  }

  .printing-form {
    max-width: 860px;
  }

  .form-tip {
    margin-left: 12px;
  }

  .agent-setup {
    display: flex;
    flex-direction: column;
    gap: 16px;
  }

  .agent-setup p {
    max-width: 760px;
    margin: 4px 0 0;
    font-size: 13px;
    line-height: 20px;
  }

  .access-key-alert {
    margin: 0;
  }

  .agent-guide {
    display: grid;
    gap: 10px;

    > div {
      display: flex;
      align-items: flex-start;
      gap: 10px;
      color: var(--art-text-gray-700);
      font-size: 13px;
      line-height: 22px;
    }

    b {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      flex: 0 0 22px;
      width: 22px;
      height: 22px;
      color: var(--el-color-primary);
      border-radius: 50%;
      background: var(--el-color-primary-light-9);
    }

    code {
      padding: 1px 5px;
      color: var(--el-color-primary);
      border-radius: 4px;
      background: var(--el-color-primary-light-9);
    }
  }

  @media (max-width: 900px) {
    .status-grid {
      grid-template-columns: 1fr;
    }

    .card-header,
    .agent-setup__head {
      align-items: flex-start;
      flex-direction: column;
    }
  }
</style>
