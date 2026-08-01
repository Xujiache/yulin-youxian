'use strict';

const { FeedbackError } = require('./errors');

function parseEnvelope(payload, statusCode) {
  if (!payload || typeof payload !== 'object') {
    throw new FeedbackError('服务返回格式错误', {
      code: 'INVALID_RESPONSE',
      statusCode,
      retryable: Number(statusCode) >= 500,
    });
  }
  if (payload.code !== 0) {
    throw new FeedbackError(payload.message || '请求失败', {
      code: String(payload.code || 'BUSINESS_ERROR'),
      statusCode,
      requestId: payload.requestId,
      details: payload.data,
      retryable: Number(statusCode) >= 500,
    });
  }
  return payload.data;
}

function parseSocketEvent(payload) {
  if (!payload || typeof payload !== 'object') return null;
  if (!payload.eventId || !payload.type || !payload.occurredAt) return null;
  return payload;
}

module.exports = { parseEnvelope, parseSocketEvent };
