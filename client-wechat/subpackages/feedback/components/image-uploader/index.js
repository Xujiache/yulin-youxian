'use strict';

Component({
  properties: {
    images: { type: Array, value: [] },
    max: { type: Number, value: 6 },
    disabled: { type: Boolean, value: false }
  },
  methods: {
    choose() { if (!this.data.disabled) this.triggerEvent('choose'); },
    remove(event) { if (!this.data.disabled) this.triggerEvent('remove', { index: Number(event.currentTarget.dataset.index) }); },
    retry(event) { if (!this.data.disabled) this.triggerEvent('retry', { index: Number(event.currentTarget.dataset.index) }); },
    preview(event) {
      const current = event.currentTarget.dataset.path;
      wx.previewImage({ current, urls: this.data.images.map((item) => item.path) });
    }
  }
});
