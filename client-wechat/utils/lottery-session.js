const STORAGE_KEY = "luckyActivitySession";
const MAX_SESSION_AGE = 6 * 60 * 60 * 1000;

function isUsableSession(session) {
  if (!session || !Number(session.orderId) || !session.source) {
    return false;
  }
  const createdAt = Number(session.createdAt || 0);
  return !createdAt || Date.now() - createdAt <= MAX_SESSION_AGE;
}

function readLotterySession() {
  try {
    const session = wx.getStorageSync(STORAGE_KEY);
    if (isUsableSession(session)) {
      return session;
    }
    if (session) {
      wx.removeStorageSync(STORAGE_KEY);
    }
  } catch {}
  return null;
}

function writeLotterySession(session = {}) {
  const nextSession = {
    ...session,
    orderId: Number(session.orderId || 0),
    createdAt: Number(session.createdAt || Date.now()),
    updatedAt: Date.now()
  };
  try {
    wx.setStorageSync(STORAGE_KEY, nextSession);
  } catch {}
  return nextSession;
}

function updateLotterySession(patch = {}) {
  const current = readLotterySession();
  if (!current) {
    return null;
  }
  return writeLotterySession({
    ...current,
    ...patch,
    createdAt: current.createdAt
  });
}

function clearLotterySession(orderId, source) {
  const current = readLotterySession();
  if (!current) {
    return;
  }
  if (orderId && Number(current.orderId) !== Number(orderId)) {
    return;
  }
  if (source && current.source !== source) {
    return;
  }
  try {
    wx.removeStorageSync(STORAGE_KEY);
  } catch {}
}

module.exports = {
  clearLotterySession,
  readLotterySession,
  updateLotterySession,
  writeLotterySession
};
