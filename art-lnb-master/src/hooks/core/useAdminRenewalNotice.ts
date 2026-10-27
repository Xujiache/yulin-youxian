import { onScopeDispose, ref, watch, type Ref } from 'vue'

const POLL_INTERVAL_MS = 30_000
const REQUEST_TIMEOUT_MS = 5_000

export function useAdminRenewalNotice(active: Ref<boolean>) {
  const visible = ref(false)
  let generation = 0
  let controller: AbortController | undefined
  let pollTimer: ReturnType<typeof setTimeout> | undefined
  let timeoutTimer: ReturnType<typeof setTimeout> | undefined

  function cancelPending() {
    generation += 1
    clearTimeout(pollTimer)
    clearTimeout(timeoutTimer)
    controller?.abort()
    controller = undefined
  }

  async function refresh() {
    if (!active.value) return
    cancelPending()
    const requestGeneration = generation
    const requestController = new AbortController()
    controller = requestController
    timeoutTimer = setTimeout(() => requestController.abort(), REQUEST_TIMEOUT_MS)

    try {
      const response = await fetch('/admin-renewal.json', {
        cache: 'no-store',
        credentials: 'omit',
        signal: requestController.signal
      })
      if (!response.ok) throw new Error('Renewal notice configuration unavailable')
      const config: unknown = await response.json()
      if (
        !config ||
        typeof config !== 'object' ||
        !('enabled' in config) ||
        typeof config.enabled !== 'boolean'
      ) {
        throw new Error('Invalid renewal notice configuration')
      }
      if (requestGeneration === generation && active.value) {
        visible.value = config.enabled
      }
    } catch {
      if (requestGeneration === generation) {
        // A failed refresh must not dismiss an already displayed reminder.
        console.warn('[Admin renewal] Configuration refresh failed; retrying later')
      }
    } finally {
      if (requestGeneration === generation) {
        clearTimeout(timeoutTimer)
        controller = undefined
        if (active.value) pollTimer = setTimeout(() => void refresh(), POLL_INTERVAL_MS)
      }
    }
  }

  const stopWatching = watch(
    active,
    (loggedIn) => {
      if (loggedIn) {
        void refresh()
      } else {
        cancelPending()
        visible.value = false
      }
    },
    { immediate: true, flush: 'sync' }
  )

  function refreshOnReturn() {
    if (typeof document === 'undefined' || document.visibilityState === 'visible') {
      void refresh()
    }
  }

  if (typeof document !== 'undefined') {
    document.addEventListener('visibilitychange', refreshOnReturn)
    window.addEventListener('focus', refreshOnReturn)
  }

  onScopeDispose(() => {
    stopWatching()
    cancelPending()
    if (typeof document !== 'undefined') {
      document.removeEventListener('visibilitychange', refreshOnReturn)
      window.removeEventListener('focus', refreshOnReturn)
    }
  })

  return visible
}
