const SHARE_IMAGES = {
  home: "/assets/share/share-home.jpg",
  category: "/assets/share/share-category.jpg",
  store: "/assets/share/share-store.jpg"
};

function enableShareToFriend() {
  if (typeof wx.showShareMenu !== "function") {
    return;
  }
  wx.showShareMenu({
    menus: ["shareAppMessage"],
    fail() {}
  });
}

function homeShare(storeName) {
  return {
    title: `${storeName || "禹邻优鲜"}｜今日新鲜，门店直送`,
    path: "/pages/home/index",
    imageUrl: SHARE_IMAGES.home
  };
}

function categoryShare(category) {
  const id = Number(category && category.id) || 0;
  const name = String((category && category.name) || "新鲜果蔬").trim();
  return {
    title: `禹邻优鲜｜${name}新鲜到家`,
    path: id ? `/pages/category/index?categoryId=${id}` : "/pages/category/index",
    imageUrl: SHARE_IMAGES.category
  };
}

function storeShare(storeName) {
  return {
    title: `${storeName || "禹邻优鲜"}｜家门口的新鲜菜篮子`,
    path: "/pages/home/index",
    imageUrl: SHARE_IMAGES.store
  };
}

module.exports = {
  enableShareToFriend,
  homeShare,
  categoryShare,
  storeShare
};
