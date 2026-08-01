<template>
  <div class="fresh-page">
    <div class="fresh-page__head">
      <div>
        <h1 class="fresh-page__title">门店设置</h1>
        <p class="fresh-page__desc">配置小程序门店信息、结算费用、联系电话和免配送规则。</p>
      </div>
      <div class="settings-actions">
        <ElButton :loading="seeding" @click="handleSeedDemoData">补齐示例数据</ElButton>
        <ElButton type="primary" :loading="saving" @click="saveSettings">保存配置</ElButton>
      </div>
    </div>

    <ElCard class="fresh-card" shadow="never">
      <template #header>
        <div class="card-header">
          <strong>基础信息</strong>
          <span>影响小程序门店名称、费用和联系电话，保存后用户端会实时读取。</span>
        </div>
      </template>
      <ElSkeleton v-if="loading" :rows="9" animated />
      <ElForm v-else :model="form" label-width="150px" class="settings-form">
        <ElFormItem label="门店名称" required>
          <ElInput v-model.trim="form.storeName" maxlength="30" show-word-limit />
        </ElFormItem>
        <ElFormItem label="首页 Logo">
          <div class="logo-upload-row">
            <ElImage class="logo-preview" :src="assetUrl(form.logoUrl)" fit="cover">
              <template #error>
                <div class="logo-preview__fallback">Logo</div>
              </template>
            </ElImage>
            <div class="logo-upload-main">
              <ElUpload
                :show-file-list="false"
                accept=".jpg,.jpeg,.png,.webp"
                :http-request="uploadLogo"
              >
                <ElButton :loading="uploadingLogo">上传 Logo</ElButton>
              </ElUpload>
              <p>建议使用 1:1 图片，小程序首页左上角展示。</p>
            </div>
          </div>
        </ElFormItem>
        <ElFormItem label="起送价（元）" required>
          <ElInputNumber
            :model-value="centToYuan(form.minOrderAmount)"
            :min="0"
            :precision="2"
            :step="1"
            class="form-full"
            @update:model-value="(value) => updateAmount('minOrderAmount', value)"
          />
        </ElFormItem>
        <ElFormItem label="配送费（元）" required>
          <ElInputNumber
            :model-value="centToYuan(form.deliveryFee)"
            :min="0"
            :precision="2"
            :step="0.5"
            class="form-full"
            @update:model-value="(value) => updateAmount('deliveryFee', value)"
          />
        </ElFormItem>
        <ElFormItem label="包装费（元）" required>
          <ElInputNumber
            :model-value="centToYuan(form.packageFee)"
            :min="0"
            :precision="2"
            :step="0.5"
            class="form-full"
            @update:model-value="(value) => updateAmount('packageFee', value)"
          />
        </ElFormItem>
        <ElFormItem label="营业时间" required>
          <ElInput v-model.trim="form.businessHours" placeholder="例如：08:00-20:00" />
        </ElFormItem>
        <ElFormItem label="联系电话" required>
          <ElInput v-model.trim="form.contactPhone" maxlength="20" />
        </ElFormItem>

        <ElDivider />

        <ElFormItem label="首单免配送">
          <ElSwitch
            v-model="form.firstOrderFreeDelivery"
            active-text="开启"
            inactive-text="关闭"
          />
          <span class="form-tip">开启后，新用户第一次下单自动减免配送费。</span>
        </ElFormItem>

        <ElFormItem label="全平台免配送">
          <div class="campaign-editor">
            <div class="campaign-create">
              <ElInput v-model.trim="newCampaign.reason" placeholder="活动原因，例如：开业福利" />
              <ElDatePicker
                v-model="newCampaign.range"
                class="campaign-range"
                type="daterange"
                value-format="YYYY-MM-DD"
                start-placeholder="开始日期"
                end-placeholder="结束日期"
              />
              <ElButton class="campaign-add" type="primary" plain @click="addCampaign">新增活动</ElButton>
            </div>
            <ElTable
              :data="form.freeDeliveryCampaigns"
              border
              empty-text="暂无全平台免配送活动"
            >
              <ElTableColumn prop="reason" label="原因" min-width="160" />
              <ElTableColumn label="时间范围" min-width="180">
                <template #default="{ row }">{{ row.startDate }} 至 {{ row.endDate }}</template>
              </ElTableColumn>
              <ElTableColumn label="状态" width="110">
                <template #default="{ row }">
                  <ElTag :type="row.enabled ? 'success' : 'info'">
                    {{ row.enabled ? '运行中' : '已终止' }}
                  </ElTag>
                </template>
              </ElTableColumn>
              <ElTableColumn label="操作" width="180">
                <template #default="{ row, $index }">
                  <ElButton size="small" @click="toggleCampaign(row)">
                    {{ row.enabled ? '手动终止' : '重新开启' }}
                  </ElButton>
                  <ElButton size="small" type="danger" plain @click="removeCampaign($index)">
                    删除
                  </ElButton>
                </template>
              </ElTableColumn>
            </ElTable>
          </div>
        </ElFormItem>
      </ElForm>
    </ElCard>
  </div>
