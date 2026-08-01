function currentRoute() {
  const pages = getCurrentPages();
  const current = pages[pages.length - 1];
  if (!current) {
    return "/pages/home/index";
  }
  const query = current.options || {};
  const queryString = Object.keys(query)
    .map((key) => `${key}=${encodeURIComponent(query[key])}`)
    .join("&");
  return `/${current.route}${queryString ? `?${queryString}` : ""}`;
}

function loginUrl(redirect) {
  return `/pages/login/index?redirect=${encodeURIComponent(redirect || currentRoute())}`;
}

function setupUrl(redirect, mode) {
  const params = [`redirect=${encodeURIComponent(redirect || currentRoute())}`];
  if (mode) {
    params.push(`mode=${encodeURIComponent(mode)}`);
  }
  return `/pages/profile-setup/index?${params.join("&")}`;
}

function requireLogin(redirect) {
  const app = getApp();
  if (app.isLoggedIn && app.isLoggedIn()) {
    return true;
  }
  wx.navigateTo({ url: loginUrl(redirect) });
  return false;
}

function requireCompleteProfile(redirect) {
  if (!requireLogin(redirect)) {
    return false;
  }
  const app = getApp();
  const profile = app.globalData.user || wx.getStorageSync("userProfile") || {};
  if (profile.profileCompleted === false) {
    wx.navigateTo({ url: setupUrl(redirect) });
    return false;
  }
  return true;
}

module.exports = {
  currentRoute,
  loginUrl,
  setupUrl,
  requireLogin,
  requireCompleteProfile
};
