const { getProducts } = require("../../api/catalog");
const { syncTheme } = require("../../utils/theme");

const FALLBACK_PRODUCTS = [
  { id: 101, name: "有机水培西红柿", subtitle: "自然熟透 沙瓤多汁", badge: "热销", unitPrice: 399, stockQty: 32, status: 1 },
  { id: 102, name: "本地鲜摘黄瓜", subtitle: "清脆爽口 今日到店", badge: "新鲜", unitPrice: 292, stockQty: 26, status: 1 },
  { id: 103, name: "高山脆甜生菜", subtitle: "适合沙拉轻食", badge: "新鲜直达", unitPrice: 480, stockQty: 18, status: 1 },
  { id: 104, name: "精选有机菠菜", subtitle: "营养丰富 无农残", badge: "精选", unitPrice: 550, stockQty: 21, status: 1 },
  { id: 105, name: "胡萝卜", subtitle: "脆甜多汁 适合炖煮", badge: "", unitPrice: 399, stockQty: 30, status: 1 },
  { id: 106, name: "红颜草莓", subtitle: "酸甜可口 产地直发", badge: "精选", unitPrice: 2990, stockQty: 12, status: 1 }
];

function getStock(product) {
  return Number(product.stockQty || 0);
}

function getHotScore(product) {
  const badge = product.badge || "";
  const badgeScore = badge.includes("热") ? 1000 : badge.includes("精选") ? 700 : 0;
  const stockScore = Math.min(getStock(product), 99);
  const priceScore = Math.max(0, 2000 - Number(product.unitPrice || 0)) / 100;
  return badgeScore + stockScore + priceScore;
}

function toRankItem(product, index, type) {
  return {
    id: product.id,
    name: product.name,
    image: product.image,
    badge: product.badge || "",
    subtitle: type === "new" ? (product.badge || "今日上新") : (product.subtitle || "门店热搜"),
    rank: index + 1,
    topClass: index < 3 ? "rank-top" : ""
  };
}

function buildRankings(products) {
  const availableProducts = (products || []).filter((item) => Number(item.status || 1) === 1);
  const hotRanks = [...availableProducts]
    .sort((a, b) => getHotScore(b) - getHotScore(a))
    .slice(0, 6)
    .map((item, index) => toRankItem(item, index, "hot"));
  const newRanks = [...availableProducts]
    .sort((a, b) => {
      const aBadge = a.badge || "";
      const bBadge = b.badge || "";
      const aNew = aBadge.includes("新") ? 1 : 0;
      const bNew = bBadge.includes("新") ? 1 : 0;
      return bNew - aNew || Number(b.id || 0) - Number(a.id || 0);
    })
    .slice(0, 6)
    .map((item, index) => toRankItem(item, index, "new"));
  return { hotRanks, newRanks };
}

Page({
  data: {
    glassMode: false,
    loading: true,
    keyword: "",
    hotRanks: [],
    newRanks: [],
    recentKeywords: []
  },

  onLoad(options) {
    syncTheme(this);
    const keyword = decodeURIComponent(options.keyword || "");
    this.setData({
      keyword,
      recentKeywords: wx.getStorageSync("recentSearchKeywords") || [],
      ...buildRankings(FALLBACK_PRODUCTS)
    });
    this.loadRankings();
  },

  async loadRankings() {
    try {
      const products = await getProducts();
      this.setData(buildRankings(products.length ? products : FALLBACK_PRODUCTS));
    } catch {
      this.setData(buildRankings(FALLBACK_PRODUCTS));
    } finally {
      this.setData({ loading: false });
    }
  },

  goBack() {
    wx.navigateBack({ delta: 1 });
  },

  handleInput(event) {
    this.setData({ keyword: event.detail.value });
  },

  handleConfirm() {
    this.submitSearch(this.data.keyword);
  },

  handleKeyword(event) {
    this.submitSearch(event.currentTarget.dataset.keyword);
  },

  submitSearch(value) {
    const keyword = String(value || "").trim();
    if (!keyword) {
      wx.showToast({ title: "请输入搜索内容", icon: "none" });
      return;
    }

    const recentKeywords = [
      keyword,
      ...this.data.recentKeywords.filter((item) => item !== keyword)
    ].slice(0, 8);
    wx.setStorageSync("recentSearchKeywords", recentKeywords);
    wx.redirectTo({
      url: `/pages/search-result/index?keyword=${encodeURIComponent(keyword)}`
    });
  }
});
