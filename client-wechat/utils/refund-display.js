const { yuan } = require('./format');

function refundDisplay(refund) {
  return {
    ...refund,
    amountText: yuan(refund.refundAmount),
    sourceText: refund.source === 'ADMIN' ? '商家主动退款' : refund.source === 'USER' ? '用户申请' : '系统退款',
    createdAtText: refund.createdAt ? refund.createdAt.replace('T', ' ').slice(0, 19) : '',
    items: (refund.items || []).map(item => ({
      ...item,
      amountText: yuan(item.refundAmount),
      description: [item.specificationText, `原购买数量 ${item.quantity}${item.saleUnit || ''}`].filter(Boolean).join(' · ')
    })),
    statusHint: refund.status === '退款成功' ? '微信已确认退款成功，请查看原支付账户账单'
      : refund.status === '退款失败' ? '退款尚未成功，商家正在处理'
        : refund.status === '退款中' ? '已提交微信处理，到账以支付账户记录为准' : ''
  };
}

module.exports = { refundDisplay };
