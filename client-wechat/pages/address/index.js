const { createAddress, deleteAddress, getAddresses, setDefaultAddress, updateAddress } = require("../../api/addresses");
const { syncTheme } = require("../../utils/theme");

const EMPTY_FORM = {
  id: 0,
  name: "",
  phone: "",
  detail: "",
  locationName: "",
  latitude: null,
  longitude: null,
  isDefault: false
};

Page({
  data: {
    glassMode: false,
    loading: true,
    addresses: [],
    selectMode: false,
    selectedId: 0,
    formVisible: false,
    form: EMPTY_FORM
  },

  onLoad(options = {}) {
    this.setData({
      selectMode: options.select === "1",
      selectedId: Number(options.selectedId || 0)
    });
  },

  onShow() {
    syncTheme(this);
    this.loadAddresses();
  },

  async loadAddresses() {
    try {
      this.setData({ addresses: await getAddresses() });
    } catch {
      this.setData({ addresses: [] });
      wx.showToast({ title: "地址加载失败", icon: "none" });
    } finally {
      this.setData({ loading: false });
    }
  },

  async handleSetDefault(event) {
    const id = Number(event.currentTarget.dataset.id);
    try {
      await setDefaultAddress(id);
      await this.loadAddresses();
    } catch {
      wx.showToast({ title: "设置失败", icon: "none" });
    }
  },

  handleAdd() {
    this.setData({
      formVisible: true,
      form: { ...EMPTY_FORM, isDefault: !this.data.addresses.length }
    });
  },

  handleAddressCardTap(event) {
    const id = Number(event.currentTarget.dataset.id);
    if (this.data.selectMode) {
      const address = this.data.addresses.find((item) => item.id === id);
      if (!address) {
        return;
      }
      wx.setStorageSync("checkoutSelectedAddress", address);
      wx.navigateBack();
      return;
    }
    this.openEditById(id);
  },

  handleEdit(event) {
    const id = Number(event.currentTarget.dataset.id);
    this.openEditById(id);
  },

  openEditById(id) {
    const address = this.data.addresses.find((item) => item.id === id);
    if (!address) {
      return;
    }
    this.setData({
      formVisible: true,
      form: {
        ...EMPTY_FORM,
        ...address
      }
    });
  },

  handleChooseLocation() {
    wx.chooseLocation({
      success: (location) => {
        const detail = location.address || location.name || this.data.form.detail;
        this.setData({
          form: {
            ...this.data.form,
            detail,
            locationName: location.name || detail,
            latitude: location.latitude,
            longitude: location.longitude
          }
        });
      },
      fail: (error) => {
        const message = error && error.errMsg ? error.errMsg : "";
        if (message.includes("auth deny") || message.includes("authorize")) {
          wx.showModal({
            title: "需要位置权限",
            content: "请允许位置权限后再选择收货位置，方便门店配送。",
            confirmText: "去设置",
            success(result) {
              if (result.confirm) {
                wx.openSetting();
              }
            }
          });
          return;
        }
        wx.showToast({ title: "位置选择已取消", icon: "none" });
      }
    });
  },

  async handleDelete(event) {
    const id = Number(event.currentTarget.dataset.id);
    const result = await new Promise((resolve) => {
      wx.showModal({
        title: "删除地址",
        content: "确认删除这个收货地址吗？",
        success: resolve
      });
    });
    if (!result.confirm) {
      return;
    }
    try {
      await deleteAddress(id);
      await this.loadAddresses();
      wx.showToast({ title: "已删除", icon: "success" });
    } catch {
      wx.showToast({ title: "删除失败", icon: "none" });
    }
  },

  handleInput(event) {
    const field = event.currentTarget.dataset.field;
    this.setData({
      form: {
        ...this.data.form,
        [field]: event.detail.value
      }
    });
  },

  handleDefaultChange(event) {
    const value = Array.isArray(event.detail.value) ? event.detail.value.length > 0 : !!event.detail.value;
    this.setData({
      form: {
        ...this.data.form,
        isDefault: value
      }
    });
  },

  handleCloseForm() {
    this.setData({ formVisible: false, form: EMPTY_FORM });
  },

  async handleSubmitForm() {
    const form = this.data.form;
    if (!form.name || !form.phone || !form.detail) {
      wx.showToast({ title: "请填写完整地址", icon: "none" });
      return;
    }
    if (form.latitude === null || form.latitude === undefined || form.longitude === null || form.longitude === undefined) {
      wx.showToast({ title: "请在地图上选择位置", icon: "none" });
      return;
    }
    try {
      const payload = {
        name: form.name,
        phone: form.phone,
        detail: form.detail,
        locationName: form.locationName,
        latitude: form.latitude,
        longitude: form.longitude,
        isDefault: form.isDefault
      };
      if (form.id) {
        await updateAddress(form.id, payload);
      } else {
        await createAddress(payload);
      }
      this.setData({ formVisible: false, form: EMPTY_FORM });
      await this.loadAddresses();
      wx.showToast({ title: "已保存", icon: "success" });
    } catch {
      wx.showToast({ title: "保存失败", icon: "none" });
    }
  }
});
