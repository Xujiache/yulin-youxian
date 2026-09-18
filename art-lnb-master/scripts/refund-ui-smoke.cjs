// Run after npm run build. All API calls are mocked; no production refund is sent.
// PLAYWRIGHT_MODULE=/path/to/playwright node scripts/refund-ui-smoke.cjs
const assert = require('node:assert/strict')
const http = require('node:http')
const fs = require('node:fs')
const path = require('node:path')
const os = require('node:os')
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright')

async function main() {
  const dist = path.resolve(__dirname, '../dist')
  assert.ok(fs.existsSync(path.join(dist, 'index.html')), 'Run npm run build first')
  const server = http.createServer((req, res) => {
    const requested = new URL(req.url, 'http://localhost').pathname
    const file = path.resolve(dist, '.' + (requested === '/' ? '/index.html' : requested))
    if (!file.startsWith(dist + path.sep) || !fs.existsSync(file) || !fs.statSync(file).isFile()) {
      res.writeHead(404).end()
      return
    }
    const mime = { '.html': 'text/html', '.js': 'application/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.webp': 'image/webp', '.woff2': 'font/woff2' }
    res.setHeader('Content-Type', mime[path.extname(file)] || 'application/octet-stream')
    fs.createReadStream(file).pipe(res)
  })
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
  const origin = `http://127.0.0.1:${server.address().port}`
  const browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE || undefined, args: ['--no-sandbox'] })
    .catch(error => { server.close(); throw error })
  const screenshots = fs.mkdtempSync(path.join(os.tmpdir(), 'item-refund-ui-'))
  const page = await browser.newPage({ viewport: { width: 1440, height: 1100 } })
  page.setDefaultTimeout(30000)
  const errors = []
  const apiCalls = []
  const posts = []
  let failNextPost = true
  let pendingLookup
  let rows = []
  const items = [
    { id: 701, productId: 101, productName: '番茄', imageUrl: '', specificationText: '当季鲜采', quantity: 0.5, saleUnit: '斤', amount: 1100 },
    { id: 702, productId: 106, productName: '草莓', imageUrl: '', specificationText: '精选大果', quantity: 1, saleUnit: '盒', amount: 2000 }
  ]
  const order = { id: 123, userId: 1000, orderNo: 'TEST-ITEM-REFUND-123', paidAmount: 3200, refundedAmount: 0, items }
  const quote = { availableAmount: 3200, hasUnallocatedRefunds: false, items: items.map((item, i) => ({ item, paidAmount: [1000, 1900][i], availableAmount: [1000, 1900][i] })) }
  page.on('pageerror', error => errors.push(error.message))
  const version = fs.readFileSync(path.resolve(__dirname, '../.env'), 'utf8').match(/VITE_VERSION\s*=\s*([^\r\n]+)/)[1].trim().replace(/['"]/g, '')
  await page.addInitScript(version => {
    const user = JSON.stringify({ isLogin: true, accessToken: 'isolated-ui-test', isLock: false, language: 'zh', info: { userId: 1, userName: '测试管理员', roles: ['R_SUPER'] } })
    localStorage.setItem(`sys-v${version}-user`, user)
    localStorage.setItem('sys-version', version)
  }, version)
  await page.route('**/*', async route => {
    const req = route.request()
    const url = new URL(req.url())
    const ok = data => route.fulfill({ json: { code: 0, message: 'ok', data } })
    if (url.pathname.startsWith('/api/')) {
      apiCalls.push(`${req.method()} ${url.pathname}`)
      if (url.pathname === '/api/admin/auth/profile') return ok({ name: '测试管理员', role: 'SUPER' })
      if (url.pathname === '/api/admin/orders/lookup') {
        if (url.searchParams.get('keyword') === 'slow') await new Promise(resolve => { pendingLookup = resolve })
        return ok(order)
      }
      if (url.pathname === '/api/admin/customers') return ok({ items: [{ userId: 1000, nickName: '测试客户', orderCount: 1 }], total: 1 })
      if (url.pathname === '/api/admin/refunds/orders/123/items') return ok(quote)
      if (url.pathname === '/api/admin/refunds' && req.method() === 'GET') return ok({ items: rows, total: rows.length, page: 1, pageSize: 100 })
      if (url.pathname === '/api/admin/refunds' && req.method() === 'POST') {
        const payload = req.postDataJSON()
        posts.push(payload)
        if (failNextPost) {
          failNextPost = false
          return route.abort('failed')
        }
        const refund = { ...payload, id: 500, refundNo: 'TEST-REFUND-500', orderNo: order.orderNo, status: '退款中', source: 'ADMIN', createdAt: '2026-09-06T20:00:00', evidenceImages: [], items: payload.items.map(selection => ({ ...items.find(item => item.id === selection.orderItemId), ...selection })) }
        rows = [refund]
        return ok(refund)
      }
      throw new Error(`Unexpected API (blocked): ${req.method()} ${url.pathname}`)
    }
    if (url.origin !== origin) return route.abort()
    return route.continue()
  })
  try {
    await page.goto(origin + '/#/fresh/trade/refunds')
    await page.getByRole('button', { name: '主动退款', exact: true }).click()
    const dialog = page.getByRole('dialog', { name: '按商品主动退款' })
    const submit = dialog.getByRole('button', { name: '确认并发起退款' })
    assert.ok(await submit.isDisabled())
    const input = dialog.getByPlaceholder('输入订单 ID 或订单号')
    await input.fill('123')
    await dialog.getByRole('button', { name: '查找订单' }).click()
    const tomato = dialog.getByRole('checkbox', { name: '退款 番茄' })
    await tomato.waitFor({ state: 'attached' })
    assert.ok(await submit.isDisabled(), 'Must select a product explicitly')
    await tomato.locator('xpath=ancestor::label').click()
    assert.ok(await tomato.isChecked())
    await dialog.getByRole('button', { name: '没货', exact: true }).click()
    const reason = dialog.getByPlaceholder('可选择快捷原因后补充，也可直接填写；完整原因会同步显示给用户')
    assert.equal(await reason.inputValue(), '没货')
    await reason.fill('没货：番茄售完，草莓正常配送')
    await dialog.getByRole('spinbutton').first().fill('4.56')
    await reason.click()
    await page.screenshot({ path: path.join(screenshots, 'selected-product.png'), fullPage: true })
    await submit.click()
    await page.getByRole('button', { name: '确认退款', exact: true }).click()
    await page.waitForFunction(() => !document.querySelector('.el-message-box'))
    await page.waitForTimeout(2200)
    assert.equal(posts.length, 1, 'Financial POST must not retry automatically')
    assert.deepEqual(posts[0].items, [{ orderItemId: 701, refundAmount: 456 }])
    assert.equal(posts[0].refundAmount, 456)
    assert.equal(posts[0].reason, '没货：番茄售完，草莓正常配送')
    assert.ok(posts[0].requestKey)
    await submit.click()
    await page.getByRole('button', { name: '确认退款', exact: true }).click()
    await dialog.waitFor({ state: 'hidden' })
    assert.equal(posts.length, 2)
    assert.equal(posts[0].requestKey, posts[1].requestKey, 'Manual retry must reuse the idempotency key')
    await page.getByText('退款已提交，到账状态请查看退款记录', { exact: true }).waitFor()
    assert.equal(await page.getByText('微信已确认退款成功', { exact: true }).count(), 0)
    await page.getByText('退款中', { exact: true }).waitFor()
    await page.screenshot({ path: path.join(screenshots, 'refund-record.png'), fullPage: true })
    await page.getByRole('button', { name: '主动退款', exact: true }).click()
    await input.fill('slow')
    await dialog.getByRole('button', { name: '查找订单' }).click()
    for (let i = 0; i < 50 && !pendingLookup; i++) await page.waitForTimeout(100)
    assert.ok(pendingLookup)
    await input.fill('another-order')
    pendingLookup()
    await page.waitForTimeout(500)
    assert.equal(await dialog.getByRole('checkbox').count(), 0, 'A stale lookup must not restore product selections')
    assert.ok(await submit.isDisabled())
    assert.deepEqual(errors, [])
    console.log(JSON.stringify({ passed: true, checks: ['explicit product selection', 'custom and preset reasons', 'selected-line amounts only', 'POST not automatically retried', 'idempotency on manual retry', 'truthful processing status', 'stale lookup ignored'], screenshots }, null, 2))
  } catch (error) {
    console.error(JSON.stringify({ url: page.url(), errors, apiCalls, body: (await page.locator('body').innerText()).slice(0, 2200) }))
    await page.screenshot({ path: path.join(screenshots, 'failure.png'), fullPage: true })
    throw error
  } finally {
    if (pendingLookup) pendingLookup()
    await browser.close()
    server.close()
  }
}

main().catch(error => { console.error(error); process.exitCode = 1 })
