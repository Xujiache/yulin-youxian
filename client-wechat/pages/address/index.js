const { createAddress, deleteAddress, getAddresses, setDefaultAddress, updateAddress } = require("../../api/addresses");
const {
  FLOOR_OPTIONS,
  buildAddressCascadeOptions,
  buildAddressDetail,
  cascadeValueForSelection,
  isCompleteSelection,
  parseTemplateAddress,
  roomOptionsForFloor,
  selectionFromCascader
} = require("../../utils/address-cascade");
const { syncTheme } = require("../../utils/theme");

const EMPTY_FORM = {
  id: 0,
  name: "",
  phone: "",
  detail: "",
  locationName: "",
  latitude: null,
  longitude: null,
  community: "",
  building: 0,
  unit: 0,
  floor: 0,
  room: "",
  isDefault: false
};

function selectionViewState(form) {
  return {
    form,
    cascadeValue: cascadeValueForSelection(form) || "",
    roomOptions: roomOptionsForFloor(form.floor),
    addressSummary: buildAddressDetail(form)
  };
}

Page({
  data: {
    glassMode: false,
    loading: true,
    addresses: [],
    selectMode: false,
    selectedId: 0,
    formVisible: false,
    form: { ...EMPTY_FORM },
    addressCascadeVisible: false,
    addressCascadeOptions: [],
    cascadeSubTitles: ["小区", "楼号", "单元"],
    cascadeValue: "",
    floorOptions: FLOOR_OPTIONS,
    roomOptions: [],
    addressSummary: "",
    saving: false,
    operatingAddressId: 0
  },

  onLoad(options = {}) {
    this._pageAlive = true;
    this._addressRequestToken = 0;
    this.setData({
      selectMode: options.select === "1",
      selectedId: Number(options.selectedId || 0)
    });
  },

  onShow() {
    this._pageAlive = true;
    syncTheme(this);
    this.loadAddresses();
  },

  onUnload() {
    this._pageAlive = false;
    this._addressRequestToken += 1;
  },

  async loadAddresses() {
    const requestToken = this._addressRequestToken + 1;
    this._addressRequestToken = requestToken;
    try {
      const addresses = await getAddresses();
      if (this._pageAlive && requestToken === this._addressRequestToken) {
        this.setData({ addresses });
      }
    } catch {
      if (!this._pageAlive || requestToken !== this._addressRequestToken) {
        return;
      }
      this.setData({ addresses: [] });
      wx.showToast({ title: "地址加载失败", icon: "none" });
    } finally {
      if (this._pageAlive && requestToken === this._addressRequestToken) {
        this.setData({ loading: false });
      }
    }
  },

  async handleSetDefault(event) {
    const id = Number(event.currentTarget.dataset.id);
    if (!id || this.data.operatingAddressId || this.data.saving) {
      return;
    }
    this.setData({ operatingAddressId: id });
    try {
      await setDefaultAddress(id);
      await this.loadAddresses();
    } catch {
      wx.showToast({ title: "设置失败", icon: "none" });
    } finally {
      if (this._pageAlive) {
        this.setData({ operatingAddressId: 0 });
      }
    }
  },

  handleAdd() {
    const form = { ...EMPTY_FORM, isDefault: !this.data.addresses.length };
    this.setData({
      formVisible: true,
      addressCascadeVisible: false,
      ...selectionViewState(form)
    });
  },

  handleAddressCardTap(event) {
    const id = Number(event.currentTarget.dataset.id);
    if (this.data.selectMode) {
      const address = this.data.addresses.find((item) => item.id === id);
      if (!address) {
        return;
      }
      this.selectAddress(address);
      return;
    }
    this.openEditById(id);
  },

  selectAddress(address) {
    const app = getApp();
    app.globalData.checkoutSelectedAddress = address;
    wx.removeStorageSync("checkoutSelectedAddress");
    wx.navigateBack();
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
    const selection = parseTemplateAddress(address);
    const form = {
      ...EMPTY_FORM,
      ...address,
      ...selection
    };
    this.setData({
      formVisible: true,
      addressCascadeVisible: false,
      ...selectionViewState(form)
    });
  },

  handleOpenAddressCascade() {
    if (!this.data.addressCascadeOptions.length) {
      this.setData({
        addressCascadeOptions: buildAddressCascadeOptions(),
        addressCascadeVisible: true
      });
      return;
    }
    this.setData({ addressCascadeVisible: true });
  },

  handleAddressCascadeClose() {
    this.setData({ addressCascadeVisible: false });
  },

  handleAddressCascadeChange(event) {
    const detail = event.detail || {};
    const selection = selectionFromCascader(detail.selectedOptions || []);
    if (!selection.community || !selection.building || !selection.unit) {
      return;
    }
    const form = {
      ...this.data.form,
      ...selection,
      locationName: "",
      latitude: null,
      longitude: null
    };
    this.setData({
      addressCascadeVisible: false,
      ...selectionViewState(form)
    });
  },

  handleFloorSelect(event) {
    const floor = Number(event.currentTarget.dataset.floor || 0);
    if (floor < 1 || floor > 6) {
      return;
    }
    const form = {
      ...this.data.form,
      floor,
      room: ""
    };
    this.setData(selectionViewState(form));
  },

  handleRoomSelect(event) {
    const room = String(event.currentTarget.dataset.room || "");
    if (!roomOptionsForFloor(this.data.form.floor).some((item) => item.value === room)) {
      return;
    }
    const form = {
      ...this.data.form,
      room
    };
    this.setData(selectionViewState(form));
  },

  async handleDelete(event) {
    const id = Number(event.currentTarget.dataset.id);
    if (!id || this.data.operatingAddressId || this.data.saving) {
      return;
    }
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
    this.setData({ operatingAddressId: id });
    try {
      await deleteAddress(id);
      await this.loadAddresses();
      wx.showToast({ title: "已删除", icon: "success" });
    } catch {
      wx.showToast({ title: "删除失败", icon: "none" });
    } finally {
      if (this._pageAlive) {
        this.setData({ operatingAddressId: 0 });
      }
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
    this.setData({
      formVisible: false,
      addressCascadeVisible: false,
      ...selectionViewState({ ...EMPTY_FORM })
    });
  },

  async handleSubmitForm() {
    if (this.data.saving || this.data.operatingAddressId) {
      return;
    }
    const form = this.data.form;
    const name = String(form.name || "").trim();
    const phone = String(form.phone || "").trim();
    if (!name) {
      wx.showToast({ title: "请填写收货人姓名", icon: "none" });
      return;
    }
    if (!/^1[3-9]\d{9}$/.test(phone)) {
      wx.showToast({ title: "请填写正确的手机号", icon: "none" });
      return;
    }
    if (!isCompleteSelection(form)) {
      wx.showToast({ title: "请依次选完小区、楼号、单元、楼层和门牌号", icon: "none" });
      return;
    }
    this.setData({ saving: true });
    try {
      const payload = {
        name,
        phone,
        detail: buildAddressDetail(form),
        locationName: "",
        latitude: null,
        longitude: null,
        isDefault: Boolean(form.isDefault)
      };
      const savedAddress = form.id
        ? await updateAddress(form.id, payload)
        : await createAddress(payload);
      this.setData({
        formVisible: false,
        addressCascadeVisible: false,
        ...selectionViewState({ ...EMPTY_FORM })
      });
      await this.loadAddresses();
      if (this.data.selectMode && savedAddress) {
        this.selectAddress(savedAddress);
        return;
      }
      wx.showToast({ title: "已保存", icon: "success" });
    } catch (error) {
      wx.showToast({ title: (error && error.message) || "保存失败", icon: "none" });
    } finally {
      if (this._pageAlive) {
        this.setData({ saving: false });
      }
    }
  }
});
