// 抽奖状态的判定统一走 reasonCode；reason 是中文文案，只用于展示兜底。
// 后端尚未下发 reasonCode 时，从中文 reason 反推，保证旧服务端也能正常工作。

const REASON_CODES = [
  "ALREADY_DRAWN",
  "ORDER_NOT_PENDING",
  "ORDER_SKIPPED",
  "ACTIVE_PAYMENT_SHARE",
  "DAILY_LIMIT_REACHED",
  "TIER_NOT_MATCHED",
  "CAMPAIGN_DISABLED",
  "CAMPAIGN_NOT_STARTED",
  "CAMPAIGN_ENDED",
  "CHALLENGE_EXPIRED",
  "NO_AVAILABLE_PRIZE",
  "ELIGIBLE"
];

const REASON_TEXTS = {
  ALREADY_DRAWN: "本单已经抽过鲜礼",
  ORDER_NOT_PENDING: "订单状态已变化",
  ORDER_SKIPPED: "本单已按原价锁定，不能再抽鲜礼",
  ACTIVE_PAYMENT_SHARE: "本单已有好友代付链接，不能再抽鲜礼",
  DAILY_LIMIT_REACHED: "今日抽奖次数已用完",
  TIER_NOT_MATCHED: "本单金额不在活动范围内",
  CAMPAIGN_DISABLED: "鲜礼活动未开启",
  CAMPAIGN_NOT_STARTED: "鲜礼活动还未开始",
  CAMPAIGN_ENDED: "本期鲜礼活动已结束",
  CHALLENGE_EXPIRED: "分享凭证已失效",
  NO_AVAILABLE_PRIZE: "当前没有可抽取的鲜礼",
  ELIGIBLE: ""
};

// 旧版本客户端/服务端出现过的英文取值，统一映射到新枚举
const LEGACY_CODES = {
  CHALLENGE_INVALID: "CHALLENGE_EXPIRED",
  INVALID_CHALLENGE: "CHALLENGE_EXPIRED",
  BELOW_THRESHOLD: "TIER_NOT_MATCHED",
  ORDER_NOT_ELIGIBLE: "TIER_NOT_MATCHED"
};

// 顺序敏感：先匹配更具体的短语
const REASON_KEYWORDS = [
  ["ORDER_SKIPPED", ["跳过抽奖"]],
  ["ALREADY_DRAWN", ["已抽奖", "已经抽奖", "已抽取", "最多抽奖一次"]],
  ["ACTIVE_PAYMENT_SHARE", ["代付链接"]],
  ["ORDER_NOT_PENDING", ["仅待支付", "订单状态", "订单不可"]],
  ["CAMPAIGN_NOT_STARTED", ["尚未开始", "还未开始", "未开始"]],
  ["CAMPAIGN_ENDED", ["已结束"]],
  ["CAMPAIGN_DISABLED", ["未开启", "未开放", "已下线"]],
  ["DAILY_LIMIT_REACHED", ["次数已用完", "次数用完", "次数已达"]],
  ["CHALLENGE_EXPIRED", ["凭证"]],
  ["NO_AVAILABLE_PRIZE", ["可抽取奖项", "可抽取的奖项", "没有奖项"]],
  ["TIER_NOT_MATCHED", ["阶梯", "门槛", "金额不在"]]
];

function knownCode(value) {
  const code = String(value || "").trim().toUpperCase();
  if (!code) {
    return "";
  }
  if (REASON_CODES.indexOf(code) >= 0) {
    return code;
  }
  return LEGACY_CODES[code] || "";
}

function lotteryReasonCode(state) {
  const declared = knownCode(state && state.reasonCode);
  if (declared) {
    return declared;
  }
  const reason = String((state && state.reason) || "").trim();
  if (!reason) {
    return "";
  }
  const legacy = knownCode(reason);
  if (legacy) {
    return legacy;
  }
  for (let index = 0; index < REASON_KEYWORDS.length; index += 1) {
    const code = REASON_KEYWORDS[index][0];
    const keywords = REASON_KEYWORDS[index][1];
    if (keywords.some((keyword) => reason.indexOf(keyword) >= 0)) {
      return code;
    }
  }
  return "";
}

