<template>
  <div class="track-replay">
    <div class="track-replay__bar">
      <ElButton
        size="small"
        type="primary"
        :disabled="points.length < 2"
        @click="playing ? pause() : play()"
      >
        {{ playing ? '暂停' : '播放' }}
      </ElButton>
      <ElButton size="small" :disabled="points.length === 0" @click="reset">回到起点</ElButton>
      <ElSelect v-model="speed" size="small" class="track-replay__speed">
        <ElOption v-for="item in SPEEDS" :key="item" :label="`${item}x`" :value="item" />
      </ElSelect>
      <span class="track-replay__time">
        {{ currentPoint ? fullTimeText(currentPoint.locatedAt) : '暂无轨迹' }}
      </span>
      <span class="track-replay__count"
        >{{ points.length > 0 ? index + 1 : 0 }}/{{ points.length }}</span
      >
    </div>

    <ElSlider
      v-model="index"
      :min="0"
      :max="Math.max(points.length - 1, 0)"
      :disabled="points.length === 0"
      :show-tooltip="false"
      @input="pause"
    />

    <div v-if="currentPoint" class="track-replay__meta">
      <span>坐标 {{ currentPoint.lat.toFixed(5) }}, {{ currentPoint.lng.toFixed(5) }}</span>
      <span v-if="currentPoint.speedMps !== null && currentPoint.speedMps !== undefined">
        速度 {{ (currentPoint.speedMps * 3.6).toFixed(1) }} km/h
      </span>
      <span v-if="currentPoint.motionState">
        状态 {{ motionStateText(currentPoint.motionState) }}
      </span>
    </div>
  </div>
</template>

<script setup lang="ts">
  import type { TrackPoint } from '@/api/delivery'
  import { fullTimeText, motionStateText } from '../utils'

  defineOptions({ name: 'TrackReplay' })

  const props = defineProps<{
    points: TrackPoint[]
  }>()

  const emit = defineEmits<{
    (event: 'cursor', point: TrackPoint | null): void
  }>()

  const SPEEDS = [1, 2, 4, 8]
  const BASE_INTERVAL = 400

  const index = ref(0)
  const speed = ref(2)
  const playing = ref(false)
  let timer: number | null = null

  const currentPoint = computed<TrackPoint | null>(() => props.points[index.value] || null)

  const clearTimer = () => {
    if (timer !== null) {
      window.clearInterval(timer)
      timer = null
    }
  }

  const pause = () => {
    playing.value = false
    clearTimer()
  }

  const play = () => {
    if (props.points.length < 2) return
    if (index.value >= props.points.length - 1) index.value = 0
    playing.value = true
    clearTimer()
    timer = window.setInterval(() => {
      if (index.value >= props.points.length - 1) {
        pause()
        return
      }
      index.value += 1
    }, BASE_INTERVAL / speed.value)
  }

  const reset = () => {
    pause()
    index.value = 0
  }

  watch(speed, () => {
    if (playing.value) play()
  })

  watch(
    () => props.points,
    () => {
      pause()
      index.value = 0
    }
  )

  watch(currentPoint, (point) => emit('cursor', point), { immediate: true })

  onDeactivated(pause)
  onUnmounted(pause)
</script>

<style scoped lang="scss">
  .track-replay {
    display: grid;
    gap: 6px;
  }

  .track-replay__bar {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 10px;
  }

  .track-replay__speed {
    width: 90px;
  }

  .track-replay__time {
    color: var(--art-gray-800);
    font-size: 13px;
    font-variant-numeric: tabular-nums;
  }

  .track-replay__count {
    margin-left: auto;
    color: var(--art-gray-600);
    font-size: 12px;
  }

  .track-replay__meta {
    display: flex;
    flex-wrap: wrap;
    gap: 4px 14px;
    color: var(--art-gray-600);
    font-size: 12px;
  }
</style>
