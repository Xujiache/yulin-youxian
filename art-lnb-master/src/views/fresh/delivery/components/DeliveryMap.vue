<template>
  <div class="delivery-map" :style="{ height }">
    <div v-if="!amapKey" class="delivery-map__placeholder">
      <ElEmpty description="未配置地图 Key">
        <p class="delivery-map__hint">
          在「调度参数 → 地图」分类里填写高德 Key
          后即可显示地图。队列筛选与派单不依赖地图，可正常使用。
        </p>
        <ElButton type="primary" plain @click="goSettings">去配置地图 Key</ElButton>
      </ElEmpty>
    </div>
    <div v-else-if="failure" class="delivery-map__placeholder">
      <ElEmpty :description="failure">
        <ElButton plain @click="setup">重新加载地图</ElButton>
      </ElEmpty>
    </div>
    <div v-else v-loading="loading" class="delivery-map__canvas">
      <div ref="containerRef" class="delivery-map__inner"></div>
    </div>
  </div>
</template>

<script setup lang="ts">
  import type { GeoPoint, MapRiderPoint, MapTaskPoint } from '@/api/delivery'
  import {
    loadAmap,
    type AMapInstance,
    type AMapNamespace,
    type AMapOverlay
  } from '../composables/useAmap'

  defineOptions({ name: 'DeliveryMap' })

  interface StopMarker {
    seqNo: number
    location: GeoPoint
    label?: string
  }

  const props = withDefaults(
    defineProps<{
      amapKey?: string
      securityCode?: string
      riders?: MapRiderPoint[]
      tasks?: MapTaskPoint[]
      /** 规划路线 */
      routePath?: GeoPoint[]
      /** 实际轨迹 */
      trackPath?: GeoPoint[]
      /** 回放游标位置 */
      cursor?: GeoPoint | null
      stops?: StopMarker[]
      origin?: GeoPoint | null
      /** 高亮的骑手/任务 */
      activeRiderId?: number | null
      activeTaskId?: number | null
      height?: string
    }>(),
    {
      amapKey: '',
      securityCode: '',
      riders: () => [],
      tasks: () => [],
      routePath: () => [],
      trackPath: () => [],
      cursor: null,
      stops: () => [],
      origin: null,
      activeRiderId: null,
      activeTaskId: null,
      height: '100%'
    }
  )

  const emit = defineEmits<{
    (event: 'select', payload: { type: 'rider' | 'task' | 'stop'; id: number }): void
  }>()

  const router = useRouter()
  const containerRef = ref<HTMLElement>()
  const loading = ref(false)
  const failure = ref('')

  let amap: AMapNamespace | null = null
  let map: AMapInstance | null = null
  let fitted = false

  const TASK_COLORS: Record<string, string> = {
    PENDING: '#F56C6C',
    ASSIGNED: '#E6A23C',
    ACCEPTED: '#E6A23C',
    PICKED_UP: '#409EFF',
    DELIVERING: '#409EFF',
    ARRIVED: '#00843D',
    DELIVERED: '#909399',
    RETURNED: '#909399',
    CANCELLED: '#C0C4CC'
  }

  const pinHtml = (background: string, text: string, active: boolean) =>
    `<div style="display:flex;align-items:center;justify-content:center;min-width:24px;height:24px;padding:0 6px;` +
    `border-radius:12px;background:${background};color:#fff;font-size:12px;font-weight:700;white-space:nowrap;` +
    `box-shadow:0 2px 6px rgba(0,0,0,.28);border:2px solid ${active ? '#fff' : 'transparent'};` +
    `transform:${active ? 'scale(1.15)' : 'none'}">${text}</div>`

  const goSettings = () => {
    router.push('/fresh/delivery/settings')
  }

  const drawPolyline = (path: GeoPoint[], color: string, dashed: boolean) => {
    if (!amap || !map || path.length < 2) return
    const line = new amap.Polyline({
      path: path.map((point) => [point.lng, point.lat]),
      strokeColor: color,
      strokeWeight: 5,
      strokeOpacity: 0.85,
      strokeStyle: dashed ? 'dashed' : 'solid',
      lineJoin: 'round'
    })
    map.add(line)
  }

  const addMarker = (
    point: GeoPoint,
    content: string,
    payload?: { type: 'rider' | 'task' | 'stop'; id: number }
  ) => {
    if (!amap || !map) return
    const marker = new amap.Marker({
      position: [point.lng, point.lat],
      content,
      offset: new amap.Pixel(-14, -14),
      zIndex: payload?.type === 'rider' ? 120 : 100
    })
    if (payload) {
      marker.on('click', () => emit('select', payload))
    }
    map.add(marker as AMapOverlay)
  }

  const renderOverlays = () => {
    if (!amap || !map) return
    map.clearMap()

    drawPolyline(props.routePath, '#00843D', false)
    drawPolyline(props.trackPath, '#409EFF', true)

    if (props.origin) {
      addMarker(props.origin, pinHtml('#00843D', '门店', false))
    }

    props.stops.forEach((stop) => {
      addMarker(stop.location, pinHtml('#7B61FF', stop.label || String(stop.seqNo), false), {
        type: 'stop',
        id: stop.seqNo
      })
    })

    props.tasks.forEach((task) => {
      addMarker(
        { lat: task.lat, lng: task.lng },
        pinHtml(TASK_COLORS[task.status] || '#909399', '单', props.activeTaskId === task.taskId),
        { type: 'task', id: task.taskId }
      )
    })

    props.riders.forEach((rider) => {
      addMarker(
        { lat: rider.lat, lng: rider.lng },
        pinHtml('#1D6FF2', '骑', props.activeRiderId === rider.riderId),
        { type: 'rider', id: rider.riderId }
      )
    })

    if (props.cursor) {
      addMarker(props.cursor, pinHtml('#F56C6C', '当前', true))
    }

    if (!fitted) {
      const hasPoint =
        props.riders.length > 0 ||
        props.tasks.length > 0 ||
        props.stops.length > 0 ||
        props.routePath.length > 0
      if (hasPoint) {
        map.setFitView(null, false, [40, 40, 40, 40], 16)
        fitted = true
      }
    }
  }

  const setup = async () => {
    if (!props.amapKey || map) return
    loading.value = true
    failure.value = ''
    try {
      amap = await loadAmap(props.amapKey, props.securityCode)
      await nextTick()
      if (!containerRef.value) return
      map = new amap.Map(containerRef.value, {
        zoom: 13,
        resizeEnable: true,
        viewMode: '2D'
      })
      fitted = false
      renderOverlays()
    } catch (error) {
      failure.value = error instanceof Error ? error.message : '地图加载失败'
    } finally {
      loading.value = false
    }
  }

  const destroy = () => {
    map?.destroy()
    map = null
    amap = null
    fitted = false
  }

  /** 数据源变化时重绘（父组件轮询 /map 时每 5 秒触发一次） */
  watch(
    () => [props.riders, props.tasks, props.routePath, props.trackPath, props.stops, props.cursor],
    () => renderOverlays(),
    { deep: true }
  )

  watch(
    () => [props.activeRiderId, props.activeTaskId],
    () => renderOverlays()
  )

  watch(
    () => props.amapKey,
    (key) => {
      if (key && !map) void setup()
    }
  )

  /** 面板从折叠恢复时容器尺寸变了，需要 resize */
  const resize = () => {
    map?.resize?.()
  }

  onMounted(() => {
    if (props.amapKey) void setup()
  })

  onUnmounted(destroy)

  defineExpose({ resize })
</script>

<style scoped lang="scss">
  .delivery-map {
    position: relative;
    min-height: 260px;
    overflow: hidden;
    border: 1px solid var(--art-border-color);
    border-radius: 10px;
    background: var(--el-fill-color-lighter);
  }

  .delivery-map__placeholder {
    display: flex;
    align-items: center;
    justify-content: center;
    height: 100%;
    padding: 16px;
    text-align: center;
  }

  .delivery-map__hint {
    max-width: 280px;
    margin: 0 auto 12px;
    color: var(--art-gray-600);
    font-size: 12px;
    line-height: 18px;
  }

  .delivery-map__canvas,
  .delivery-map__inner {
    width: 100%;
    height: 100%;
  }
</style>
