// 转盘只负责把服务端已经确定的结果对齐到对应格子。
// prizeId 优先；prizeIndex 是奖项在下发给客户端的 prizes 列表中的下标，对不上时返回 -1，
// 此时宁可不转动也不能停在错误的格子上。

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
  const backendIndex = Number(result.prizeIndex);
  if (Number.isInteger(backendIndex) && backendIndex >= 0 && backendIndex < list.length) {
    return backendIndex;
  }
  return -1;
}

function normalizeDegrees(value) {
  return ((Number(value || 0) % 360) + 360) % 360;
}

function targetRotation({ index, count, currentRotation = 0, withTurns = false, turns = 5 }) {
  const current = Number(currentRotation) || 0;
  const total = Math.max(Number(count) || 0, 1);
  if (!Number.isInteger(index) || index < 0 || index >= total) {
    return current;
  }
  const targetModulo = normalizeDegrees(-(360 / total) * index);
  if (!withTurns) {
    return targetModulo;
  }
  return current + turns * 360 + normalizeDegrees(targetModulo - normalizeDegrees(current));
}

module.exports = {
  normalizeDegrees,
  resolvePrizeIndex,
  targetRotation
};
