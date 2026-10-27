import assert from 'node:assert/strict'
import { execFileSync, spawnSync } from 'node:child_process'
import {
  mkdirSync,
  mkdtempSync,
  readFileSync,
  readdirSync,
  realpathSync,
  rmSync,
  symlinkSync
} from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import test from 'node:test'
import { effectScope, ref } from 'vue'
import { useAdminRenewalNotice } from '../src/hooks/core/useAdminRenewalNotice.ts'

const settle = () => new Promise((resolve) => setImmediate(resolve))
const response = (value) => new Response(JSON.stringify(value))

function mount(t, authenticated = true) {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  t.mock.method(console, 'warn', () => {})
  const active = ref(authenticated)
  const scope = effectScope()
  const visible = scope.run(() => useAdminRenewalNotice(active))
  t.after(() => scope.stop())
  return { active, visible, scope }
}

test('anonymous sessions do not request or display the reminder', async (t) => {
  const fetch = t.mock.method(globalThis, 'fetch', async () => response({ enabled: true }))
  const { visible } = mount(t, false)
  await settle()
  t.mock.timers.tick(60_000)
  assert.equal(fetch.mock.callCount(), 0)
  assert.equal(visible.value, false)
})

test('login, periodic operator disable/enable, and logout update one reminder', async (t) => {
  let enabled = true
  const fetch = t.mock.method(globalThis, 'fetch', async () => response({ enabled }))
  const { active, visible } = mount(t, false)
  active.value = true
  await settle()
  assert.equal(visible.value, true)
  assert.equal(fetch.mock.callCount(), 1)
  assert.equal(fetch.mock.calls[0].arguments[0], '/admin-renewal.json')
  assert.equal(fetch.mock.calls[0].arguments[1].cache, 'no-store')
  assert.equal(fetch.mock.calls[0].arguments[1].credentials, 'omit')
  enabled = false
  t.mock.timers.tick(30_000)
  await settle()
  assert.equal(visible.value, false)
  enabled = true
  t.mock.timers.tick(30_000)
  await settle()
  assert.equal(visible.value, true)
  active.value = false
  assert.equal(visible.value, false)
  const count = fetch.mock.callCount()
  t.mock.timers.tick(60_000)
  assert.equal(fetch.mock.callCount(), count)
})

test('refreshing a logged-in page reads the current server policy', async (t) => {
  t.mock.method(globalThis, 'fetch', async () => response({ enabled: true }))
  const { visible } = mount(t)
  await settle()
  assert.equal(visible.value, true)
})

test('invalid values, malformed JSON, HTTP errors and network failures cannot dismiss a notice', async (t) => {
  let next = () => response({ enabled: true })
  t.mock.method(globalThis, 'fetch', async () => next())
  const { visible } = mount(t)
  await settle()
  for (const invalid of [null, {}, [], { enabled: 'false' }, { enabled: 0 }]) {
    next = () => response(invalid)
    t.mock.timers.tick(30_000)
    await settle()
    assert.equal(visible.value, true)
  }
  for (const failure of [
    () => new Response('not JSON'),
    () => new Response('{}', { status: 503 }),
    () => {
      throw new TypeError('network unavailable')
    }
  ]) {
    next = failure
    t.mock.timers.tick(30_000)
    await settle()
    assert.equal(visible.value, true)
  }
})

test('an initial failure does not invent policy and retries successfully', async (t) => {
  let fail = true
  t.mock.method(globalThis, 'fetch', async () => {
    if (fail) throw new TypeError('network unavailable')
    return response({ enabled: true })
  })
  const { visible } = mount(t)
  await settle()
  assert.equal(visible.value, false)
  fail = false
  t.mock.timers.tick(30_000)
  await settle()
  assert.equal(visible.value, true)
})

test('a late response after logout cannot resurrect the notice', async (t) => {
  let finish
  let signal
  t.mock.method(globalThis, 'fetch', (_url, options) => {
    signal = options.signal
    return new Promise((resolve) => {
      finish = resolve
    })
  })
  const { active, visible } = mount(t)
  active.value = false
  assert.equal(signal.aborted, true)
  finish(response({ enabled: true }))
  await settle()
  assert.equal(visible.value, false)
})

