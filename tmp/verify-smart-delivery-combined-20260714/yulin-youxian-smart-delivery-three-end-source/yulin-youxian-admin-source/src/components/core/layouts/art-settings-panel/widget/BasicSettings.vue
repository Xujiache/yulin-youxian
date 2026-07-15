<template>
  <div>
    <SectionTitle :title="$t('setting.basics.title')" class="mt-10" />
    <SettingItem
      v-for="config in basicSettingsConfig"
      :key="config.key"
      :config="config"
      :model-value="getSettingValue(config.key)"
      @change="handleSettingChange(config.handler, $event)"
    />
  </div>
</template>

<script setup lang="ts">
import SectionTitle from './SectionTitle.vue';
import SettingItem from './SettingItem.vue';
import { useSettingStore } from '@/store/modules/setting';
import { useSettingsConfig } from '../composables/useSettingsConfig';
import { useSettingsHandlers } from '../composables/useSettingsHandlers';
import { storeToRefs } from 'pinia';
const settingStore = useSettingStore();
const { basicSettingsConfig } = useSettingsConfig();
const { basicHandlers } = useSettingsHandlers();
const { uniqueOpened, showMenuButton, showFastEnter, showRefreshButton, showCrumbs, showWorkTab, showLanguage, showNprogress, colorWeak, watermarkVisible, menuOpenWidth, tabStyle, pageTransition, customRadius } = storeToRefs(settingStore);
const settingValueMap = {
    uniqueOpened,
    showMenuButton,
    showFastEnter,
    showRefreshButton,
    showCrumbs,
    showWorkTab,
    showLanguage,
    showNprogress,
    colorWeak,
    watermarkVisible,
    menuOpenWidth,
    tabStyle,
    pageTransition,
    customRadius
};
const getSettingValue = (key: string) => {
    const settingRef = settingValueMap[key as keyof typeof settingValueMap];
    return settingRef?.value ?? null;
};
const handleSettingChange = (handlerName: string, value: any) => {
    const handler = (basicHandlers as any)[handlerName];
    if (typeof handler === 'function') {
        handler(value);
    }
    else {
        console.warn(`Handler "${handlerName}" not found in basicHandlers`);
    }
};
</script>
