<template>
  <ElImage
    v-bind="$attrs"
    :src="displayUrl"
    :fit="fit"
    :lazy="true"
    :preview-src-list="preview ? [originalUrl] : undefined"
    preview-teleported
    @error="handleError"
  >
    <template #placeholder>
      <div class="fresh-image__placeholder"><ElSkeleton animated><template #template><ElSkeletonItem variant="image" /></template></ElSkeleton></div>
    </template>
    <template #error>
      <button class="fresh-image__error" type="button" title="图片加载失败，点击重试" @click.stop="reload">图片加载失败<br />点击重试</button>
    </template>
  </ElImage>
</template>

<script setup lang="ts">
  import { resolveFreshAssetUrl, resolveFreshThumbnailUrl } from '@/utils/fresh-assets'

  defineOptions({ inheritAttrs: false })
  const props = withDefaults(defineProps<{
    src: string
    thumbnail?: string
    preview?: boolean
    fit?: 'fill' | 'contain' | 'cover' | 'none' | 'scale-down'
  }>(), { preview: true, fit: 'cover' })

  const originalUrl = computed(() => resolveFreshAssetUrl(props.src))
  const preferredUrl = computed(() => props.thumbnail ? resolveFreshAssetUrl(props.thumbnail) : resolveFreshThumbnailUrl(props.src))
  const displayUrl = ref('')
  const attempt = ref(0)
  let retryTimer: ReturnType<typeof setTimeout> | undefined

  const withRetryNonce = (value: string) => `${value}${value.includes('?') ? '&' : '?'}image-retry=${Date.now()}`
  const reset = () => {
    if (retryTimer) clearTimeout(retryTimer)
    attempt.value = 0
    displayUrl.value = preferredUrl.value || originalUrl.value
  }
  watch([preferredUrl, originalUrl], reset, { immediate: true })
  onBeforeUnmount(() => retryTimer && clearTimeout(retryTimer))

  const handleError = () => {
    if (attempt.value >= 2) return
    attempt.value += 1
    const next = attempt.value === 2 ? originalUrl.value : withRetryNonce(preferredUrl.value || originalUrl.value)
    retryTimer = setTimeout(() => { displayUrl.value = next }, attempt.value * 500)
  }
  const reload = () => reset()
</script>

<style scoped lang="scss">
  .fresh-image__placeholder, .fresh-image__error { width: 100%; height: 100%; min-height: 32px; display: grid; place-items: center; color: var(--el-text-color-secondary); background: var(--el-fill-color-light); font-size: 12px; text-align: center; }
  .fresh-image__placeholder :deep(.el-skeleton), .fresh-image__placeholder :deep(.el-skeleton__image) { width: 100%; height: 100%; }
  .fresh-image__error { border: 0; cursor: pointer; line-height: 1.45; }
</style>
