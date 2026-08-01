'use strict';

const listeners = new Map();

function on(type, listener) {
  if (!listeners.has(type)) listeners.set(type, new Set());
  listeners.get(type).add(listener);
  return () => off(type, listener);
}

function off(type, listener) {
  const set = listeners.get(type);
  if (set) set.delete(listener);
}

function emit(type, payload) {
  const set = listeners.get(type);
  if (!set) return;
  Array.from(set).forEach((listener) => {
    try { listener(payload); } catch (_) { /* 页面销毁期间忽略监听器异常 */ }
  });
}

module.exports = { on, off, emit };
