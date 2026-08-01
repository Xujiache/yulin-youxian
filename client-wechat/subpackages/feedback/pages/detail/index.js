'use strict';

const api = require('../../core/api');
const { cssVariables } = require('../../core/theme');
const { decorateFeedback, decorateMessage } = require('../../core/presentation');
const { userMessage } = require('../../core/errors');
const { chooseImages, MAX_IMAGES } = require('../../core/images');
const { uploadAll, uploadOne } = require('../../core/upload-batch');
const { collectDeviceContext } = require('../../core/device-context');
const events = require('../../core/events');
const websocket = require('../../core/websocket');
const { hydrateAttachments } = require('../../core/private-images');

Page({
  data: { themeStyle: '', state: 'loading', errorText: '', id: '', feedback: null, messages: [], supplement: '', images: [], attachmentMax: MAX_IMAGES, submitting: false },
  onLoad(options) {
    this.loadEpoch = 0;
    this.setData({ themeStyle: cssVariables(), id: options.id || '' });
    this.offEvent = events.on('socket-event', (event) => {
      if (!event.data || !event.data.feedbackId || event.data.feedbackId === this.data.id) this.load();
    });
    this.offCompensate = events.on('compensate', () => this.load());
    this.load();
  },
  onShow() { websocket.setForeground(true); },
  onHide() { websocket.setForeground(false); },
  onUnload() { this.loadEpoch += 1; websocket.setForeground(false); if (this.offEvent) this.offEvent(); if (this.offCompensate) this.offCompensate(); },
  load() {
    if (!this.data.id) { this.setData({ state: 'error', errorText: '反馈编号无效' }); return Promise.resolve(); }
    const epoch = this.loadEpoch += 1;
    return api.getFeedback(this.data.id).then((result) => {
      const feedback = decorateFeedback(result);
      return hydrateAttachments(feedback.attachments).then((attachments) => {
        if (epoch !== this.loadEpoch) return;
        this.setData({
          feedback: Object.assign({}, feedback, { attachments }),
          messages: (result.messages || []).map(decorateMessage),
          attachmentMax: Math.max(0, MAX_IMAGES - attachments.length),
          state: 'content',
          errorText: ''
        });
      });
    }).catch((error) => { if (epoch === this.loadEpoch) this.setData({ state: 'error', errorText: userMessage(error) }); });
  },
  preview(event) {
    const urls = (this.data.feedback.attachments || []).map((item) => item.url).filter(Boolean);
    if (urls.length) wx.previewImage({ current: event.currentTarget.dataset.url, urls });
  },
  onSupplement(event) { this.setData({ supplement: event.detail.value }); },
  chooseImages() {
    const remaining = Math.max(0, this.data.attachmentMax - this.data.images.length);
    if (!remaining) { wx.showToast({ title: '每个工单累计最多 6 张截图', icon: 'none' }); return; }
    chooseImages(remaining).then((files) => this.setData({ images: this.data.images.concat(files) })).catch((error) => wx.showToast({ title: userMessage(error), icon: 'none' }));
  },
  removeImage(event) { const images = this.data.images.slice(); images.splice(event.detail.index, 1); this.setData({ images }); },
  retryImage(event) { uploadOne(this.data.images, event.detail.index, (images) => this.setData({ images })).then((images) => this.setData({ images })).catch((error) => wx.showToast({ title: userMessage(error), icon: 'none' })); },
  submitSupplement() {
    const content = this.data.supplement.trim();
    if (!content || this.data.submitting) { if (!content) wx.showToast({ title: '请填写补充内容', icon: 'none' }); return; }
    this.setData({ submitting: true });
    uploadAll(this.data.images, (images) => this.setData({ images }))
      .then((images) => { this.setData({ images }); return collectDeviceContext({ source: 'embedded-supplement' }); })
      .then((context) => api.supplementFeedback(this.data.id, { content, attachmentIds: this.data.images.map((item) => item.attachmentId).filter(Boolean), context }))
      .then(() => { this.setData({ supplement: '', images: [] }); wx.showToast({ title: '补充成功', icon: 'success' }); return this.load(); })
      .catch((error) => wx.showToast({ title: userMessage(error), icon: 'none' }))
      .finally(() => this.setData({ submitting: false }));
  }
});
