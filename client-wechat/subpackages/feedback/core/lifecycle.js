'use strict';

const websocket = require('./websocket');
const session = require('./session');

function onAppShow() {
  // 未进入过反馈模块时不主动触发 wx.login；首次会话由反馈页面建立。
  if (session.getSession()) websocket.setForeground(true);
}

function onAppHide() {
  websocket.setForeground(false);
}

module.exports = { onAppShow, onAppHide };
