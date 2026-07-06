const { yuan } = require("../../utils/format");
const { getOrder } = require("../../api/orders");

const CONTACT_PHONE = "400-800-1234";

Page({
  data: {
    loading: true,
    address: null,
    items: [],
    order: null,
    orderId: null,
    orderNo: "",
    statusText: "",
    totalText: "0.00"
  },

  async onLoad(options) {
    const id = Number(options.id || 0);
    if (!id) {
      this.setData({ loading: false });
      wx.showToast({ title: "订单不存在", icon: "none" });
      return;
    }
    try {
      const order = await getOrder(id);
      this.setData({
        order,
        orderId: order.id,
        orderNo: order.orderNo,
        statusText: order.status,
        address: order.address,
        items: (order.items || []).map((item) => ({
          ...item,
          amountText: yuan(item.amount)
        })),
        totalText: yuan(order.payableAmount)
      });
      return;
    } catch {
      wx.showToast({ title: "订单详情加载失败", icon: "none" });
    } finally {
      this.setData({ loading: false });
    }
  },

  handleRefund() {
    const id = this.data.orderId;
    if (!id) {
      wx.showToast({ title: "订单不存在", icon: "none" });
      return;
    }
    wx.navigateTo({ url: `/pages/refund-apply/index?orderId=${id}` });
  },

  handleService() {
    wx.showModal({
      title: "联系客服",
      content: `禹邻优鲜客服电话：${CONTACT_PHONE}`,
      confirmText: "拨打电话",
      cancelText: "取消",
      success(result) {
        if (!result.confirm) {
          return;
        }
        wx.makePhoneCall({
          phoneNumber: CONTACT_PHONE,
          fail() {
            wx.showToast({ title: "拨号失败，请稍后重试", icon: "none" });
          }
        });
      }
    });
  }
});
