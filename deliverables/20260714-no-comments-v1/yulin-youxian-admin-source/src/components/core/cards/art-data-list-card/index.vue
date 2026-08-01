
<template>
  <div class="art-card p-5">
    <div class="pb-3.5">
      <p class="text-lg font-medium">{{ title }}</p>
      <p class="text-sm text-g-600">{{ subtitle }}</p>
    </div>
    <ElScrollbar :style="{ height: maxHeight }">
      <div v-for="(item, index) in list" :key="index" class="flex-c py-3">
        <div v-if="item.icon" class="flex-cc mr-3 size-10 rounded-lg" :class="item.class">
          <ArtSvgIcon :icon="item.icon" class="text-xl" />
        </div>
        <div class="flex-1">
          <div class="mb-1 text-sm">{{ item.title }}</div>
          <div class="text-xs text-g-500">{{ item.status }}</div>
        </div>
        <div class="ml-3 text-xs text-g-500">{{ item.time }}</div>
      </div>
    </ElScrollbar>
    <ElButton
      class="mt-[25px] w-full text-center"
      v-if="showMoreButton"
      v-ripple
      @click="handleMore"
      >查看更多</ElButton
    >
  </div>
</template>

<script setup lang="ts">
defineOptions({ name: 'ArtDataListCard' });
interface Props {
    list: Activity[];
    title: string;
    subtitle?: string;
    maxCount?: number;
    showMoreButton?: boolean;
}
interface Activity {
    title: string;
    status: string;
    time: string;
    class: string;
    icon: string;
}
const ITEM_HEIGHT = 66;
const DEFAULT_MAX_COUNT = 5;
const props = withDefaults(defineProps<Props>(), {
    maxCount: DEFAULT_MAX_COUNT
});
const maxHeight = computed(() => `${ITEM_HEIGHT * props.maxCount}px`);
const emit = defineEmits<{
    (e: 'more'): void;
}>();
const handleMore = () => emit('more');
</script>
