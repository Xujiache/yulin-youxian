
<template>
  <div
    class="art-card h-32 flex-c px-5 transition-transform duration-200 hover:-translate-y-0.5"
    :class="boxStyle"
  >
    <div v-if="icon" class="mr-4 size-11 flex-cc rounded-lg text-xl text-white" :class="iconStyle">
      <ArtSvgIcon :icon="icon"></ArtSvgIcon>
    </div>
    <div class="flex-1">
      <p class="m-0 text-lg font-medium" :style="{ color: textColor }" v-if="title">
        {{ title }}
      </p>
      <ArtCountTo
        class="m-0 text-2xl font-medium"
        v-if="count !== undefined"
        :target="count"
        :duration="2000"
        :decimals="decimals"
        :separator="separator"
      />
      <p
        class="mt-1 text-sm text-g-500 opacity-90"
        :style="{ color: textColor }"
        v-if="description"
        >{{ description }}</p
      >
    </div>
    <div v-if="showArrow">
      <ArtSvgIcon icon="ri:arrow-right-s-line" class="text-xl text-g-500" />
    </div>
  </div>
</template>

<script setup lang="ts">
defineOptions({ name: 'ArtStatsCard' });
interface StatsCardProps {
    boxStyle?: string;
    icon?: string;
    iconStyle?: string;
    title?: string;
    count?: number;
    decimals?: number;
    separator?: string;
    description: string;
    textColor?: string;
    showArrow?: boolean;
}
withDefaults(defineProps<StatsCardProps>(), {
    iconSize: 30,
    iconBgRadius: 50,
    decimals: 0,
    separator: ','
});
</script>
