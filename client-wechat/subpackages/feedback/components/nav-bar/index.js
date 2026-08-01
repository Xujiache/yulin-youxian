'use strict';

const config = require('../../core/config');

Component({
  properties: {
    title: { type: String, value: '' },
    back: { type: Boolean, value: true },
    themeStyle: { type: String, value: '' }
  },
  data: { statusBarHeight: 20, navigationHeight: 44 },
  lifetimes: {
    attached() {
      try {
        const windowInfo = wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync();
        const menu = wx.getMenuButtonBoundingClientRect ? wx.getMenuButtonBoundingClientRect() : null;
        const navigationHeight = menu
          ? (menu.top - windowInfo.statusBarHeight) * 2 + menu.height
          : 44;
        this.setData({ statusBarHeight: windowInfo.statusBarHeight || 20, navigationHeight });
      } catch (_) { /* 保留安全默认值 */ }
    }
  },
  methods: {
    handleBack() {
      const pages = getCurrentPages();
      if (pages.length > 1) wx.navigateBack();
      else wx.reLaunch({ url: `${config.getConfig().subpackageRoot}/pages/list/index` });
    }
  }
});
