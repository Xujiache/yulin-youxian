const { yuan, lineAmount } = require("../../utils/format");
const { getAddresses } = require("../../api/addresses");
const { getCart } = require("../../api/cart");
const { getDeliverySlots } = require("../../api/delivery");
const { confirmDevelopmentPayment, createOrder, payOrder, previewOrder } = require("../../api/orders");
const { requireCompleteProfile } = require("../../utils/auth-guard");
const { syncTheme } = require("../../utils/theme");
const EMPTY_AMOUNT = {
    productAmountText: "0.00",
    deliveryFeeText: "0.00",
    packageFeeText: "0.00",
    totalText: "0.00",
    deliveryFeeNotice: ""
};
function requestWechatPayment(payment) {
    if (!payment || payment.developmentMode) {
        return Promise.resolve({ developmentMode: true });
    }
    return new Promise((resolve, reject) => {
        wx.requestPayment({
            timeStamp: payment.timeStamp,
            nonceStr: payment.nonceStr,
            "package": payment.packageValue,
            signType: payment.signType,
            paySign: payment.paySign,
            success: resolve,
            fail: reject
        });
    });
}
Page({
    data: {
        glassMode: false,
        loading: true,
        address: null,
        items: [],
        slots: [],
        activeSlotId: 0,
        productAmountText: "0.00",
        deliveryFeeText: "5.00",
        packageFeeText: "1.00",
        totalText: "0.00",
        deliveryFeeNotice: "",
        cartItemIds: [],
        loadError: "",
        payDisabled: true
    },
    onLoad() {
        if (!requireCompleteProfile("/pages/checkout/index")) {
            this.setData({ loading: false });
            return;
        }
        this.loadCheckout();
    },
    onShow() {
        syncTheme(this);
        const selectedAddress = wx.getStorageSync("checkoutSelectedAddress");
        if (!selectedAddress || !selectedAddress.id) {
            return;
        }
        wx.removeStorageSync("checkoutSelectedAddress");
        if (!this.data.cartItemIds.length || !this.data.activeSlotId) {
            this.setData({ address: selectedAddress });
            return;
        }
        this.refreshPreview(selectedAddress, this.data.activeSlotId);
    },
    async loadCheckout() {
        this.setData({
            loading: true,
            loadError: "",
            payDisabled: true,
            address: null,
            items: [],
            slots: [],
            activeSlotId: 0,
            cartItemIds: [],
            ...EMPTY_AMOUNT
        });
        try {
            const [remoteAddresses, remoteSlots, cart] = await Promise.all([
                getAddresses(),
                getDeliverySlots(),
                getCart()
            ]);
            const selectedItems = (cart.items || []).filter((item) => item.selected);
            const storedAddress = wx.getStorageSync("checkoutSelectedAddress");
            if (storedAddress && storedAddress.id) {
                wx.removeStorageSync("checkoutSelectedAddress");
            }
            const address = (storedAddress && storedAddress.id)
                ? storedAddress
                : remoteAddresses.find((item) => item.isDefault) || remoteAddresses[0];
            const availableSlots = (remoteSlots || []).filter((item) => item && item.available !== false);
            const slot = availableSlots[0];
            const cartItemIds = selectedItems.map((item) => item.id);
            if (!cartItemIds.length) {
                this.setData({
                    address: address || null,
                    slots: availableSlots,
                    items: [],
                    cartItemIds: [],
                    payDisabled: true,
                    loadError: "购物车还没有选中的商品，请先选择后再结算。",
                    ...EMPTY_AMOUNT
                });
                wx.showToast({ title: "请先选择商品", icon: "none" });
                return;
            }
            if (!address) {
                wx.showToast({ title: "请先添加收货地址", icon: "none" });
                wx.navigateTo({ url: "/pages/address/index" });
                return;
            }
            if (!slot) {
                this.setData({
                    address: address || null,
                    slots: [],
                    items: [],
                    cartItemIds,
                    payDisabled: true,
                    loadError: "暂无可预约配送时间",
                    ...EMPTY_AMOUNT
                });
                wx.showToast({ title: "暂无可预约配送时间", icon: "none" });
                return;
            }
            const preview = await previewOrder({
                addressId: address.id,
                deliverySlotId: slot.id,
                cartItemIds
            });
            this.setData({
                address: preview.address,
                slots: availableSlots,
                activeSlotId: slot.id,
                items: preview.items.map((item) => ({
                    ...item,
                    amountText: yuan(item.amount)
                })),
                cartItemIds,
                productAmountText: yuan(preview.productAmount),
                deliveryFeeText: yuan(preview.deliveryFee),
                packageFeeText: yuan(preview.packageFee),
                totalText: yuan(preview.payableAmount),
                deliveryFeeNotice: preview.deliveryFeeNotice || "",
                payDisabled: false,
                loadError: ""
            });
            return;
        }
        catch (error) {
            const message = error && error.message ? error.message : "订单信息加载失败，请稍后重试。";
            this.setData({
                address: null,
                items: [],
                slots: [],
                activeSlotId: 0,
                cartItemIds: [],
                payDisabled: true,
                loadError: message,
                ...EMPTY_AMOUNT
            });
            wx.showToast({ title: message, icon: "none" });
        }
        finally {
            this.setData({ loading: false });
        }
    },
    handleChooseAddress() {
        const selectedId = this.data.address ? this.data.address.id : 0;
        wx.navigateTo({ url: `/pages/address/index?select=1&selectedId=${selectedId}` });
    },
    async refreshPreview(address, activeSlotId) {
        if (!address || !address.id || !activeSlotId || !this.data.cartItemIds.length) {
            return;
        }
        try {
            const preview = await previewOrder({
                addressId: address.id,
                deliverySlotId: activeSlotId,
                cartItemIds: this.data.cartItemIds
            });
            this.setData({
                address: preview.address,
                items: preview.items.map((item) => ({
                    ...item,
                    amountText: yuan(item.amount)
                })),
                productAmountText: yuan(preview.productAmount),
                deliveryFeeText: yuan(preview.deliveryFee),
                packageFeeText: yuan(preview.packageFee),
                totalText: yuan(preview.payableAmount),
                deliveryFeeNotice: preview.deliveryFeeNotice || "",
                payDisabled: false,
                loadError: ""
            });
        }
        catch (error) {
            this.setData({ payDisabled: true });
            wx.showToast({ title: error.message || "地址选择失败", icon: "none" });
        }
    },
    handleRetry() {
        this.loadCheckout();
    },
    goCart() {
        wx.redirectTo({ url: "/pages/cart/index" });
    },
    async chooseSlot(event) {
        const activeSlotId = Number(event.currentTarget.dataset.id);
        this.setData({ activeSlotId });
        if (!this.data.address || !this.data.cartItemIds.length) {
            return;
        }
        await this.refreshPreview(this.data.address, activeSlotId);
    },
    async handlePay() {
        if (!requireCompleteProfile("/pages/checkout/index")) {
            return;
        }
        if (this.data.payDisabled || !this.data.address || !this.data.activeSlotId || !this.data.cartItemIds.length) {
            wx.showToast({ title: "订单信息不完整", icon: "none" });
            return;
        }
        try {
            const order = await createOrder({
                addressId: this.data.address.id,
                deliverySlotId: this.data.activeSlotId,
                cartItemIds: this.data.cartItemIds,
                remark: ""
            });
            const payment = await payOrder(order.id);
            const paymentResult = await requestWechatPayment(payment);
            if (paymentResult && paymentResult.developmentMode) {
                await confirmDevelopmentPayment(order.id);
            }
            wx.showToast({ title: "支付成功", icon: "success" });
            wx.redirectTo({ url: `/pages/order-detail/index?id=${order.id}` });
        }
        catch (error) {
            wx.showToast({ title: error.message || "支付失败，请重试", icon: "none" });
        }
    }
});