</template>

<script setup lang="ts">
import { ElMessage } from 'element-plus';
import { getSettings, seedDemoData, updateSettings, uploadLogoImage, type FreeDeliveryCampaign, type StoreSettings } from '@/api/admin';
import { resolveFreshAssetUrl } from '@/utils/fresh-assets';
defineOptions({ name: 'FreshSettings' });
const loading = ref(false);
const saving = ref(false);
const seeding = ref(false);
const uploadingLogo = ref(false);
const form = reactive<StoreSettings>({
    storeName: '',
    logoUrl: '/assets/products/store-logo.png',
    minOrderAmount: 0,
    deliveryFee: 0,
    packageFee: 0,
    businessHours: '',
    contactPhone: '',
    firstOrderFreeDelivery: false,
    freeDeliveryCampaigns: []
});
const newCampaign = reactive<{
    reason: string;
    range: string[];
}>({
    reason: '',
    range: []
});
const centToYuan = (value: number) => Number((Number(value || 0) / 100).toFixed(2));
const yuanToCent = (value: number) => Math.max(0, Math.round(Number(value || 0) * 100));
const updateAmount = (field: 'minOrderAmount' | 'deliveryFee' | 'packageFee', value: number | undefined) => {
    form[field] = yuanToCent(Number(value || 0));
};
const normalizeCampaigns = (items?: FreeDeliveryCampaign[]) => (items || []).map((item) => ({
    id: item.id,
    reason: item.reason || '',
    startDate: item.startDate || '',
    endDate: item.endDate || '',
    enabled: Boolean(item.enabled)
}));
const applySettings = (settingsResult: StoreSettings) => {
    Object.assign(form, {
        ...settingsResult,
        firstOrderFreeDelivery: Boolean(settingsResult.firstOrderFreeDelivery),
        freeDeliveryCampaigns: normalizeCampaigns(settingsResult.freeDeliveryCampaigns)
    });
};
const loadSettings = async () => {
    loading.value = true;
    try {
        applySettings(await getSettings());
    }
    catch (error) {
        ElMessage.error(error instanceof Error ? error.message : '门店设置加载失败');
    }
    finally {
        loading.value = false;
    }
};
const assetUrl = resolveFreshAssetUrl;
const uploadLogo = async (options: any) => {
    uploadingLogo.value = true;
    try {
        const result = await uploadLogoImage(options.file);
        form.logoUrl = result.url;
        applySettings(await updateSettings({ ...form, freeDeliveryCampaigns: normalizeCampaigns(form.freeDeliveryCampaigns) }));
        options.onSuccess?.(result);
        ElMessage.success('Logo 已上传并保存');
    }
    catch (error) {
        options.onError?.(error);
        ElMessage.error(error instanceof Error ? error.message : 'Logo 上传失败');
    }
    finally {
        uploadingLogo.value = false;
    }
};
const validateSettings = () => {
    if (!form.storeName)
        return '请填写门店名称';
    if (!form.businessHours)
        return '请填写营业时间';
    if (!form.contactPhone)
        return '请填写联系电话';
    const invalidCampaign = form.freeDeliveryCampaigns.find((item) => !item.reason || !item.startDate || !item.endDate);
    if (invalidCampaign)
        return '请补全免配送活动的原因和时间';
    return '';
};
const addCampaign = () => {
    if (!newCampaign.reason || newCampaign.range.length !== 2) {
        ElMessage.warning('请填写活动原因并选择日期范围');
        return;
    }
    form.freeDeliveryCampaigns.push({
        reason: newCampaign.reason,
        startDate: newCampaign.range[0],
        endDate: newCampaign.range[1],
        enabled: true
    });
    newCampaign.reason = '';
    newCampaign.range = [];
};
const toggleCampaign = (row: FreeDeliveryCampaign) => {
    row.enabled = !row.enabled;
};
const removeCampaign = (index: number) => {
    form.freeDeliveryCampaigns.splice(index, 1);
};
const saveSettings = async () => {
    const message = validateSettings();
    if (message) {
        ElMessage.warning(message);
        return;
    }
    saving.value = true;
    try {
        applySettings(await updateSettings({ ...form, freeDeliveryCampaigns: normalizeCampaigns(form.freeDeliveryCampaigns) }));
        ElMessage.success('门店设置已保存');
    }
    catch (error) {
        ElMessage.error(error instanceof Error ? error.message : '门店设置保存失败');
    }
    finally {
        saving.value = false;
    }
};
const handleSeedDemoData = async () => {
    seeding.value = true;
    try {
        await seedDemoData();
        ElMessage.success('示例数据已补齐');
        await loadSettings();
    }
    catch (error) {
        ElMessage.error(error instanceof Error ? error.message : '示例数据补齐失败');
    }
    finally {
        seeding.value = false;
    }
};
onMounted(loadSettings);
</script>

