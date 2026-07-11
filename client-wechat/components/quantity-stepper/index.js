Component({
  properties: {
    value: {
      type: Number,
      value: 1
    },
    min: {
      type: Number,
      value: 1
    },
    max: {
      type: Number,
      value: 9999
    },
    step: {
      type: Number,
      value: 1
    },
    unit: {
      type: String,
      value: ""
    }
  },

  data: {
    displayText: "1",
    glassMode: false
  },

  lifetimes: {
    attached() {
      this.setData({ glassMode: Boolean(wx.getStorageSync("glassMode")) });
      this.updateDisplay();
    }
  },

  pageLifetimes: {
    show() {
      this.setData({ glassMode: Boolean(wx.getStorageSync("glassMode")) });
    }
  },

  observers: {
    "value, unit": function () {
      this.updateDisplay();
    }
  },

  methods: {
    normalize(value) {
      return Math.round(Number(value || 0) * 1000) / 1000;
    },

    updateDisplay() {
      const value = this.normalize(this.properties.value);
      const text = Number.isInteger(value)
        ? String(value)
        : String(value).replace(/0+$/, "").replace(/\.$/, "");
      this.setData({ displayText: text + this.properties.unit });
    },

    emitChange(value) {
      const next = this.normalize(value);
      this.setData({ displayText: next + this.properties.unit });
      this.triggerEvent("change", { value: next });
    },

    handleMinus() {
      const { value, min, step } = this.properties;
      const next = Math.max(Number(min), this.normalize(Number(value) - Number(step)));
      this.emitChange(next);
    },

    handlePlus() {
      const { value, max, step } = this.properties;
      const next = Math.min(Number(max), this.normalize(Number(value) + Number(step)));
      this.emitChange(next);
    }
  }
});
