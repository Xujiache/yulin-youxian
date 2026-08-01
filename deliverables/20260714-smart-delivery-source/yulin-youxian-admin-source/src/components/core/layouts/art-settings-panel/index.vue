
<template>
  <div class="layout-settings">
    <SettingDrawer v-model="showDrawer" @open="handleOpen" @close="handleClose">
      
      <SettingHeader @close="closeDrawer" />
      
      <ThemeSettings />
      
      <MenuLayoutSettings />
      
      <MenuStyleSettings />
      
      <ColorSettings />
      
      <BoxStyleSettings />
      
      <ContainerSettings />
      
      <BasicSettings />
      
      <SettingActions />
    </SettingDrawer>
  </div>
</template>

<script setup lang="ts">
import { useSettingsPanel } from './composables/useSettingsPanel';
import SettingDrawer from './widget/SettingDrawer.vue';
import SettingHeader from './widget/SettingHeader.vue';
import ThemeSettings from './widget/ThemeSettings.vue';
import MenuLayoutSettings from './widget/MenuLayoutSettings.vue';
import MenuStyleSettings from './widget/MenuStyleSettings.vue';
import ColorSettings from './widget/ColorSettings.vue';
import BoxStyleSettings from './widget/BoxStyleSettings.vue';
import ContainerSettings from './widget/ContainerSettings.vue';
import BasicSettings from './widget/BasicSettings.vue';
import SettingActions from './widget/SettingActions.vue';
defineOptions({ name: 'ArtSettingsPanel' });
interface Props {
    open?: boolean;
}
const props = defineProps<Props>();
const settingsPanel = useSettingsPanel();
const { showDrawer } = settingsPanel;
const { handleOpen, handleClose, closeDrawer } = settingsPanel.useDrawerControl();
const { initializeSettings, cleanupSettings } = settingsPanel.useSettingsInitializer();
settingsPanel.usePropsWatcher(props);
onMounted(() => {
    initializeSettings();
});
onUnmounted(() => {
    cleanupSettings();
});
</script>

<style lang="scss">

  @use './style';
</style>
