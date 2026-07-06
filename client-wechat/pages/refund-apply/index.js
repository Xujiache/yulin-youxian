const { getOrder, submitRefund, uploadRefundEvidence } = require("../../api/orders");
const { yuan } = require("../../utils/format");

const REFUND_REASONS = [
  { value: "商品质量问题", desc: "腐坏、变质、有异味，或明显不符合正常食用状态" },
  { value: "少件/漏发", desc: "实际收到的商品数量、份数或重量少于订单记录" },
  { value: "发错商品", desc: "收到的商品与订单商品不一致，或规格明显不符" },
  { value: "包装破损", desc: "包装破裂、渗漏、污染，影响商品保存或食用" },
  { value: "重量不符", desc: "称重商品实收重量与下单重量差异较大" },
  { value: "配送超时", desc: "未在预约配送时间内送达，影响使用安排" },
  { value: "商品不新鲜", desc: "外观、口感、气味或成熟度明显异常" },
  { value: "不想要了", desc: "商品仍符合售后规则，希望取消本次购买" },
  { value: "其他原因", desc: "以上原因都不符合，需要在补充描述里说明" }
];

function chooseEvidenceFiles(count) {
  return new Promise((resolve, reject) => {
    if (wx.chooseMedia) {
      wx.chooseMedia({
        count,
        mediaType: ["image"],
        sourceType: ["album", "camera"],
        success(result) {
          resolve((result.tempFiles || []).map((file) => file.tempFilePath).filter(Boolean));
        },
        fail: reject
      });
      return;
    }
    wx.chooseImage({
      count,
      sourceType: ["album", "camera"],
      success(result) {
        resolve(result.tempFilePaths || []);
      },
      fail: reject
    });
  });
}

function moneyToCents(value) {
  const text = String(value || "").replace(/[^\d.]/g, "");
  const parts = text.split(".");
  const normalized = parts.length > 1 ? `${parts[0]}.${parts.slice(1).join("").slice(0, 2)}` : parts[0];
  const amount = Number(normalized);
  if (!Number.isFinite(amount) || amount <= 0) {
    return 0;
  }
  return Math.round(amount * 100);
}

function clampRefundAmount(amount, maxAmount) {
  const max = Number(maxAmount || 0);
  if (max <= 0) {
    return 0;
  }
  return Math.min(Math.max(Number(amount || 0), 1), max);
}

