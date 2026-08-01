'use strict';

function promisifyWx(method, options) {
  return new Promise((resolve) => {
    if (typeof wx[method] !== 'function') {
      resolve(null);
      return;
    }
    wx[method](Object.assign({}, options || {}, { success: resolve, fail: () => resolve(null) }));
  });
}

function currentPageContext() {
  try {
    const pages = getCurrentPages();
    const page = pages[pages.length - 1];
    return page ? { route: page.route || '' } : null;
  } catch (_) {
    return null;
  }
}

function accountInfo() {
  try { return typeof wx.getAccountInfoSync === 'function' ? wx.getAccountInfoSync() : null; }
  catch (_) { return null; }
}

function collectDeviceContext(extra) {
  const systemPromise = typeof wx.getSystemInfoAsync === 'function'
    ? promisifyWx('getSystemInfoAsync')
    : promisifyWx('getSystemInfo');
  return Promise.all([
    systemPromise,
    promisifyWx('getNetworkType'),
    Promise.resolve(accountInfo()),
  ]).then(([system, network, account]) => {
    const miniProgram = account && account.miniProgram ? account.miniProgram : {};
    const page = currentPageContext();
    return {
      platform: system && system.platform,
      system: system && system.system,
      model: system && system.model,
      brand: system && system.brand,
      wechatVersion: system && system.version,
      sdkVersion: system && system.SDKVersion,
      appVersion: miniProgram.version || '',
      networkType: network && network.networkType,
      screenWidth: system && system.screenWidth,
      screenHeight: system && system.screenHeight,
      pixelRatio: system && system.pixelRatio,
      pageRoute: page && page.route,
      extra: {
        capturedAt: new Date().toISOString(),
        language: system && system.language,
        signalStrength: network && network.signalStrength,
        miniProgramAppId: miniProgram.appId || '',
        envVersion: miniProgram.envVersion || '',
        moduleVersion: '1.0.0',
        source: extra && extra.source,
      }
    };
  });
}

module.exports = { collectDeviceContext, currentPageContext };
