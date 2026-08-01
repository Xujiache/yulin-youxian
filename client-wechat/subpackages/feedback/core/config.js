'use strict';

const CONFIG_STORAGE_KEY = '__feedback_platform_config_v1__';
const DEFAULTS = Object.freeze({
  feedbackBaseUrl: '',
  projectKey: '',
  subpackageRoot: '/subpackages/feedback',
  requestTimeout: 15000,
  uploadTimeout: 60000,
  theme: {
    primary: '#2563eb',
    primaryText: '#ffffff',
    pageBackground: '#f5f7fb',
    cardBackground: '#ffffff',
    text: '#172033',
    muted: '#667085',
    danger: '#d92d20',
  },
});

let memoryConfig = null;

function trimTrailingSlash(value) {
  return String(value || '').trim().replace(/\/+$/, '');
}

function validate(input) {
  const feedbackBaseUrl = trimTrailingSlash(input.feedbackBaseUrl);
  const projectKey = String(input.projectKey || '').trim();
  if (!/^https:\/\//i.test(feedbackBaseUrl) && !/^http:\/\/(127\.0\.0\.1|localhost)(:\d+)?$/i.test(feedbackBaseUrl)) {
    throw new Error('feedbackBaseUrl 必须是 HTTPS 地址（本地调试可用 localhost/127.0.0.1）');
  }
  if (!/^[A-Za-z0-9][A-Za-z0-9._-]{1,127}$/.test(projectKey)) {
    throw new Error('projectKey 格式无效');
  }
}

function normalize(options) {
  const input = Object.assign({}, DEFAULTS, options || {});
  validate(input);
  return {
    feedbackBaseUrl: trimTrailingSlash(input.feedbackBaseUrl),
    projectKey: String(input.projectKey).trim(),
    subpackageRoot: String(input.subpackageRoot || DEFAULTS.subpackageRoot).replace(/\/$/, ''),
    requestTimeout: Number(input.requestTimeout) || DEFAULTS.requestTimeout,
    uploadTimeout: Number(input.uploadTimeout) || DEFAULTS.uploadTimeout,
    theme: Object.assign({}, DEFAULTS.theme, input.theme || {}),
  };
}

function clone(value) {
  return Object.assign({}, value, { theme: Object.assign({}, value.theme) });
}

function configure(options) {
  memoryConfig = normalize(options);
  wx.setStorageSync(CONFIG_STORAGE_KEY, memoryConfig);
  return clone(memoryConfig);
}

function readHostConfig() {
  if (typeof getApp !== 'function') return null;
  try {
    const app = getApp();
    return app && app.globalData && app.globalData.feedbackConfig
      ? app.globalData.feedbackConfig
      : null;
  } catch (_) {
    return null;
  }
}

function getConfig() {
  // 微信主包不能 require 分包代码。宿主只在 App.globalData 暴露公开配置，
  // 分包首次加载时自行读取并持久化；配置变更时以宿主当前值为准。
  const hostConfig = readHostConfig();
  if (hostConfig) {
    const normalized = normalize(hostConfig);
    if (!memoryConfig || JSON.stringify(memoryConfig) !== JSON.stringify(normalized)) {
      memoryConfig = normalized;
      wx.setStorageSync(CONFIG_STORAGE_KEY, memoryConfig);
    }
  } else if (!memoryConfig) {
    memoryConfig = wx.getStorageSync(CONFIG_STORAGE_KEY) || null;
  }
  if (!memoryConfig) {
    throw new Error('反馈模块未配置，请在 App.globalData.feedbackConfig 提供公开配置');
  }
  validate(memoryConfig);
  return clone(memoryConfig);
}

function sessionStorageKey() {
  return `__feedback_platform_session_v1__:${getConfig().projectKey}`;
}

module.exports = { configure, getConfig, sessionStorageKey, CONFIG_STORAGE_KEY };
