'use strict';

const config = require('./config');
const session = require('./session');
const { FeedbackError } = require('./errors');
const { parseEnvelope } = require('./contract');

function encodeQuery(query) {
  return Object.keys(query || {})
    .filter((key) => query[key] !== undefined && query[key] !== null && query[key] !== '')
    .map((key) => `${encodeURIComponent(key)}=${encodeURIComponent(query[key])}`)
    .join('&');
}

function request(options) {
  const settings = Object.assign({ method: 'GET', auth: true, retryAuth: true }, options || {});
  return (settings.auth ? session.ensureSession() : Promise.resolve(null)).then((activeSession) => {
    const cfg = config.getConfig();
    const query = encodeQuery(settings.query);
    const url = `${cfg.feedbackBaseUrl}${settings.path}${query ? `?${query}` : ''}`;
    return new Promise((resolve, reject) => {
      wx.request({
        url,
        method: settings.method,
        data: settings.data,
        timeout: settings.timeout || cfg.requestTimeout,
        header: Object.assign(
          { 'content-type': 'application/json', 'x-client': 'feedback-wechat/1.0.0' },
          activeSession ? { Authorization: `Bearer ${activeSession.accessToken}` } : {},
          settings.header || {},
        ),
        success: (response) => {
          if (response.statusCode === 401 && settings.auth && settings.retryAuth) {
            session.refresh().then(() => request(Object.assign({}, settings, { retryAuth: false }))).then(resolve, reject);
            return;
          }
          try {
            if (response.statusCode < 200 || response.statusCode >= 300) {
              throw new FeedbackError((response.data && response.data.message) || `请求失败（${response.statusCode}）`, {
                code: (response.data && response.data.code) || 'HTTP_ERROR',
                statusCode: response.statusCode,
                requestId: response.data && response.data.requestId,
                details: response.data && (response.data.details !== undefined ? response.data.details : response.data.data),
                retryable: response.statusCode >= 500,
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
  });
}

module.exports = { request };
