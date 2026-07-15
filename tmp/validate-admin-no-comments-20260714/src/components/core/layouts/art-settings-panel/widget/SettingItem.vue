<template>
  <div class="flex-cb mb-4 last:mb-2" :class="{ 'mobile-hide': config.mobileHide }">
    <span class="text-sm">{{ config.label }}</span>

    
    <ElSwitch v-if="config.type === 'switch'" :model-value="modelValue" @change="handleChange" />

    
    <ElInputNumber
      v-else-if="config.type === 'input-number'"
      :model-value="modelValue"
      :min="config.min"
      :max="config.max"
      :step="config.step"
      :style="config.style"
      :controls-position="config.controlsPosition"
      @change="handleChange"
    />

    
    <ElSelect
      v-else-if="config.type === 'select'"
      :model-value="modelValue"
      :style="config.style"
      @change="handleChange"
    >
      <ElOption
        v-for="option in normalizedOptions"
        :key="option.value"
        :label="option.label"
        :value="option.value"
      />
    </ElSelect>
  </div>
</template>

<script setup lang="ts">
import type { ComputedRef } from 'vue';
interface SettingItemConfig {
    key: string;
    label: string;
    type: 'switch' | 'input-number' | 'select';
    handler: string;
    mobileHide?: boolean;
    min?: number;
    max?: number;
    step?: number;
    style?: Record<string, string>;
    controlsPosition?: '' | 'right';
    options?: Array<{
        value: any;
        label: string;
    }> | ComputedRef<Array<{
        value: any;
        label: string;
    }>>;
}
interface Props {
    config: SettingItemConfig;
    modelValue: any;
}
interface Emits {
    (e: 'change', value: any): void;
}
const props = defineProps<Props>();
const emit = defineEmits<Emits>();
const normalizedOptions = computed(() => {
    if (!props.config.options)
        return [];
    try {
        if (typeof props.config.options === 'object' && 'value' in props.config.options) {
            return props.config.options.value || [];
        }
        return Array.isArray(props.config.options) ? props.config.options : [];
    }
    catch (error) {
        console.warn('Error processing options for config:', props.config.key, error);
        return [];
    }
});
const handleChange = (value: any) => {
    try {
        emit('change', value);
    }
    catch (error) {
        console.error('Error handling change for config:', props.config.key, error);
    }
};
</script>

<style lang="scss" scoped>

  @media screen and (width <= 768px) {
    .mobile-hide {
      display: none !important;
    }
  }
</style>
