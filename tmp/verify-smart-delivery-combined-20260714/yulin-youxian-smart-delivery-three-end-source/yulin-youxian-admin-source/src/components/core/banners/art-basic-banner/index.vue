
<template>
  <div
    class="art-card basic-banner"
    :class="[{ 'has-decoration': decoration }, boxStyle]"
    :style="{ height }"
    @click="emit('click')"
  >
    
    <div v-if="meteorConfig?.enabled && isDark" class="basic-banner__meteors">
      <span
        v-for="(meteor, index) in meteors"
        :key="index"
        class="meteor"
        :style="{
          top: '-60px',
          left: `${meteor.x}%`,
          animationDuration: `${meteor.speed}s`,
          animationDelay: `${meteor.delay}s`
        }"
      ></span>
    </div>

    <div class="basic-banner__content">
      
      <slot name="title">
        <p v-if="title" class="basic-banner__title" :style="{ color: titleColor }">{{ title }}</p>
      </slot>

      
      <slot name="subtitle">
        <p v-if="subtitle" class="basic-banner__subtitle" :style="{ color: subtitleColor }">{{
          subtitle
        }}</p>
      </slot>

      
      <slot name="button">
        <div
          v-if="buttonConfig?.show"
          class="basic-banner__button"
          :style="{
            backgroundColor: buttonColor,
            color: buttonTextColor,
            borderRadius: buttonRadius
          }"
          @click.stop="emit('buttonClick')"
        >
          {{ buttonConfig?.text }}
        </div>
      </slot>

      
      <slot></slot>

      
      <img
        v-if="imageConfig.src"
        class="basic-banner__background-image"
        :src="imageConfig.src"
        :style="{ width: imageConfig.width, bottom: imageConfig.bottom, right: imageConfig.right }"
        loading="lazy"
        alt="背景图片"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref, computed } from 'vue';
import { useSettingStore } from '@/store/modules/setting';
const settingStore = useSettingStore();
const { isDark } = storeToRefs(settingStore);
defineOptions({ name: 'ArtBasicBanner' });
interface Meteor {
    x: number;
    speed: number;
    delay: number;
}
interface ButtonConfig {
    show: boolean;
    text: string;
    color?: string;
    textColor?: string;
    radius?: string;
}
interface MeteorConfig {
    enabled: boolean;
    count?: number;
}
interface ImageConfig {
    src: string;
    width?: string;
    bottom?: string;
    right?: string;
}
interface Props {
    height?: string;
    title?: string;
    subtitle?: string;
    boxStyle?: string;
    decoration?: boolean;
    buttonConfig?: ButtonConfig;
    meteorConfig?: MeteorConfig;
    imageConfig?: ImageConfig;
    titleColor?: string;
    subtitleColor?: string;
}
const props = withDefaults(defineProps<Props>(), {
    height: '11rem',
    titleColor: 'white',
    subtitleColor: 'white',
    boxStyle: '!bg-theme/60',
    decoration: true,
    buttonConfig: () => ({
        show: true,
        text: '查看',
        color: '#fff',
        textColor: '#333',
        radius: '6px'
    }),
    meteorConfig: () => ({ enabled: false, count: 10 }),
    imageConfig: () => ({ src: '', width: '12rem', bottom: '-3rem', right: '0' })
});
const emit = defineEmits<{
    (e: 'click'): void;
    (e: 'buttonClick'): void;
}>();
const buttonColor = computed(() => props.buttonConfig?.color ?? '#fff');
const buttonTextColor = computed(() => props.buttonConfig?.textColor ?? '#333');
const buttonRadius = computed(() => props.buttonConfig?.radius ?? '6px');
const meteors = ref<Meteor[]>([]);
onMounted(() => {
    if (props.meteorConfig?.enabled) {
        meteors.value = generateMeteors(props.meteorConfig?.count ?? 10);
    }
});
function generateMeteors(count: number): Meteor[] {
    const segmentWidth = 100 / count;
    return Array.from({ length: count }, (_, index) => {
        const segmentStart = index * segmentWidth;
        const x = segmentStart + Math.random() * segmentWidth;
        const isSlow = Math.random() > 0.5;
        return {
            x,
            speed: isSlow ? 5 + Math.random() * 3 : 2 + Math.random() * 2,
            delay: Math.random() * 5
        };
    });
}
</script>

<style lang="scss" scoped>

  .basic-banner {
    position: relative;
    display: flex;
    flex-direction: column;
    justify-content: center;
    padding: 0 2rem;
    overflow: hidden;
    color: white;
    border-radius: calc(var(--custom-radius) + 2px) !important;

    &__content {
      position: relative;
      z-index: 1;
    }

    &__title {
      margin: 0 0 0.5rem;
      font-size: 1.5rem;
      font-weight: 600;
    }

    &__subtitle {
      position: relative;
      z-index: 10;
      margin: 0 0 1.5rem;
      font-size: 0.9rem;
      opacity: 0.9;
    }

    &__button {
      box-sizing: border-box;
      display: inline-block;
      min-width: 80px;
      height: var(--el-component-custom-height);
      padding: 0 12px;
      font-size: 14px;
      line-height: var(--el-component-custom-height);
      text-align: center;
      cursor: pointer;
      user-select: none;
      transition: all 0.3s;

      &:hover {
        opacity: 0.8;
      }
    }

    &__background-image {
      position: absolute;
      right: 0;
      bottom: -3rem;
      z-index: 0;
      width: 12rem;
    }

    &.has-decoration::after {
      position: absolute;
      right: -10%;
      bottom: -20%;
      width: 60%;
      height: 140%;
      content: '';
      background: rgb(255 255 255 / 10%);
      border-radius: 30%;
      transform: rotate(-20deg);
    }

    &__meteors {
      position: absolute;
      top: 0;
      left: 0;
      z-index: 0;
      width: 100%;
      height: 100%;
      pointer-events: none;

      .meteor {
        position: absolute;
        width: 2px;
        height: 60px;
        background: linear-gradient(
          to top,
          rgb(255 255 255 / 40%),
          rgb(255 255 255 / 10%),
          transparent
        );
        opacity: 0;
        transform-origin: top left;
        animation-name: meteor-fall;
        animation-timing-function: linear;
        animation-iteration-count: infinite;

        &::before {
          position: absolute;
          right: 0;
          bottom: 0;
          width: 2px;
          height: 2px;
          content: '';
          background: rgb(255 255 255 / 50%);
        }
      }
    }
  }

  @keyframes meteor-fall {
    0% {
      opacity: 1;
      transform: translate(0, -60px) rotate(-45deg);
    }

    100% {
      opacity: 0;
      transform: translate(400px, 340px) rotate(-45deg);
    }
  }

  @media (width <= 640px) {
    .basic-banner {
      box-sizing: border-box;
      justify-content: flex-start;
      padding: 16px;

      &__title {
        font-size: 1.4rem;
      }

      &__background-image {
        display: none;
      }

      &.has-decoration::after {
        display: none;
      }
    }
  }
</style>
