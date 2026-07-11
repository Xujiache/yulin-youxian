const STORAGE_KEY = "glassMode";

function isGlassModeEnabled() {
  return Boolean(wx.getStorageSync(STORAGE_KEY));
}

function syncTheme(page) {
  if (!page || typeof page.setData !== "function") {
    return;
  }
  page.setData({ glassMode: isGlassModeEnabled() });
}

function setGlassMode(enabled) {
  const value = Boolean(enabled);
  wx.setStorageSync(STORAGE_KEY, value);
  getCurrentPages().forEach((page) => {
    if (page && typeof page.setData === "function" && page.data && Object.prototype.hasOwnProperty.call(page.data, "glassMode")) {
      page.setData({ glassMode: value });
    }
  });
  return value;
}

module.exports = {
  isGlassModeEnabled,
  setGlassMode,
  syncTheme
};
