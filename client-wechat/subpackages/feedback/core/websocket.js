'use strict';

const config = require('./config');
const api = require('./api');
const session = require('./session');
const events = require('./events');
const { parseSocketEvent } = require('./contract');

const MAX_BACKOFF = 30000;
const MAX_EVENT_IDS = 200;
const HEARTBEAT_INTERVAL = 35000;
const PONG_TIMEOUT = 10000;
let socketTask = null;
let state = 'idle';
let foreground = true;
let online = true;
let manualClose = false;
let attempt = 0;
let reconnectTimer = null;
let heartbeatTimer = null;
let pongTimer = null;
let networkBound = false;
let connectionEpoch = 0;
const eventIds = new Set();
const eventOrder = [];

function rememberEvent(id) {
  if (eventIds.has(id)) return false;
  eventIds.add(id);
  eventOrder.push(id);
  if (eventOrder.length > MAX_EVENT_IDS) eventIds.delete(eventOrder.shift());
  return true;
}

function socketUrl(ticket) {
  const cfg = config.getConfig();
  const raw = ticket.wsUrl && /^wss?:\/\//i.test(ticket.wsUrl)
    ? ticket.wsUrl
    : `${cfg.feedbackBaseUrl.replace(/^http/i, 'ws')}${ticket.wsUrl || '/ws'}`;
  return `${raw}${raw.includes('?') ? '&' : '?'}ticket=${encodeURIComponent(ticket.ticket)}`;
}

function clearReconnect() {
  if (reconnectTimer) clearTimeout(reconnectTimer);
  reconnectTimer = null;
}

function clearHeartbeat() {
  if (heartbeatTimer) clearInterval(heartbeatTimer);
  heartbeatTimer = null;
  if (pongTimer) clearTimeout(pongTimer);
  pongTimer = null;
}

function failSocket(task, reason, error) {
  if (task !== socketTask) return;
  socketTask = null;
  clearHeartbeat();
  state = 'closed';
  if (error) events.emit('socket-state', { state: 'error', error });
  try { task.close({ code: 4001, reason }); } catch (_) { /* SocketTask 可能已关闭 */ }
  scheduleReconnect(reason);
}

function startHeartbeat(task) {
  clearHeartbeat();
  heartbeatTimer = setInterval(() => {
    if (task !== socketTask || state !== 'open') return;
    try {
      if (pongTimer) clearTimeout(pongTimer);
      pongTimer = setTimeout(() => {
        pongTimer = null;
        failSocket(task, 'pong-timeout', new Error('WebSocket 心跳超时'));
      }, PONG_TIMEOUT);
      task.send({
        data: JSON.stringify({ type: 'ping' }),
        fail: (error) => failSocket(task, 'heartbeat-failed', error),
      });
    } catch (error) {
      failSocket(task, 'heartbeat-failed', error);
    }
  }, HEARTBEAT_INTERVAL);
}

function scheduleReconnect(reason) {
  if (manualClose || !foreground || !online || reconnectTimer) return;
  const base = Math.min(MAX_BACKOFF, 1000 * (2 ** Math.min(attempt, 5)));
  const delay = Math.round(base * (0.8 + Math.random() * 0.4));
  attempt += 1;
  state = 'waiting';
  reconnectTimer = setTimeout(() => {
    reconnectTimer = null;
    connect(true);
  }, delay);
  events.emit('socket-state', { state, reason, retryIn: delay });
}

function bindNetwork() {
  if (networkBound || typeof wx.onNetworkStatusChange !== 'function') return;
  networkBound = true;
  wx.onNetworkStatusChange((result) => {
    const wasOffline = !online;
    online = result.isConnected !== false;
    if (online && wasOffline && foreground) {
      events.emit('compensate', { reason: 'network-restored' });
      connect(true);
    }
  });
}

function attach(task, reconnecting) {
  task.onOpen(() => {
    if (task !== socketTask) return;
    const shouldCompensate = reconnecting || attempt > 0;
    state = 'open';
    attempt = 0;
    startHeartbeat(task);
    events.emit('socket-state', { state });
    if (shouldCompensate) events.emit('compensate', { reason: 'socket-reconnected' });
  });
  task.onMessage((message) => {
    let payload;
    try { payload = JSON.parse(message.data); } catch (_) { return; }
    const event = parseSocketEvent(payload);
    if (!event || !rememberEvent(event.eventId)) return;
    if (event.type === 'pong') {
      if (pongTimer) clearTimeout(pongTimer);
      pongTimer = null;
      return;
    }
    events.emit('socket-event', event);
    events.emit(`socket-event:${event.type}`, event);
  });
  task.onError((error) => {
    failSocket(task, 'socket-error', error);
  });
  task.onClose((result) => {
    if (task !== socketTask) return;
    socketTask = null;
    clearHeartbeat();
    state = 'closed';
    events.emit('socket-state', { state, result });
    scheduleReconnect('socket-closed');
  });
}

function connect(reconnecting) {
  bindNetwork();
  manualClose = false;
  if (!foreground || !online || socketTask || state === 'ticket') return Promise.resolve();
  const epoch = ++connectionEpoch;
  state = 'ticket';
  return session.ensureSession()
    .then(() => api.createSocketTicket())
    .then((ticket) => {
      if (epoch !== connectionEpoch || !foreground || manualClose) return;
      const task = wx.connectSocket({ url: socketUrl(ticket), timeout: 10000 });
      socketTask = task;
      state = 'connecting';
      attach(task, Boolean(reconnecting));
    })
    .catch((error) => {
      if (epoch !== connectionEpoch) return;
      state = 'error';
      events.emit('socket-state', { state, error });
      scheduleReconnect('ticket-failed');
    });
}

function disconnect() {
  connectionEpoch += 1;
  manualClose = true;
  clearReconnect();
  clearHeartbeat();
  if (socketTask) socketTask.close({ code: 1000, reason: 'feedback-module-suspended' });
  socketTask = null;
  state = 'idle';
}

function setForeground(value) {
  const wasForeground = foreground;
  foreground = Boolean(value);
  if (foreground) {
    manualClose = false;
    if (!wasForeground) events.emit('compensate', { reason: 'app-foreground' });
    connect(true);
  } else {
    disconnect();
  }
}

module.exports = { connect, disconnect, setForeground, getState: () => state };
