import request from '@/utils/http'
import type { PageResult } from './admin'

export type RiderAppPolicy = 'OPTIONAL' | 'FORCE'
export type RiderAppStatus = 'DRAFT' | 'PUBLISHED' | 'DISABLED' | 'SUPERSEDED'

export interface RiderAppRelease {
  id: number
  channel: string
  versionName: string
  versionCode: number
  title: string
  notes: string | null
  policy: RiderAppPolicy
  packageName: string
  fileName: string
  fileUrl: string
  fileSize: number
  fileSha256: string
  certSha256: string
  sourceSha: string | null
  status: RiderAppStatus
  publishedBy: string | null
  publishedAt: string | null
  notifyCount: number
  lastNotifiedAt: string | null
  createdAt: string | null
  hasApkFile: boolean
  current: boolean
}

export interface RiderAppCoverage {
  channel: string
  currentVersionCode: number | null
  currentVersionName: string | null
  activeDevices: number
  onLatest: number
  behind: number
  unknownVersion: number
  deviceOwner: number
  profileOwner: number
  standard: number
}

export function getRiderAppReleases(params?: { channel?: string; page?: number; pageSize?: number }) {
  return request.get<PageResult<RiderAppRelease>>({
    url: '/api/admin/rider-app/releases',
    params
  })
}

export function getRiderAppCoverage(params?: { channel?: string }) {
  return request.get<RiderAppCoverage>({
    url: '/api/admin/rider-app/coverage',
    params
  })
}

export function uploadRiderAppRelease(data: FormData, onUploadProgress?: (percent: number) => void) {
  return request.post<RiderAppRelease>({
    url: '/api/admin/rider-app/releases',
    data,
    timeout: 10 * 60 * 1000,
    onUploadProgress: (event) => {
      if (!event.total) return
      onUploadProgress?.(Math.round((event.loaded / event.total) * 100))
    }
  })
}

export function publishRiderAppRelease(id: number) {
  return request.post<RiderAppRelease>({
    url: `/api/admin/rider-app/releases/${id}/publish`,
    timeout: 60 * 1000
  })
}

export function notifyRiderAppRelease(id: number) {
  return request.post<{ sentCount: number; id: number }>({
    url: `/api/admin/rider-app/releases/${id}/notify`
  })
}

export function disableRiderAppRelease(id: number) {
  return request.post<RiderAppRelease>({
    url: `/api/admin/rider-app/releases/${id}/disable`
  })
}

export function activateRiderAppRelease(id: number) {
  return request.post<RiderAppRelease>({
    url: `/api/admin/rider-app/releases/${id}/activate`
  })
}
