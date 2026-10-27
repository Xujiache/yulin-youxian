const SLOT_COUNT = 4;
const SLOT_ANGLE = 90;
const SPIN_TURNS = 6;

function normalizePrizeCode(value) {
  return String(value || "").trim().toUpperCase();
}

function resolvePrizeIndex(prizes, result) {
  const list = Array.isArray(prizes) ? prizes : [];
  if (!list.length || !result) {
    return -1;
  }
  const prizeId = result.prizeId;
  if (prizeId !== undefined && prizeId !== null && prizeId !== "") {
    const matched = list.findIndex((prize) => prize && String(prize.id) === String(prizeId));
    if (matched >= 0) {
      return matched;
    }
  }
  const prizeCode = normalizePrizeCode(result.prizeCode);
  if (prizeCode) {
    const matched = list.findIndex((prize) => prize && normalizePrizeCode(prize.prizeCode) === prizeCode);
    if (matched >= 0) {
      return matched;
    }
  }
  const backendIndex = Number(result.prizeIndex);
  if (Number.isInteger(backendIndex) && backendIndex >= 0 && backendIndex < list.length) {
    return backendIndex;
  }
  return -1;
}

function normalizeDegrees(value) {
  return ((Number(value || 0) % 360) + 360) % 360;
}

function slotCenterAngle(index) {
  return SLOT_ANGLE * Number(index || 0);
}

function slotTargetAngle(index) {
  return normalizeDegrees(-(SLOT_ANGLE * Number(index || 0)));
}

function targetRotation({
  index,
  count = SLOT_COUNT,
  currentRotation = 0,
  withTurns = false,
  turns = SPIN_TURNS
} = {}) {
  const current = Number(currentRotation) || 0;
  const total = Math.max(Number(count) || 0, 1);
  if (!Number.isInteger(index) || index < 0 || index >= total) {
    return current;
  }
  const targetModulo = total === SLOT_COUNT
    ? slotTargetAngle(index)
    : normalizeDegrees(-(360 / total) * index);
  if (!withTurns) {
    return targetModulo;
  }
  return current + turns * 360 + normalizeDegrees(targetModulo - normalizeDegrees(current));
}

module.exports = {
  SLOT_ANGLE,
  SLOT_COUNT,
  SPIN_TURNS,
  normalizeDegrees,
  resolvePrizeIndex,
  slotCenterAngle,
  slotTargetAngle,
  targetRotation
};