function lotteryReasonText(state, fallback = "本单暂不能参与鲜礼活动") {
  const text = REASON_TEXTS[lotteryReasonCode(state)];
  if (text) {
    return text;
  }
  const reason = String((state && state.reason) || "").trim();
  // 英文枚举不适合直接展示给用户
  if (reason && !knownCode(reason)) {
    return reason;
  }
  return fallback;
}

function isChallengeInvalid(state) {
  return lotteryReasonCode(state) === "CHALLENGE_EXPIRED";
}

function hasActivePaymentShare(state) {
  return lotteryReasonCode(state) === "ACTIVE_PAYMENT_SHARE";
}

function campaignEnabled(campaign) {
  return Boolean(campaign && campaign.enabled !== false);
}

function lotteryCampaignEnabled(state) {
  return campaignEnabled(state && state.campaign);
}

// 后端下发的是 LocalDateTime 文本（2026-08-01T09:00:00），iOS 对非标准写法容忍度低，按字段手动解析
function parseCampaignTime(value) {
  if (!value) {
    return null;
  }
  if (value instanceof Date) {
    return Number.isNaN(value.getTime()) ? null : value;
  }
  const text = String(value).trim();
  if (!text) {
    return null;
  }
  const matched = /^(\d{4})-(\d{1,2})-(\d{1,2})(?:[T\s](\d{1,2}):(\d{1,2})(?::(\d{1,2}))?)?/.exec(text);
  if (matched) {
    const parsed = new Date(
      Number(matched[1]),
      Number(matched[2]) - 1,
      Number(matched[3]),
      Number(matched[4] || 0),
      Number(matched[5] || 0),
      Number(matched[6] || 0)
    );
    return Number.isNaN(parsed.getTime()) ? null : parsed;
  }
  const fallback = new Date(text);
  return Number.isNaN(fallback.getTime()) ? null : fallback;
}

function campaignWindowState(campaign, now) {
  if (!campaignEnabled(campaign)) {
    return "DISABLED";
  }
  const current = now instanceof Date ? now.getTime() : Number(now) || Date.now();
  const startAt = parseCampaignTime(campaign.startAt);
  if (startAt && current < startAt.getTime()) {
    return "NOT_STARTED";
  }
  const endAt = parseCampaignTime(campaign.endAt);
  if (endAt && current >= endAt.getTime()) {
    return "ENDED";
  }
  return "ACTIVE";
}

function campaignTimeText(value) {
  const date = parseCampaignTime(value);
  if (!date) {
    return "";
  }
  const pad = (number) => String(number).padStart(2, "0");
  return `${date.getMonth() + 1}月${date.getDate()}日 ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

function campaignNotice(campaign, now) {
  const status = campaignWindowState(campaign, now);
  if (status === "ACTIVE") {
    return null;
  }
  if (status === "NOT_STARTED") {
    const startText = campaignTimeText(campaign && campaign.startAt);
    return {
      status,
      title: "本期鲜礼还没开篮",
      desc: startText
        ? `活动将于 ${startText} 开始，到点再来就能看到可抽取的鲜礼。`
        : "活动开始后，会在这里展示可抽取的鲜礼。"
    };
  }
  if (status === "ENDED") {
    const endText = campaignTimeText(campaign && campaign.endAt);
    return {
      status,
      title: "本期鲜礼已经收篮",
      desc: endText
        ? `活动已于 ${endText} 结束，新一期开放后会在这里展示。`
        : "活动已经结束，新一期开放后会在这里展示。"
    };
  }
  return {
    status,
    title: "本期鲜礼已经收篮",
    desc: "新一期活动开放后，会在这里展示可抽取的内容。"
  };
}

module.exports = {
  REASON_CODES,
  campaignEnabled,
  campaignNotice,
  campaignTimeText,
  campaignWindowState,
  hasActivePaymentShare,
  isChallengeInvalid,
  lotteryCampaignEnabled,
  lotteryReasonCode,
  lotteryReasonText,
  parseCampaignTime
};
