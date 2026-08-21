export function resolveFreshAssetUrl(url?: string) {
  if (!url) return ''
  if (/^https?:\/\//.test(url) || url.startsWith('//')) return url
  if (!url.startsWith('/assets/') && !url.startsWith('/uploads/')) return url

  const apiUrl = String(import.meta.env.VITE_API_URL || '').trim()
  if (!apiUrl || apiUrl === '/') return url

  return `${apiUrl.replace(/\/+$/, '')}${url}`
}

/** Public uploads keep variants beside the original. Sensitive evidence stays on the signed original URL. */
export function resolveFreshThumbnailUrl(url?: string) {
  if (!url || !url.includes('/uploads/')) return resolveFreshAssetUrl(url)
  const [path, query = ''] = url.split('?', 2)
  const slash = path.lastIndexOf('/')
  const filename = path.slice(slash + 1)
  if (slash < 0 || !/\.(?:jpe?g|png|webp)$/i.test(filename) || path.includes('/thumbnails/')) {
    return resolveFreshAssetUrl(url)
  }
  // Private evidence carries the original ticket through unchanged. The server accepts it
  // only for this deterministic derivative, never for another file.
  return resolveFreshAssetUrl(`${path.slice(0, slash + 1)}thumbnails/${filename}.webp${query ? `?${query}` : ''}`)
}
