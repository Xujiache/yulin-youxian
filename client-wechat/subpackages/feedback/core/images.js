'use strict';

const ALLOWED_EXTENSIONS = ['jpg', 'jpeg', 'png', 'webp'];
const ALLOWED_MIME = ['image/jpeg', 'image/png', 'image/webp'];
const MAX_IMAGES = 6;
const MAX_IMAGE_BYTES = 10 * 1024 * 1024;

function extension(path) {
  const clean = String(path || '').split('?')[0].toLowerCase();
  const match = clean.match(/\.([a-z0-9]+)$/);
  return match ? match[1] : '';
}

function validateImage(file) {
  const ext = extension(file.tempFilePath || file.path);
  const mime = String(file.fileType || file.type || '').toLowerCase();
  if (!ALLOWED_EXTENSIONS.includes(ext) && !ALLOWED_MIME.includes(mime)) {
    throw new Error('仅支持 JPEG、PNG、WebP 图片');
  }
  if (Number(file.size) > MAX_IMAGE_BYTES) throw new Error('单张图片不能超过 10MB');
  return true;
}

function chooseImages(remaining) {
  const requested = Number(remaining);
  const count = Number.isFinite(requested) ? Math.max(0, Math.min(MAX_IMAGES, requested)) : MAX_IMAGES;
  if (!count) return Promise.resolve([]);
  return new Promise((resolve, reject) => {
    const method = typeof wx.chooseMedia === 'function'
      ? 'chooseMedia'
      : (typeof wx.chooseImage === 'function' ? 'chooseImage' : '');
    if (!method) {
      reject(new Error('当前微信版本不支持选择图片，请升级微信后重试'));
      return;
    }
    const options = {
      count,
      sourceType: ['album', 'camera'],
      sizeType: ['compressed'],
      success: (result) => {
        try {
          const selected = result.tempFiles || (result.tempFilePaths || []).map((path) => ({ tempFilePath: path }));
          const files = selected.map((file, index) => {
            validateImage(file);
            return {
              localId: `${Date.now()}-${index}-${Math.random().toString(36).slice(2, 8)}`,
              path: file.tempFilePath || file.path,
              size: Number(file.size) || 0,
              state: 'pending',
              progress: 0,
              attachmentId: '',
              error: '',
            };
          });
          resolve(files);
        } catch (error) { reject(error); }
      },
      fail: (error) => {
        if (/cancel/i.test(error.errMsg || '')) resolve([]);
        else reject(error);
      },
    };
    if (method === 'chooseMedia') options.mediaType = ['image'];
    wx[method](options);
  });
}

module.exports = { MAX_IMAGES, MAX_IMAGE_BYTES, ALLOWED_EXTENSIONS, ALLOWED_MIME, chooseImages, validateImage };
