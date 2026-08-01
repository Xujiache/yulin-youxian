'use strict';

Component({
  properties: {
    state: { type: String, value: 'content' },
    emptyText: { type: String, value: '暂无内容' },
    errorText: { type: String, value: '加载失败' },
    loadingText: { type: String, value: '加载中…' }
  },
  methods: {
    retry() { this.triggerEvent('retry'); }
  }
});
