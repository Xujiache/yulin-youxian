// 配送追踪响应的归一化（契约见 docs/rider/04-API契约.md §三）
// 后端任何一层字段缺失都不能让页面崩，所以这里统一做兜底，页面只消费归一化后的结构。
const { formatEtaText, formatRemaining, formatDistance } = require("../../utils/delivery-format");

const TIMELINE_TEMPLATE = [
  { code: "ASSIGNED", label: "已安排骑手" },
  { code: "PICKED_UP", label: "骑手已取货" },
  { code: "DELIVERING", label: "配送中" },
  { code: "ARRIVED", label: "即将送达" },
  { code: "DELIVERED", label: "已送达" }
];

const TERMINAL_STATUSES = ["DELIVERED", "RETURNED", "CANCELLED", "CANCELED", "FAILED", "CLOSED"];

const VEHICLE_TEXT = {
  EBIKE: "电动车配送",
  ELECTRIC_BIKE: "电动车配送",
  MOTORCYCLE: "摩托车配送",
  BIKE: "自行车配送",
  BICYCLE: "自行车配送",
  CAR: "汽车配送",
  WALK: "步行配送"
};

const RATING_TAGS = ["准时", "礼貌", "包装完好", "态度好"];

const DEFAULT_AVATAR = "/assets/products/avatar.png";
// GPS 漂移保护：骑手与目的地相距超过此值时按定位失效处理（远单是正常的，阈值取得足够宽）
const MAX_REASONABLE_METERS = 200000;
const MIN_POLLING_INTERVAL_MS = 1000;
const MAX_POLLING_INTERVAL_MS = 60000;

const EMPTY_DELIVERY = {
  available: false,
  terminal: false,
  statusText: "",
  etaText: "",
  remainingText: "",
  distanceText: "",
  stopsAheadText: "",
  noticeText: "",
  phoneWarningText: "",
  waitingText: "",
  staleText: "",
  hasRider: false,
  rider: null,
  destination: null,
  store: null,
  showMap: false,
  timeline: [],
  subscribeTemplateIds: [],
  subscribed: false,
  rating: null,
  pollingIntervalMs: 0
};

function toFiniteNumber(value) {
  if (value === null || value === undefined || value === "") {
    return null;
  }
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : null;
}

function noticeText(value) {
  if (typeof value === "string") {
    return value.trim();
  }
  if (!value || typeof value !== "object") {
    return "";
  }
  const candidate = value.message || value.text || value.content || value.notice;
  return typeof candidate === "string" ? candidate.trim() : "";
}

function pollingIntervalMs(raw, fallback = 0) {
  const polling = raw && typeof raw.polling === "object" ? raw.polling : {};
  const seconds = toFiniteNumber(polling.intervalSeconds);
  if (seconds === null || seconds <= 0) {
    return fallback;
  }
  return Math.max(
    MIN_POLLING_INTERVAL_MS,
    Math.min(MAX_POLLING_INTERVAL_MS, Math.round(seconds * 1000))
  );
}

function shouldContinueUnavailableTracking(orderStatus, raw) {
  return String(orderStatus || "").trim() === "配送中"
    && Boolean(raw && typeof raw === "object" && raw.hasDelivery === false);
}

function trackingPollDelayMs(orderStatus, raw, fallback = 5000, backoff = 15000) {
  const requested = pollingIntervalMs(raw, fallback);
  return shouldContinueUnavailableTracking(orderStatus, raw)
    ? Math.max(requested, backoff)
    : requested;
}

function markerAnimationDuration(intervalMs, maximum = 4800) {
  const interval = toFiniteNumber(intervalMs);
  const maxDuration = Math.max(0, toFiniteNumber(maximum) || 0);
  if (interval === null || interval <= 0) {
    return maxDuration;
  }
  return Math.min(maxDuration, Math.max(300, Math.round(interval) - 200));
}

// 兼容 { lat, lng } 与 { latitude, longitude } 两种写法
function toPoint(raw) {
  if (!raw || typeof raw !== "object") {
    return null;
  }
  const lat = toFiniteNumber(raw.lat !== undefined ? raw.lat : raw.latitude);
  const lng = toFiniteNumber(raw.lng !== undefined ? raw.lng : raw.longitude);
  if (lat === null || lng === null) {
    return null;
  }
  if (lat === 0 && lng === 0) {
    return null;
  }
  if (lat < -90 || lat > 90 || lng < -180 || lng > 180) {
    return null;
  }
  return { lat, lng };
}