Page({
  data: {
    loading: true,
    reasons: REFUND_REASONS,
    activeReason: REFUND_REASONS[0].value,
    orderId: 0,
    items: [],
    refundAmount: 0,
    refundAmountText: "0.00",
    refundAmountYuan: "0.00",
    maxRefundAmount: 0,
    maxRefundAmountText: "0.00",
    productRefundAmount: 0,
    productRefundAmountText: "0.00",
    activeAmountQuick: "all",
    description: "",
    evidenceImages: [],
    reasonSheetVisible: false,
    submitting: false,
    uploadingEvidence: false
  },

  onLoad(options) {
    const orderId = Number(options.orderId || 0);
    this.setData({ orderId });
    this.loadOrder(orderId);
  },

  async loadOrder(orderId) {
    if (!orderId) {
      wx.showToast({ title: "订单不存在", icon: "none" });
      this.setData({ loading: false });
      return;
    }
    try {
      const order = await getOrder(orderId);
      const items = order.items || [];
      const maxRefundAmount = Math.max((order.paidAmount || order.payableAmount || 0) - (order.refundedAmount || 0), 0);
      const productAmount = items.reduce((sum, item) => sum + (item.amount || 0), 0);
      const productRefundAmount = Math.min(productAmount, maxRefundAmount);
      this.setData({
        items: items.map((item) => ({
          ...item,
          amountText: yuan(item.amount)
        })),
        refundAmount: maxRefundAmount,
        refundAmountText: yuan(maxRefundAmount),
        refundAmountYuan: yuan(maxRefundAmount),
        maxRefundAmount,
        maxRefundAmountText: yuan(maxRefundAmount),
        productRefundAmount,
        productRefundAmountText: yuan(productRefundAmount)
      });
    } catch {
      wx.showToast({ title: "订单加载失败", icon: "none" });
    } finally {
      this.setData({ loading: false });
    }
  },

  openReasonSheet() {
    this.setData({ reasonSheetVisible: true });
  },

  closeReasonSheet() {
    this.setData({ reasonSheetVisible: false });
  },

  noop() {},

  selectReason(event) {
    const reason = event.currentTarget.dataset.reason;
    if (!reason) {
      return;
    }
    this.setData({
      activeReason: reason,
      reasonSheetVisible: false
    });
  },

  handleDescription(event) {
    this.setData({ description: event.detail.value });
  },

  setRefundAmount(amount, activeAmountQuick = "custom") {
    const refundAmount = clampRefundAmount(amount, this.data.maxRefundAmount);
    this.setData({
      refundAmount,
      refundAmountText: yuan(refundAmount),
      refundAmountYuan: yuan(refundAmount),
      activeAmountQuick
    });
  },

  handleRefundAmountInput(event) {
    const value = event.detail.value;
    const amount = moneyToCents(value);
    const refundAmount = amount > 0 ? Math.min(amount, this.data.maxRefundAmount) : 0;
    this.setData({
      refundAmountYuan: value,
      refundAmount,
      refundAmountText: yuan(refundAmount),
      activeAmountQuick: "custom"
    });
  },

  handleRefundAmountBlur() {
    this.setRefundAmount(this.data.refundAmount, this.data.activeAmountQuick);
  },

  handleRefundSliderChange(event) {
    this.setRefundAmount(event.detail.value, "custom");
  },

  handleQuickAmount(event) {
    const type = event.currentTarget.dataset.type;
    if (type === "product") {
      this.setRefundAmount(this.data.productRefundAmount || this.data.maxRefundAmount, "product");
      return;
    }
    if (type === "half") {
      this.setRefundAmount(Math.round((this.data.maxRefundAmount || 0) / 2), "half");
      return;
    }
    this.setRefundAmount(this.data.maxRefundAmount, "all");
  },

  async handleChooseEvidence() {
    if (this.data.uploadingEvidence) {
      return;
    }
    const remain = 3 - this.data.evidenceImages.length;
    if (remain <= 0) {
      wx.showToast({ title: "最多上传 3 张凭证", icon: "none" });
      return;
    }
    try {
      const filePaths = await chooseEvidenceFiles(remain);
      if (!filePaths.length) {
        return;
      }
      this.setData({ uploadingEvidence: true });
      wx.showLoading({ title: "上传中" });
      const uploaded = [];
      for (const filePath of filePaths) {
        uploaded.push(await uploadRefundEvidence(filePath));
      }
      this.setData({
        evidenceImages: this.data.evidenceImages.concat(uploaded).slice(0, 3)
      });
      wx.hideLoading();
      wx.showToast({ title: "凭证已上传", icon: "success" });
    } catch (error) {
      wx.hideLoading();
      const message = (error && error.errMsg) || (error && error.message) || "";
      if (!message.includes("cancel")) {
        wx.showToast({ title: (error && error.message) || "凭证上传失败", icon: "none" });
      }
    } finally {
      this.setData({ uploadingEvidence: false });
    }
  },

  handlePreviewEvidence(event) {
    const index = Number(event.currentTarget.dataset.index || 0);
    const urls = this.data.evidenceImages.map((item) => item.displayUrl || item.url).filter(Boolean);
    if (!urls.length) {
      return;
    }
    wx.previewImage({
      current: urls[index],
      urls
    });
  },

  handleRemoveEvidence(event) {
    const index = Number(event.currentTarget.dataset.index);
    const evidenceImages = this.data.evidenceImages.filter((_, currentIndex) => currentIndex !== index);
    this.setData({ evidenceImages });
  },

  async handleSubmit() {
    if (this.data.submitting) {
      return;
    }
    if (!this.data.orderId || !this.data.refundAmount) {
      wx.showToast({ title: "暂无可退金额", icon: "none" });
      return;
    }
    if (this.data.refundAmount > this.data.maxRefundAmount) {
      wx.showToast({ title: "退款金额不能超过可退金额", icon: "none" });
      this.setRefundAmount(this.data.maxRefundAmount);
      return;
    }
    try {
      this.setData({ submitting: true });
      await submitRefund({
        orderId: this.data.orderId,
        orderItemIds: [],
        refundAmount: this.data.refundAmount,
        reason: this.data.description ? `${this.data.activeReason}：${this.data.description}` : this.data.activeReason,
        evidenceImages: this.data.evidenceImages.map((item) => item.url).filter(Boolean)
      });
      wx.showToast({ title: "已提交申请", icon: "success" });
      setTimeout(() => {
        wx.navigateBack();
      }, 600);
    } catch (error) {
      wx.showModal({
        title: "提交失败",
        content: (error && error.message) || "请稍后重试",
        showCancel: false,
        confirmText: "知道了"
      });
    } finally {
      this.setData({ submitting: false });
    }
  }
});
