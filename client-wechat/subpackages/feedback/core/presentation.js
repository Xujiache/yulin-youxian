'use strict';

const config = require('./config');

const STATUS = {
  SUBMITTED_PENDING_REVIEW: { label: '已提交待审核', tone: 'warning' },
  NEEDS_MORE_INFO: { label: '待补充', tone: 'danger' },
  APPROVED: { label: '审核已通过', tone: 'info' },
  IN_PROGRESS: { label: '开始修复', tone: 'primary' },
  RESOLVED: { label: '已修复完成', tone: 'success' },
  CLOSED: { label: '已关闭', tone: 'muted' },
};

const AI_STATUS = {
  QUEUED: { label: '排队中', tone: 'muted' },
  ANALYZING: { label: '分析中', tone: 'info' },
  LIKELY_VALID: { label: '初审有效', tone: 'success' },
  INSUFFICIENT_INFO: { label: '信息不足', tone: 'warning' },
  LIKELY_DUPLICATE: { label: '疑似重复', tone: 'warning' },
  LIKELY_INVALID: { label: '疑似无效', tone: 'danger' },
  CANCELLED: { label: '初审已取消', tone: 'muted' },
  FAILED: { label: '分析失败', tone: 'danger' },
};

function statusMeta(code) {
  return STATUS[code] || { label: code || '未知状态', tone: 'muted' };
}

function aiStatusMeta(code) {
  return AI_STATUS[code] || { label: code || '未知状态', tone: 'muted' };
}

function formatTime(value) {
  if (!value) return '';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return String(value);
  const pad = (number) => String(number).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

function decorateAttachments(items) {
  const baseUrl = config.getConfig().feedbackBaseUrl;
  return (Array.isArray(items) ? items : []).map((item) => {
    const relative = Boolean(item.url && item.url.startsWith('/'));
    const signed = Boolean(item.url && /\/signed\?token=/.test(item.url));
    return Object.assign({}, item, {
      url: relative ? `${baseUrl}${item.url}` : item.url,
      requiresAuth: relative && !signed,
    });
  });
}

function decorateCanonicalFeedback(item) {
  if (!item) return null;
  const meta = statusMeta(item.status);
  const messages = Array.isArray(item.messages) ? item.messages : [];
  return Object.assign({}, item, {
    resolution: item.resolution || ((messages.find((message) => message.type === 'RESOLUTION') || {}).content),
    statusLabel: meta.label,
    statusTone: meta.tone,
    resolvedAtText: formatTime(item.resolvedAt),
    updatedAtText: formatTime(item.updatedAt),
  });
}

function decorateFeedback(item) {
  const meta = statusMeta(item.status);
  const aiMeta = aiStatusMeta(item.aiStatus);
  const messages = Array.isArray(item.messages) ? item.messages : [];
  const resolution = item.resolution || ((messages.find((message) => message.type === 'RESOLUTION') || {}).content);
  return Object.assign({}, item, {
    attachments: decorateAttachments(item.attachments),
    messages,
    resolution,
    canonicalFeedback: decorateCanonicalFeedback(item.duplicateRelation && item.duplicateRelation.canonicalFeedback),
    statusLabel: meta.label,
    statusTone: meta.tone,
    aiStatusLabel: aiMeta.label,
    aiStatusTone: aiMeta.tone,
    createdAtText: formatTime(item.createdAt),
    resolvedAtText: formatTime(item.resolvedAt)
  });
}

function decorateMessage(item) {
  const senderLabels = { ADMIN: '管理员', CLIENT: '客户', EMBEDDED: '提交者', AI_SYSTEM: 'AI 初审' };
  return Object.assign({}, item, { senderLabel: senderLabels[item.senderType] || '系统', createdAtText: formatTime(item.createdAt) });
}

module.exports = { statusMeta, aiStatusMeta, formatTime, decorateFeedback, decorateMessage, decorateAttachments, decorateCanonicalFeedback };
