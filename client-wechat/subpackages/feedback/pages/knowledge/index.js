'use strict';

const api = require('../../core/api');
const { cssVariables } = require('../../core/theme');
const { decorateFeedback } = require('../../core/presentation');
const { userMessage } = require('../../core/errors');
const events = require('../../core/events');
const websocket = require('../../core/websocket');

Page({
  data: { themeStyle: '', state: 'loading', errorText: '', items: [], page: 1, pageSize: 20, total: 0, loadingMore: false },
  onLoad() {
    this.loadEpoch = 0;
    this.resetLoading = false;
    this.setData({ themeStyle: cssVariables() });
    this.offCompensate = events.on('compensate', () => this.load(true));
    this.offResolved = events.on('socket-event:feedback.resolved', () => this.load(true));
    this.load(true);
  },
  onShow() { websocket.setForeground(true); },
  onHide() { websocket.setForeground(false); },
  onUnload() {
    this.loadEpoch += 1;
    websocket.setForeground(false);
    if (this.offCompensate) this.offCompensate();
    if (this.offResolved) this.offResolved();
  },
  onPullDownRefresh() { this.load(true).finally(() => wx.stopPullDownRefresh()); },
  onReachBottom() { this.load(false); },
  load(reset) {
    if (!reset && (this.data.loadingMore || this.resetLoading)) return Promise.resolve();
    const epoch = reset ? (this.loadEpoch += 1) : this.loadEpoch;
    const page = reset ? 1 : this.data.page + 1;
    if (!reset && this.data.items.length >= this.data.total) return Promise.resolve();
    if (reset) { this.resetLoading = true; this.setData({ state: 'loading', errorText: '', loadingMore: false }); } else this.setData({ loadingMore: true });
    return api.listKnowledge({ page, pageSize: this.data.pageSize }).then((result) => {
      if (epoch !== this.loadEpoch) return;
      const items = (result.items || []).map(decorateFeedback);
      this.setData({ items: reset ? items : this.data.items.concat(items), page: result.page || page, total: Number(result.total) || 0, state: items.length || (!reset && this.data.items.length) ? 'content' : 'empty' });
    }).catch((error) => { if (epoch === this.loadEpoch) this.setData({ state: reset ? 'error' : this.data.state, errorText: userMessage(error) }); }).finally(() => {
      if (epoch !== this.loadEpoch) return;
      if (reset) this.resetLoading = false;
      this.setData({ loadingMore: false });
    });
  }
});
