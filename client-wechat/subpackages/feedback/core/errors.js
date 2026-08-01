'use strict';

class FeedbackError extends Error {
  constructor(message, options) {
    super(message || '请求失败');
    this.name = 'FeedbackError';
    const details = options || {};
    this.code = details.code || 'UNKNOWN_ERROR';
    this.statusCode = Number(details.statusCode) || 0;
    this.requestId = details.requestId || '';
    this.retryable = Boolean(details.retryable);
    this.details = details.details;
  }
}

function userMessage(error) {
  if (!error) return '操作失败，请稍后重试';
  if (error.code === 'NETWORK_ERROR') return '网络连接失败，请检查网络后重试';
  if (error.code === 'TIMEOUT') return '请求超时，请稍后重试';
  if (error.code === 'SESSION_EXPIRED') return '反馈会话已过期，请重新进入';
  return error.message || '操作失败，请稍后重试';
}

module.exports = { FeedbackError, userMessage };