<style scoped lang="scss">

  @use '../style.scss';

  .settings-actions {
    display: flex;
    gap: 12px;
  }

  .settings-form {
    max-width: 860px;
  }

  .logo-upload-row {
    display: flex;
    align-items: center;
    gap: 16px;
  }

  .logo-preview {
    width: 64px;
    height: 64px;
    flex-shrink: 0;
    overflow: hidden;
    border: 1px solid var(--art-border-color);
    border-radius: 16px;
    background: #f4f8f5;
  }

  .logo-preview__fallback {
    display: flex;
    align-items: center;
    justify-content: center;
    width: 100%;
    height: 100%;
    color: var(--art-text-gray-600);
    font-size: 12px;
    background: var(--art-bg-color);
  }

  .logo-upload-main {
    display: flex;
    flex-direction: column;
    gap: 8px;

    p {
      margin: 0;
      color: var(--art-text-gray-600);
      font-size: 12px;
      line-height: 18px;
    }
  }

  .card-header {
    display: flex;
    flex-direction: column;
    gap: 4px;

    span {
      color: var(--art-text-gray-600);
      font-size: 13px;
      font-weight: 400;
    }
  }

  .form-tip {
    margin-left: 12px;
    color: var(--art-gray-600);
    font-size: 13px;
  }

  .campaign-editor {
    display: flex;
    flex-direction: column;
    gap: 14px;
    width: 100%;
  }

  .campaign-create {
    display: grid;
    grid-template-columns: minmax(180px, 1fr) 360px 104px;
    gap: 10px;
    align-items: center;
    width: 100%;
    max-width: 710px;
  }

  .campaign-range {
    width: 100%;
  }

  .campaign-add {
    width: 104px;
  }

  @media (max-width: 960px) {
    .campaign-create {
      grid-template-columns: 1fr;
      max-width: none;
    }

    .campaign-add {
      width: 100%;
    }
  }
</style>
