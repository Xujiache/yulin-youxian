
<template>
  <div class="art-card-sm flex-c flex-col pb-6" :style="{ height: height }">
    <div class="flex-c flex-col gap-4 text-center">
      <div class="w-45">
        <img :src="image" :alt="title" class="w-full h-full object-contain" />
      </div>
      <div class="box-border px-4">
        <p class="mb-2 text-lg font-semibold text-g-800">{{ title }}</p>
        <p class="m-0 text-sm text-g-600">{{ description }}</p>
      </div>
      <div class="flex-c gap-3">
        <div
          v-if="cancelButton?.show"
          class="inline-block h-9 px-3 text-sm/9 c-p select-none rounded-md border border-g-300"
          :style="{
            backgroundColor: cancelButton?.color,
            color: cancelButton?.textColor
          }"
          @click="handleCancel"
        >
          {{ cancelButton?.text }}
        </div>
        <div
          v-if="button?.show"
          class="inline-block h-9 px-3 text-sm/9 c-p select-none rounded-md"
          :style="{ backgroundColor: button?.color, color: button?.textColor }"
          @click="handleClick"
        >
          {{ button?.text }}
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import defaultIcon from '@imgs/3d/icon1.webp';
defineOptions({ name: 'ArtCardBanner' });
interface CardBannerProps {
    height?: string;
    image?: string;
    title: string;
    description: string;
    button?: {
        show?: boolean;
        text?: string;
        color?: string;
        textColor?: string;
    };
    cancelButton?: {
        show?: boolean;
        text?: string;
        color?: string;
        textColor?: string;
    };
}
withDefaults(defineProps<CardBannerProps>(), {
    height: '24rem',
    image: defaultIcon,
    title: '',
    description: '',
    button: () => ({
        show: true,
        text: '查看详情',
        color: 'var(--theme-color)',
        textColor: '#fff'
    }),
    cancelButton: () => ({
        show: false,
        text: '取消',
        color: '#f5f5f5',
        textColor: '#666'
    })
});
const emit = defineEmits<{
    (e: 'click'): void;
    (e: 'cancel'): void;
}>();
const handleClick = () => {
    emit('click');
};
const handleCancel = () => {
    emit('cancel');
};
</script>
