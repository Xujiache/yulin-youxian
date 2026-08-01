'use strict';

const { uploadImage } = require('./upload');

function clone(items) {
  return (items || []).map((item) => Object.assign({}, item));
}

function update(items, localId, patch, notify) {
  const next = clone(items).map((item) => item.localId === localId ? Object.assign({}, item, patch) : item);
  notify(next);
  return next;
}

function uploadAll(initialItems, notify) {
  let latest = clone(initialItems);
  const candidates = latest.filter((item) => item.state !== 'uploaded');
  const tasks = candidates.map((candidate) => {
    latest = update(latest, candidate.localId, { state: 'uploading', progress: 0, error: '' }, notify);
    return uploadImage(candidate, (progress) => {
      latest = update(latest, candidate.localId, { progress }, notify);
    }).then((attachment) => {
      latest = update(latest, candidate.localId, {
        state: 'uploaded',
        progress: 100,
        attachmentId: attachment.id,
        error: '',
      }, notify);
      return { ok: true };
    }).catch((error) => {
      latest = update(latest, candidate.localId, {
        state: 'failed',
        progress: 0,
        attachmentId: '',
        error: error.message || '上传失败',
      }, notify);
      return { ok: false, error };
    });
  });
  return Promise.all(tasks).then((results) => {
    const failed = results.find((item) => !item.ok);
    if (failed) throw failed.error;
    return latest;
  });
}

function uploadOne(initialItems, index, notify) {
  const target = initialItems[index];
  if (!target) return Promise.resolve(clone(initialItems));
  const reset = clone(initialItems);
  reset[index] = Object.assign({}, reset[index], { state: 'pending', error: '', attachmentId: '' });
  return uploadAll(reset.filter((_, itemIndex) => itemIndex === index), (single) => {
    const next = clone(reset);
    next[index] = single[0];
    notify(next);
  }).then((single) => {
    const next = clone(reset);
    next[index] = single[0];
    return next;
  });
}

module.exports = { uploadAll, uploadOne };