function roughDistanceMeters(from, to) {
  if (!from || !to) {
    return null;
  }
  const radian = Math.PI / 180;
  const meanLat = ((from.lat + to.lat) / 2) * radian;
  const dx = (to.lng - from.lng) * radian * Math.cos(meanLat);
  const dy = (to.lat - from.lat) * radian;
  return Math.sqrt(dx * dx + dy * dy) * 6371000;
}

function isTerminalStatus(status) {
  return TERMINAL_STATUSES.includes(String(status || "").toUpperCase());
}

function timeText(value) {
  if (!value || typeof value !== "string") {
    return "";
  }
  const index = value.indexOf("T");
  if (index >= 0 && value.length >= index + 6) {
    return value.slice(index + 1, index + 6);
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return "";
  }
  const pad = (number) => String(number).padStart(2, "0");
  return `${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

// 始终渲染五个节点：后端返回的节点覆盖模板，未返回的保持未完成
function buildTimeline(list) {
  const source = Array.isArray(list) ? list.filter((item) => item && typeof item === "object") : [];
  const matched = TIMELINE_TEMPLATE.map((template) => {
    const hit = source.find((item) => String(item.code || "").toUpperCase() === template.code);
    return {
      code: template.code,
      label: (hit && hit.label) || template.label,
      timeText: hit ? timeText(hit.at) : "",
      done: Boolean(hit && hit.done)
    };
  });
  const extras = source
    .filter((item) => !TIMELINE_TEMPLATE.some((template) => template.code === String(item.code || "").toUpperCase()))
    .map((item, index) => ({
      code: String(item.code || `EXTRA_${index}`),
      label: item.label || "配送进度",
      timeText: timeText(item.at),
      done: Boolean(item.done)
    }));
  return matched.concat(extras);
}

function normalizeRider(raw, destination, trackingDegraded) {
  if (!raw || typeof raw !== "object") {
    return null;
  }
  const point = toPoint(raw.location);
  const drifted = point && destination ? roughDistanceMeters(point, destination) > MAX_REASONABLE_METERS : false;
  const ratingStar = toFiniteNumber(raw.ratingStar);
  const bearing = toFiniteNumber(raw.bearing);
  const callNumber = raw.callNumber ? String(raw.callNumber) : "";
  return {
    name: raw.name || "配送骑手",
    avatarUrl: raw.avatarUrl || DEFAULT_AVATAR,
    vehicleText: VEHICLE_TEXT[String(raw.vehicleType || "").toUpperCase()] || "配送中",
    ratingText: ratingStar === null ? "" : ratingStar.toFixed(1),
    starPercent: ratingStar === null ? 0 : Math.round(Math.max(0, Math.min(100, (ratingStar / 5) * 100)) * 10) / 10,
    callNumber,
    phoneDegraded: Boolean(raw.phoneDegraded || raw.degraded || trackingDegraded),
    point: drifted ? null : point,
    bearing: bearing === null ? 0 : ((bearing % 360) + 360) % 360,
    // 后端给了坐标但被判为漂移：不渲染，按定位陈旧处理，而不是当成「还没取货」
    locationRejected: Boolean(drifted),
    fresh: drifted ? false : raw.locationFresh !== false,
    locatedAtText: timeText(raw.locatedAt)
  };
}

function pickTemplateIds(raw) {
  const groups = [
    raw.subscribeTemplateIds,
    raw.templateIds,
    raw.subscribe && raw.subscribe.templateIds,
    raw.notification && raw.notification.templateIds
  ];
  for (let index = 0; index < groups.length; index += 1) {
    const group = groups[index];
    if (Array.isArray(group)) {
      const ids = group.filter((id) => typeof id === "string" && id.trim()).map((id) => id.trim());
      if (ids.length) {
        // wx.requestSubscribeMessage 单次最多 3 个模板
        return ids.slice(0, 3);
      }
    }
  }
  return [];
}

function normalizeRating(raw) {
  const source = raw.rating || raw.ratingInfo || null;
  if (!source || typeof source !== "object") {
    return { rated: Boolean(raw.rated), star: 0, tags: [], comment: "", timeText: "" };
  }
  const star = toFiniteNumber(source.star);
  return {
    rated: Boolean(raw.rated || source.rated || star),
    star: star === null ? 0 : Math.max(0, Math.min(5, Math.round(star))),
    tags: Array.isArray(source.tags) ? source.tags.filter((tag) => typeof tag === "string" && tag) : [],
    comment: source.comment || "",
    timeText: timeText(source.createdAt || source.ratedAt)
  };
}

function stopsAheadText(value) {
  const stops = toFiniteNumber(value);
  if (stops === null || stops < 0) {
    return "";
  }
  if (stops === 0) {
    return "你是骑手的下一单";
  }
  return `骑手还有 ${Math.round(stops)} 单送达你`;
}

// fallbackDestination：老订单 tracking 未返回目的地时，用收货地址里的经纬度兜底
function normalizeTracking(raw, fallbackDestination) {
  const intervalMs = pollingIntervalMs(raw);
  if (!raw || typeof raw !== "object" || raw.hasDelivery === false) {
    return { ...EMPTY_DELIVERY, pollingIntervalMs: intervalMs };
  }
  // 响应里一点配送信息都没有时同样按「无配送任务」处理，避免渲染一张空卡片
  if (!raw.taskStatus && !raw.rider && !Array.isArray(raw.timeline)) {
    return { ...EMPTY_DELIVERY, pollingIntervalMs: intervalMs };
  }
  const destination = toPoint(raw.destination) || toPoint(fallbackDestination);
  // 兼容过渡期顶层 degraded；服务端补齐 rider.phoneDegraded 后仍以同一字段消费。
  const trackingDegraded = Boolean(raw.phoneDegraded || raw.degraded);
  const rider = normalizeRider(raw.rider, destination, trackingDegraded);
  const store = toPoint(raw.store);
  const polling = raw.polling && typeof raw.polling === "object" ? raw.polling : {};
  const terminalStatus = isTerminalStatus(raw.taskStatus);
  // rider 为 null 既可能是终态清空，也可能是尚未派单；后者要继续轮询等骑手出现
  const terminal = (polling.stopWhenDone !== false && terminalStatus) || (!rider && (terminalStatus || !raw.taskStatus));
  const eta = raw.eta && typeof raw.eta === "object" ? raw.eta : null;
  const remainingSeconds = eta ? toFiniteNumber(eta.remainingSeconds) : null;
  const hasRiderPoint = Boolean(rider && rider.point);
  const normalizedNotice = noticeText(raw.notice);
  const degradedNotice = noticeText(
    raw.phoneNotice
      || raw.phoneDegradedNotice
      || (raw.rider && (raw.rider.phoneNotice || raw.rider.notice))
      || (trackingDegraded ? raw.notice : "")
  );

  return {
    available: true,
    terminal,
    statusText: raw.taskStatusText || "",
    etaText: terminal ? "" : formatEtaText(eta),
    remainingText:
      !terminal && remainingSeconds !== null && remainingSeconds > 0
        ? `预计还需 ${formatRemaining(remainingSeconds)}`
        : "",
    distanceText: hasRiderPoint ? formatDistance(raw.distanceMeters) : "",
    stopsAheadText: hasRiderPoint ? stopsAheadText(raw.stopsAhead) : "",
    noticeText: normalizedNotice,
    phoneWarningText: rider && rider.phoneDegraded
      ? degradedNotice || "骑手隐私号暂不可用，联系骑手将直接拨号，请勿保存号码。"
      : "",
    // 骑手未取货：只给时间轴，不给地图（骑手还在店里，暴露位置无意义且涉及隐私）
    waitingText: !terminal && !hasRiderPoint && !(rider && rider.locationRejected) ? "门店正在为您备货" : "",
    staleText: !terminal && rider && (rider.locationRejected || (hasRiderPoint && !rider.fresh)) ? "骑手位置更新中" : "",
    hasRider: Boolean(rider),
    rider,
    destination,
    store,
    showMap: Boolean(!terminal && hasRiderPoint && destination),
    timeline: buildTimeline(raw.timeline),
    subscribeTemplateIds: pickTemplateIds(raw),
    subscribed: Boolean(raw.subscribed || (raw.subscribe && raw.subscribe.subscribed)),
    rating: normalizeRating(raw),
    pollingIntervalMs: intervalMs
  };
}

module.exports = {
  EMPTY_DELIVERY,
  RATING_TAGS,
  TIMELINE_TEMPLATE,
  isTerminalStatus,
  markerAnimationDuration,
  normalizeTracking,
  pollingIntervalMs,
  roughDistanceMeters,
  shouldContinueUnavailableTracking,
  trackingPollDelayMs,
  toPoint
};
