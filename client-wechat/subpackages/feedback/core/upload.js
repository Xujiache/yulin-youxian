'use strict';

const config = require('./config');
const session = require('./session');
const api = require('./api');
const { FeedbackError } = require('./errors');
const { parseEnvelope } = require('./contract');

function uploadImage(item, onProgress, allowRefresh) {
  return session.ensureSession().then((activeSession) => new Promise((resolve, reject) => {
    const cfg = config.getConfig();
    const task = wx.uploadFile({
      url: `${cfg.feedbackBaseUrl}/api/files/images`,
      filePath: item.path,
      name: 'file',
      timeout: cfg.uploadTimeout,
      formData: { projectId: api.currentProjectId() },
      header: {
        Authorization: `Bearer ${activeSession.accessToken}`,
        'x-client': 'feedback-wechat/1.0.0',
      },
      success: (response) => {
        let body;
        try { body = typeof response.data === 'string' ? JSON.parse(response.data) : response.data; }
        catch (_) { reject(new FeedbackError('上传响应格式错误', { code: 'INVALID_RESPONSE' })); return; }
        if (response.statusCode === 401 && allowRefresh !== false) {
          session.refresh().then(() => uploadImage(item, onProgress, false)).then(resolve, reject);
          return;
        }
        try {
          if (response.statusCode < 200 || response.statusCode >= 300) {
            throw new FeedbackError(body.message || '图片上传失败', {
              code: body.code || 'UPLOAD_FAILED',
              statusCode: response.statusCode,
              requestId: body.requestId,
              retryable: response.statusCode >= 500,
            });
          }
          resolve(parseEnvelope(body, response.statusCode));
        } catch (error) { reject(error); }
      },
      fail: (error) => reject(new FeedbackError(error.errMsg || '图片上传失败', {
        code: /timeout/i.test(error.errMsg || '') ? 'TIMEOUT' : 'UPLOAD_FAILED',
        retryable: true,
        details: error,
      })),
    });
    if (task && typeof task.onProgressUpdate === 'function') {
      task.onProgressUpdate((event) => onProgress && onProgress(Math.max(0, Math.min(100, event.progress || 0))));
    }
  }));
}

module.exports = { uploadImage };
