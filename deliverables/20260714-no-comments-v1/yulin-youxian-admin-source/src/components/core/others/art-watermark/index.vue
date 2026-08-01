
<template>
  <div
    v-if="watermarkVisible"
    class="fixed left-0 top-0 h-screen w-screen pointer-events-none"
    :style="{ zIndex: zIndex }"
  >
    <ElWatermark
      :content="content"
      :font="{ fontSize: fontSize, color: fontColor }"
      :rotate="rotate"
      :gap="[gapX, gapY]"
      :offset="[offsetX, offsetY]"
    >
      <div style="height: 100vh"></div>
    </ElWatermark>
  </div>
</template>

<script setup lang="ts">
import AppConfig from '@/config';
import { useSettingStore } from '@/store/modules/setting';
defineOptions({ name: 'ArtWatermark' });
const settingStore = useSettingStore();
const { watermarkVisible } = storeToRefs(settingStore);
interface WatermarkProps {
    content?: string;
    visible?: boolean;
    fontSize?: number;
    fontColor?: string;
    rotate?: number;
    gapX?: number;
    gapY?: number;
    offsetX?: number;
    offsetY?: number;
    zIndex?: number;
}
withDefaults(defineProps<WatermarkProps>(), {
    content: AppConfig.systemInfo.name,
    visible: false,
    fontSize: 16,
    fontColor: 'rgba(128, 128, 128, 0.2)',
    rotate: -22,
    gapX: 100,
    gapY: 100,
    offsetX: 50,
    offsetY: 50,
    zIndex: 3100
});
</script>
