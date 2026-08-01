'use strict';

const config = require('./core/config');
const lifecycle = require('./core/lifecycle');
const session = require('./core/session');
const websocket = require('./core/websocket');

/**
 * 仅供分包内部调试/维护使用。微信主包不得 require 此入口；
 * 宿主只在 App.globalData.feedbackConfig 提供公开配置。
 */
function configure(options) {
  return config.configure(options);
}

function logout() {
  websocket.disconnect();
  return session.logout();
}

module.exports = {
  configure,
  ensureSession: session.ensureSession,
  logout,
  clearSession: session.clearSession,
  onAppShow: lifecycle.onAppShow,
  onAppHide: lifecycle.onAppHide,
};
