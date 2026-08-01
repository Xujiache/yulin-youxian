'use strict';

const config = require('./config');
const session = require('./session');

function download(item, retryAuth) {
  if (!item || !item.url || !item.requiresAuth) return Promise.resolve(item);
  return session.ensureSession().then((active) => new Promise((resolve, reject) => {
    wx.downloadFile({
      url: item.url,
      timeout: config.getConfig().uploadTimeout,
      header: { Authorization: `Bearer ${active.accessToken}` },
      success: (result) => {
        if (result.statusCode === 401 && retryAuth !== false) {
          session.refresh().then(() => download(item, false)).then(resolve, reject);
        } else if (result.statusCode >= 200 && result.statusCode < 300) {
          resolve(Object.assign({}, item, { remoteUrl: item.url, url: result.tempFilePath, requiresAuth: false }));
        } else reject(new Error(`截图读取失败（${result.statusCode}）`));
      },
      fail: reject
    });
  }));
}

function hydrateAttachments(items) {
  return Promise.all((items || []).map((item) => download(item, true).catch((error) => Object.assign({}, item, { url: '', imageError: error.message || '截图读取失败' }))));
}

module.exports = { hydrateAttachments };
