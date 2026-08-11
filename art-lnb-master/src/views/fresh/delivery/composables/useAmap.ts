/**
 * 高德 JS API 2.0 加载与实例管理
 *
 * Key 由后端 /configs 的 amap 分类下发，不硬编码、不写进仓库。
 * 项目未引入 @amap/amap-jsapi-loader，这里用一次性 script 注入完成加载，
 * 同一 Key 只注入一次，后续调用复用同一个 Promise。
 */
import type { DeliveryConfigItem } from '@/api/delivery'

export interface AMapOverlay {
  setMap?(map: AMapInstance | null): void
}

export interface AMapMarker extends AMapOverlay {
  setPosition(position: [number, number]): void
  setContent(content: string): void
  setAngle?(angle: number): void
  on(event: string, handler: (event: unknown) => void): void
}

export interface AMapInstance {
  add(overlay: AMapOverlay | AMapOverlay[]): void
  remove(overlay: AMapOverlay | AMapOverlay[]): void
  clearMap(): void
  destroy(): void
  setFitView(
    overlays?: AMapOverlay[] | null,
    immediately?: boolean,
    avoid?: number[],
    maxZoom?: number
  ): void
  setCenter(position: [number, number]): void
  setZoom(zoom: number): void
  resize?(): void
}

export interface AMapNamespace {
  Map: new (container: HTMLElement | string, options?: Record<string, unknown>) => AMapInstance
  Marker: new (options?: Record<string, unknown>) => AMapMarker
  Polyline: new (options?: Record<string, unknown>) => AMapOverlay
  Pixel: new (x: number, y: number) => unknown
}

interface AMapWindow {
  AMap?: AMapNamespace
  _AMapSecurityConfig?: { securityJsCode: string }
}

const amapWindow = () => window as unknown as AMapWindow

const globalAmap = () => amapWindow().AMap

let loadPromise: Promise<AMapNamespace> | null = null
let loadedKey = ''

/** 只使用浏览器端 JS Key；服务端 web_key 绝不能回退为前端地图 Key。 */
export function pickAmapConfig(items: DeliveryConfigItem[]) {
  const amapItems = items.filter(
    (item) => String(item.category || '').toUpperCase() === 'AMAP' || /^amap\./i.test(item.key)
  )
  const valueOf = (key: string) =>
    amapItems.find((item) => item.key.toLowerCase() === key && String(item.value || '').trim())
      ?.value || ''
  return {
    key: valueOf('amap.js_key'),
    securityCode: valueOf('amap.security_js_code') || valueOf('amap.security_code')
  }
}

export function loadAmap(key: string, securityCode?: string): Promise<AMapNamespace> {
  const existing = globalAmap()
  if (existing) return Promise.resolve(existing)
  if (!key) return Promise.reject(new Error('未配置地图 Key'))
  if (loadPromise && loadedKey === key) return loadPromise

  loadedKey = key
  loadPromise = new Promise<AMapNamespace>((resolve, reject) => {
    if (securityCode) {
      amapWindow()._AMapSecurityConfig = { securityJsCode: securityCode }
    }
    const script = document.createElement('script')
    script.src = `https://webapi.amap.com/maps?v=2.0&key=${encodeURIComponent(key)}`
    script.async = true
    script.onload = () => {
      const amap = globalAmap()
      if (amap) resolve(amap)
      else {
        loadPromise = null
        reject(new Error('高德地图脚本已加载但未初始化'))
      }
    }
    script.onerror = () => {
      loadPromise = null
      reject(new Error('高德地图脚本加载失败，请检查网络或地图 Key'))
    }
    document.head.appendChild(script)
  })
  return loadPromise
}
