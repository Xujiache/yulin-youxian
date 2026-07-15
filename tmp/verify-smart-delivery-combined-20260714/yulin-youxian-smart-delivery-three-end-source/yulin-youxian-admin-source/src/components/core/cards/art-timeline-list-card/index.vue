
<template>
  <div class="art-card p-5">
    <div class="pb-3.5">
      <p class="text-lg font-medium">{{ title }}</p>
      <p class="text-sm text-g-600">{{ subtitle }}</p>
    </div>
    <ElScrollbar :style="{ height: maxHeight }">
      <ElTimeline class="!pl-0.5">
        <ElTimelineItem
          v-for="item in list"
          :key="item.time"
          :timestamp="item.time"
          :placement="TIMELINE_PLACEMENT"
          :color="item.status"
          :center="true"
        >
          <div class="flex-c gap-3">
            <div class="flex-c gap-2">
              <span class="text-sm">{{ item.content }}</span>
              <span v-if="item.code" class="text-sm text-theme"> #{{ item.code }} </span>
            </div>
          </div>
        </ElTimelineItem>
      </ElTimeline>
    </ElScrollbar>
  </div>
</template>

<script setup lang="ts">
defineOptions({ name: 'ArtTimelineListCard' });
const ITEM_HEIGHT = 65;
const TIMELINE_PLACEMENT = 'top';
const DEFAULT_MAX_COUNT = 5;
interface TimelineItem {
    time: string;
    status: string;
    content: string;
    code?: string;
}
interface Props {
    list: TimelineItem[];
    title: string;
    subtitle?: string;
    maxCount?: number;
}
const props = withDefaults(defineProps<Props>(), {
    title: '',
    subtitle: '',
    maxCount: DEFAULT_MAX_COUNT
});
const maxHeight = computed(() => `${ITEM_HEIGHT * props.maxCount}px`);
</script>
