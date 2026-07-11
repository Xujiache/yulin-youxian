Component({
  properties: {
    type: {
      type: String,
      value: "list"
    },
    hasTabbar: {
      type: Boolean,
      value: false
    }
  },
  data: {
    glassMode: false,
    four: [1, 2, 3, 4],
    five: [1, 2, 3, 4, 5],
    six: [1, 2, 3, 4, 5, 6]
  },

  lifetimes: {
    attached() {
      this.setData({ glassMode: Boolean(wx.getStorageSync("glassMode")) });
    }
  },

  pageLifetimes: {
    show() {
      this.setData({ glassMode: Boolean(wx.getStorageSync("glassMode")) });
    }
  }
});