test('slow requests time out and retry without overlapping requests', async (t) => {
  let inFlight = 0
  let maxInFlight = 0
  const fetch = t.mock.method(globalThis, 'fetch', (_url, { signal }) => {
    inFlight += 1
    maxInFlight = Math.max(maxInFlight, inFlight)
    return new Promise((_resolve, reject) =>
      signal.addEventListener(
        'abort',
        () => {
          inFlight -= 1
          reject(new DOMException('Aborted', 'AbortError'))
        },
        { once: true }
      )
    )
  })
  const { visible } = mount(t)
  t.mock.timers.tick(5_000)
  await settle()
  assert.equal(inFlight, 0)
  assert.equal(visible.value, false)
  t.mock.timers.tick(30_000)
  assert.equal(fetch.mock.callCount(), 2)
  assert.equal(maxInFlight, 1)
})

test('disposing the component cancels polling', async (t) => {
  const fetch = t.mock.method(globalThis, 'fetch', async () => response({ enabled: true }))
  const { scope } = mount(t)
  await settle()
  scope.stop()
  t.mock.timers.tick(90_000)
  assert.equal(fetch.mock.callCount(), 1)
})

test('notice markup has no close control, dismissal handler, amount or auto-dismiss timer', () => {
  const source = readFileSync(
    new URL('../src/components/business/admin-renewal-notice/index.vue', import.meta.url),
    'utf8'
  )
  assert.ok(source.includes('账号服务即将到期，请联系管理员续费。'))
  assert.ok(source.includes('el-message--error'))
  assert.doesNotMatch(source, /<button|closeBtn|@click|@keydown|setTimeout|duration|金额|[¥￥]/)
})

test('the reminder occupies the header gap before search instead of a floating body toast', () => {
  const header = readFileSync(
    new URL('../src/components/core/layouts/art-header-bar/index.vue', import.meta.url),
    'utf8'
  )
  const app = readFileSync(new URL('../src/App.vue', import.meta.url), 'utf8')
  const notice = readFileSync(
    new URL('../src/components/business/admin-renewal-notice/index.vue', import.meta.url),
    'utf8'
  )
  assert.doesNotMatch(app, /<AdminRenewalNotice/)
  assert.equal((header.match(/<AdminRenewalNotice\b/g) || []).length, 1)
  assert.ok(header.indexOf('<AdminRenewalNotice') > header.indexOf('<ArtBreadcrumb'))
  assert.ok(header.indexOf('<AdminRenewalNotice') < header.indexOf('<!-- 搜索 -->'))
  assert.match(header, /:active="userStore\.isLogin && Boolean\(userStore\.accessToken\)"/)
  assert.doesNotMatch(notice, /<Teleport|position:\s*fixed|top:\s*72px/)
  assert.match(notice, /position:\s*static/)
})

test(
  'operator switch is atomic, backs up the old flag, and rejects invalid input and symlinks',
  { skip: process.platform === 'win32' },
  (t) => {
    const root = realpathSync(mkdtempSync(join(tmpdir(), 'yulin-renewal-test-')))
    t.after(() => {
      assert.ok(root.startsWith(realpathSync(tmpdir()) + '/yulin-renewal-test-'))
      rmSync(root, { recursive: true, force: true })
    })
    const script = new URL('../../deploy/scripts/set-admin-renewal.sh', import.meta.url).pathname
    const run = (value) => execFileSync('bash', [script, value, root], { encoding: 'utf8' })
    run('on')
    const file = join(root, 'runtime', 'admin-renewal.json')
    assert.deepEqual(JSON.parse(readFileSync(file, 'utf8')), { enabled: true })
    run('off')
    assert.deepEqual(JSON.parse(readFileSync(file, 'utf8')), { enabled: false })
    assert.equal(
      readdirSync(join(root, 'runtime')).filter((name) => name.includes('.before.')).length,
      1
    )
    assert.equal(spawnSync('bash', [script, 'invalid', root]).status, 2)
    assert.deepEqual(JSON.parse(readFileSync(file, 'utf8')), { enabled: false })
    assert.equal(spawnSync('bash', [script, 'on', root, 'unexpected']).status, 2)
    const linkedRoot = join(root, 'linked-release')
    mkdirSync(linkedRoot)
    symlinkSync(join(root, 'runtime'), join(linkedRoot, 'runtime'))
    assert.equal(spawnSync('bash', [script, 'on', linkedRoot]).status, 2)
  }
)
