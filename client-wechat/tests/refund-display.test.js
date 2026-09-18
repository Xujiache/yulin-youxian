const test = require('node:test');
const assert = require('node:assert/strict');
global.getApp = () => ({ globalData: { apiBaseUrl: 'https://api.test' } });
const { normalizeRefund } = require('../api/normalize');
const { refundDisplay } = require('../utils/refund-display');

test('item refund preserves product, partial amount, custom reason and status for customer', () => {
  const result = refundDisplay(normalizeRefund({ source: 'ADMIN', status: '退款中', refundAmount: 150,
    reason: '没货：今天这款卖完了', items: [{ orderItemId: 8, productName: '番茄', imageUrl: '/uploads/a.webp',
      specificationText: '500g', quantity: 0.5, saleUnit: '斤', refundAmount: 150 }] }));
  assert.equal(result.sourceText, '商家主动退款');
  assert.equal(result.items[0].imageUrl, 'https://api.test/uploads/a.webp');
  assert.equal(result.items[0].amountText, '1.50');
  assert.match(result.items[0].description, /0.5斤/);
  assert.equal(result.reason, '没货：今天这款卖完了');
  assert.match(result.statusHint, /已提交微信处理/);
  assert.doesNotMatch(result.statusHint, /确认退款成功/);
});
test('historical refunds have no invented product details and failures are not shown as successful', () => {
  const result = refundDisplay(normalizeRefund({ source: 'USER', status: '退款失败', refundAmount: 100 }));
  assert.deepEqual(result.items, []);
  assert.match(result.statusHint, /尚未成功/);
  assert.match(refundDisplay({ status: '退款成功' }).statusHint, /微信已确认/);
});

test('order detail binds itemized refunds while keeping the fulfillment status visible', () => {
  let definition;
  global.Page = value => { definition = value; };
  require('../pages/order-detail/index');
  let data;
  definition.applyOrder.call({
    data: {},
    setData(value) { data = value; },
    syncDeliverySection() {}
  }, {
    id: 123, status: '备货中', paidAmount: 3200, payableAmount: 3200, refundedAmount: 150,
    items: [], gifts: [], refunds: [{ id: 9, source: 'ADMIN', status: '退款成功', refundAmount: 150,
      reason: '商品损坏：番茄挤压', items: [{ orderItemId: 8, productName: '番茄', quantity: 0.5, saleUnit: '斤', refundAmount: 150 }] }]
  });
  assert.equal(data.statusText, '备货中');
  assert.equal(data.hasRefundedAmount, true);
  assert.equal(data.refundRecords[0].sourceText, '商家主动退款');
  assert.equal(data.refundRecords[0].items[0].productName, '番茄');
  assert.equal(data.refundRecords[0].items[0].amountText, '1.50');
  assert.equal(data.refundRecords[0].reason, '商品损坏：番茄挤压');
});
