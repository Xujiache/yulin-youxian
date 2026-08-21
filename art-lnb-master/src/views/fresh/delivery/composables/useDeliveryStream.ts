/**
 * 调度看板实时通道
 *
 * 首屏走 GET /board 全量，之后接 SSE 增量；SSE 断开时自动降级为 5 秒轮询全量，
 * 并按退避策略重连，重连成功后再全量刷新一次对齐状态。
 *
 * 生命周期由调用方掌握：页面 onMounted/onActivated 调 start()，
 * onDeactivated/onUnmounted 调 stop()——stop() 会关闭连接并清掉全部定时器。
 */
import { createBoardEventSource, type BoardStreamHandlers } from '@/api/delivery'
import type { BoardEventSourceController } from '@/api/delivery'

/** 降级轮询间隔（毫秒） */
const POLL_INTERVAL = 5000
/** 重连退避序列（毫秒） */
const RETRY_DELAYS = [3000, 5000, 10000, 20000, 30000]

export interface UseDeliveryStreamOptions extends BoardStreamHandlers {
  /** 断线降级轮询与重连成功后调用，用于拉取 /board 全量 */
  onFullRefresh: () => void | Promise<void>
}

export function useDeliveryStream(options: UseDeliveryStreamOptions) {
  const connected = ref(false)
  /** 已降级为轮询 */
  const degraded = ref(false)
  const lastEventAt = ref<number>(0)

  let controller: BoardEventSourceController | null = null
  let pollTimer: number | null = null
  let retryTimer: number | null = null
  let retryIndex = 0
  let running = false
  /** 每次 start/stop 都递增，防止旧连接的迟到回调污染新连接。 */
  let generation = 0

  const clearPollTimer = () => {
    if (pollTimer !== null) {
      window.clearInterval(pollTimer)
      pollTimer = null
    }
  }

  const clearRetryTimer = () => {
    if (retryTimer !== null) {
      window.clearTimeout(retryTimer)
      retryTimer = null
    }
  }

  const startPolling = () => {
    if (pollTimer !== null || !running) return
    degraded.value = true
    pollTimer = window.setInterval(() => {
      void options.onFullRefresh()
    }, POLL_INTERVAL)
  }

  const touch = () => {
    lastEventAt.value = Date.now()
  }

  const connect = (expectedGeneration = generation) => {
    if (!running || expectedGeneration !== generation || controller) return
    let localController: BoardEventSourceController | null = null
    const isCurrent = () =>
      running && expectedGeneration === generation && controller === localController
    localController = createBoardEventSource({
      onOpen: () => {
        if (!isCurrent()) {
          localController?.close()
          return
        }
        const wasDegraded = degraded.value
        connected.value = true
        degraded.value = false
        retryIndex = 0
        clearPollTimer()
        touch()
        options.onOpen?.()
        // 断线期间可能漏事件，重连后对齐一次全量
        if (wasDegraded) void options.onFullRefresh()
      },
      onHeartbeat: () => {
        if (!isCurrent()) return
        touch()
        options.onHeartbeat?.()
      },
      onTaskChanged: (task) => {
        if (!isCurrent()) return
        touch()
        options.onTaskChanged?.(task)
      },
      onRiderMoved: (rider) => {
        if (!isCurrent()) return
        touch()
        options.onRiderMoved?.(rider)
      },
      onExceptionRaised: (exception) => {
        if (!isCurrent()) return
        touch()
        options.onExceptionRaised?.(exception)
      },
      onSummaryUpdated: (summary) => {
        if (!isCurrent()) return
        touch()
        options.onSummaryUpdated?.(summary)
      },
      onError: (error) => {
        if (!isCurrent()) {
          localController?.close()
          return
        }
        connected.value = false
        localController?.close()
        controller = null
        options.onError?.(error)
        if (!running) return
        startPolling()
        scheduleRetry(expectedGeneration)
      }
    })
    controller = localController
  }

  const scheduleRetry = (expectedGeneration = generation) => {
    if (!running || expectedGeneration !== generation || retryTimer !== null) return
    const delay = RETRY_DELAYS[Math.min(retryIndex, RETRY_DELAYS.length - 1)]
    retryIndex += 1
    retryTimer = window.setTimeout(() => {
      retryTimer = null
      if (running && expectedGeneration === generation) connect(expectedGeneration)
    }, delay)
  }

  const start = () => {
    if (running) return
    running = true
    generation += 1
    retryIndex = 0
    connect(generation)
  }

  const stop = () => {
    running = false
    generation += 1
    connected.value = false
    degraded.value = false
    clearPollTimer()
    clearRetryTimer()
    controller?.close()
    controller = null
  }

  /** 手动重连（右上角提示里的「重连」按钮） */
  const reconnect = () => {
    stop()
    start()
  }

  return { connected, degraded, lastEventAt, start, stop, reconnect }
}
