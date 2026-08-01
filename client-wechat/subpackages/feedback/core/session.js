'use strict';

const config = require('./config');
const { FeedbackError } = require('./errors');
const { parseEnvelope } = require('./contract');

let loginPromise = null;
let refreshPromise = null;

function expiresInMilliseconds(value) {
  if (typeof value === 'number' && Number.isFinite(value)) return Math.max(0, value * 1000);
  const text = String(value || '').trim();
  if (/^\d+$/.test(text)) return Number(text) * 1000;
  const match = text.match(/^(\d+)(s|m|h|d)$/i);
  if (!match) return 0;
  const unit = { s: 1000, m: 60000, h: 3600000, d: 86400000 }[match[2].toLowerCase()];
  return Number(match[1]) * unit;
}

function getSession() {
  const value = wx.getStorageSync(config.sessionStorageKey());
  return value && value.accessToken ? value : null;
}

function saveSession(value) {
  const normalized = Object.assign({}, value, {
    savedAt: Date.now(),
    expiresAt: Date.now() + expiresInMilliseconds(value.expiresIn),
  });
  wx.setStorageSync(config.sessionStorageKey(), normalized);
  return normalized;
}

function clearSession() {
  wx.removeStorageSync(config.sessionStorageKey());
}

function wxLogin() {
  return new Promise((resolve, reject) => {
    wx.login({
      timeout: 10000,
      success: (result) => result.code ? resolve(result.code) : reject(new FeedbackError('微信登录未返回 code', { code: 'WX_LOGIN_FAILED' })),
      fail: (error) => reject(new FeedbackError(error.errMsg || '微信登录失败', { code: 'WX_LOGIN_FAILED', details: error })),
    });
  });
}

function rawRequest(path, body) {
  const cfg = config.getConfig();
  return new Promise((resolve, reject) => {
    wx.request({
      url: `${cfg.feedbackBaseUrl}${path}`,
      method: 'POST',
      data: body,
      timeout: cfg.requestTimeout,
      header: { 'content-type': 'application/json', 'x-client': 'feedback-wechat/1.0.0' },
      success: (response) => {
        try {
          if (response.statusCode < 200 || response.statusCode >= 300) {
            throw new FeedbackError((response.data && response.data.message) || '登录失败', {
              code: (response.data && response.data.code) || 'HTTP_ERROR',
              statusCode: response.statusCode,
              requestId: response.data && response.data.requestId,
            });
          }
          resolve(parseEnvelope(response.data, response.statusCode));
        } catch (error) { reject(error); }
      },
      fail: (error) => reject(new FeedbackError(error.errMsg || '网络请求失败', {
        code: /timeout/i.test(error.errMsg || '') ? 'TIMEOUT' : 'NETWORK_ERROR',
        retryable: true,
        details: error,
      })),
    });
  });
}

function login() {
  if (loginPromise) return loginPromise;
  loginPromise = wxLogin()
    .then((code) => rawRequest('/api/embed/auth/wechat', { projectKey: config.getConfig().projectKey, code }))
    .then(saveSession)
    .finally(() => { loginPromise = null; });
  return loginPromise;
}

function refresh() {
  if (refreshPromise) return refreshPromise;
  const current = getSession();
  if (!current || !current.refreshToken) return login();
  refreshPromise = rawRequest('/api/embed/auth/refresh', { refreshToken: current.refreshToken })
    .then((tokens) => saveSession(Object.assign({}, current, tokens)))
    .catch(() => {
      clearSession();
      return login();
    })
    .finally(() => { refreshPromise = null; });
  return refreshPromise;
}

function ensureSession() {
  const current = getSession();
  if (!current) return login();
  if (current.expiresAt && current.expiresAt - Date.now() < 60000) return refresh();
  return Promise.resolve(current);
}

function logout() {
  const current = getSession();
  const revoke = current && current.refreshToken
    ? rawRequest('/api/embed/auth/logout', { refreshToken: current.refreshToken }).catch(() => ({ revoked: false }))
    : Promise.resolve({ revoked: false });
  return revoke.finally(clearSession);
}

module.exports = { getSession, saveSession, clearSession, login, refresh, ensureSession, logout };
