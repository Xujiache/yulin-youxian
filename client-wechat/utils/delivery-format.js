// 配送可视化展示格式化工具（契约见 docs/rider/04-API契约.md §三 tracking.eta）

function pad(value) {
  return value < 10 ? `0${value}` : String(value);
}

// 从 ISO-8601 本地时间字符串（如 "2026-08-11T15:45:00"）提取 "HH:mm"
function formatTimePart(value) {
  if (!value) {
    return "";
  }
  if (typeof value === "string") {
    const tIndex = value.indexOf("T");
    if (tIndex >= 0 && value.length >= tIndex + 6) {
      return value.slice(tIndex + 1, tIndex + 6);
    }
  }
  const date = value instanceof Date ? value : new Date(value);
  if (Number.isNaN(date.getTime())) {
    return "";
  }
  return `${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

// eta: { displayText?, lowerAt?, upperAt?, isRange? }
// 后端给了 displayText 就直接用；否则按区间/单点自行拼接
function formatEtaText(eta) {
  if (!eta) {
    return "";
  }
  if (eta.displayText) {
    return eta.displayText;
  }
  const lower = formatTimePart(eta.lowerAt);
  const upper = formatTimePart(eta.upperAt);
  if (eta.isRange && lower && upper && lower !== upper) {
    return `预计 ${lower}-${upper} 送达`;
  }
  const single = upper || lower;
  return single ? `预计 ${single} 送达` : "";
}

// 剩余秒数 -> "mm:ss"；负数表示已超时，显示 "已超时 mm:ss"
function formatRemaining(seconds) {
  const value = Number(seconds);
  if (!Number.isFinite(value)) {
    return "";
  }
  const overtime = value < 0;
  const total = Math.floor(Math.abs(value));
  const text = `${pad(Math.floor(total / 60))}:${pad(total % 60)}`;
  return overtime ? `已超时 ${text}` : text;
}

// 距离（米）-> "<1000 显示整数米，>=1000 显示一位小数公里"
function formatDistance(meters) {
  const value = Number(meters);
  if (!Number.isFinite(value) || value < 0) {
    return "";
  }
  if (value < 1000) {
    return `${Math.round(value)}米`;
  }
  return `${(value / 1000).toFixed(1)}公里`;
}

module.exports = {
  formatEtaText,
  formatRemaining,
  formatDistance
};
