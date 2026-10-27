import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import { fileURLToPath } from 'node:url'
import axios from 'axios'
import { loadEnv } from 'vite'

const root = fileURLToPath(new URL('../', import.meta.url))
const { VITE_API_URL } = loadEnv('production', root, 'VITE_API_URL')
const authSource = readFileSync(new URL('../src/api/auth.ts', import.meta.url), 'utf8')
const authPaths = [...authSource.matchAll(/url: '(\/api\/admin\/auth\/[^']+)'/g)].map(
  (match) => match[1]
)

test('production API uses the existing same-origin Nginx proxy', () => {
  assert.equal(VITE_API_URL, '')
  const httpSource = readFileSync(new URL('../src/utils/http/index.ts', import.meta.url), 'utf8')
  assert.match(httpSource, /baseURL:\s*VITE_API_URL/)
  assert.deepEqual(authPaths, [
    '/api/admin/auth/login',
    '/api/admin/auth/profile',
    '/api/admin/auth/logout'
  ])
})

for (const host of ['hqhjxt.vip', 'admin.hqhjxt.vip', 'www.hqhjxt.vip']) {
  test(`login, profile and logout remain same-origin on ${host}`, () => {
    const origin = `https://${host}`
    const client = axios.create({ baseURL: VITE_API_URL })
    for (const pathname of authPaths) {
      const actual = new URL(client.getUri({ url: pathname }), origin)
      assert.equal(actual.origin, origin)
      assert.equal(actual.pathname, pathname)
      assert.equal(actual.search, '')
    }
  })

  test(`native fetch and editor upload URLs stay same-origin on ${host}`, () => {
    const origin = `https://${host}`
    // These existing consumers concatenate the prefix instead of using Axios URL joining.
    for (const pathname of [
      '/api/admin/delivery/board/stream',
      '/api/common/upload/wangeditor',
      '/api/common/upload'
    ]) {
      const actual = new URL(`${VITE_API_URL}${pathname}`, origin)
      assert.equal(actual.origin, origin)
      assert.equal(actual.pathname, pathname)
    }
  })
}
