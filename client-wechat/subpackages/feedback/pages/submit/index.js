'use strict';

const { cssVariables } = require('../../core/theme');
const { chooseImages, MAX_IMAGES } = require('../../core/images');
const { uploadAll, uploadOne } = require('../../core/upload-batch');
const { collectDeviceContext } = require('../../core/device-context');
const { createFeedback } = require('../../core/api');
const { userMessage } = require('../../core/errors');
const navigation = require('../../core/navigation');
const websocket = require('../../core/websocket');

Page({
  data: { themeStyle: '', content: '', images: [], submitting: false },
  onLoad() { this.setData({ themeStyle: cssVariables() }); },
  onShow() { websocket.setForeground(true); },
  onHide() { websocket.setForeground(false); },
  onUnload() { websocket.setForeground(false); },
  onInput(event) { this.setData({ content: event.detail.value }); },
  chooseImages() {
    chooseImages(MAX_IMAGES - this.data.images.length)
      .then((files) => this.setData({ images: this.data.images.concat(files) }))
      .catch((error) => wx.showToast({ title: userMessage(error), icon: 'none' }));
  },
  removeImage(event) {
    const images = this.data.images.slice();
    images.splice(event.detail.index, 1);
    this.setData({ images });
  },
  retryImage(event) {
    const index = event.detail.index;
    uploadOne(this.data.images, index, (images) => this.setData({ images }))
      .then((images) => this.setData({ images }))
      .catch((error) => wx.showToast({ title: userMessage(error), icon: 'none' }));
  },
  submit() {
    const content = this.data.content.trim();
    if (!content) { wx.showToast({ title: '请描述遇到的问题', icon: 'none' }); return; }
    if (this.data.submitting) return;
    this.setData({ submitting: true });
    uploadAll(this.data.images, (images) => this.setData({ images }))
      .then((images) => {
        this.setData({ images });
        return collectDeviceContext({ source: 'embedded-subpackage' });
      })
      .then((context) => createFeedback({
        content,
        attachmentIds: this.data.images.map((item) => item.attachmentId).filter(Boolean),
        context,
      }))
      .then((feedback) => {
        wx.showToast({ title: '反馈已提交', icon: 'success' });
        this.setData({ content: '', images: [] });
        setTimeout(() => navigation.navigate('detail', { id: feedback.id }), 500);
      })
      .catch((error) => wx.showToast({ title: userMessage(error), icon: 'none', duration: 3000 }))
      .finally(() => this.setData({ submitting: false }));
  }
});
