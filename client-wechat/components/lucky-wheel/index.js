const { SLOT_COUNT, SPIN_TURNS, resolvePrizeIndex, targetRotation } = require("./wheel-math");

const SPIN_DURATION = 4200;
const SPIN_TIMEOUT = SPIN_DURATION + 600;
const SLOT_LABELS = ["一等奖", "二等奖", "三等奖", "谢谢惠顾"];
const SLOT_CODES = ["FIRST", "SECOND", "THIRD", "NONE"];

function hasResult(result) {
  return Boolean(
    result
    && (
      result.drawId
      || result.prizeId
      || result.prizeCode
      || result.prizeName
      || result.payableAmount !== undefined
    )
  );
}

Component({
  properties: {
    prizes: {
      type: Array,
      value: [],
      observer() {
        this.preparePrizes();
      }
    },
    initialResult: {
      type: Object,
      value: null,
      observer(result) {
        if (hasResult(result) && !this.data.spinning) {
          setTimeout(() => this.restoreResult(result), 0);
        }
      }
    }
  },

  data: {
    displayPrizes: [],
    wheelRotation: 0,
    wheelTransition: "none",
    spinning: false,
    winnerIndex: -1,
    localResult: null
  },

  lifetimes: {
    attached() {
      this.preparePrizes();
      if (hasResult(this.properties.initialResult)) {
        this.restoreResult(this.properties.initialResult);
      }
    },
    detached() {
      this.clearSpinTimer();
    }
  },

  methods: {
    preparePrizes() {
      const incoming = Array.isArray(this.properties.prizes) ? this.properties.prizes : [];
      const byCode = {};
      incoming.forEach((prize) => {
        const code = String((prize && prize.prizeCode) || "").toUpperCase();
        if (SLOT_CODES.includes(code) && !byCode[code]) {
          byCode[code] = prize;
        }
      });
      this.setData({
        displayPrizes: SLOT_CODES.map((code, index) => {
          const prize = byCode[code] || incoming[index] || {};
          const angle = index * 90;
          return {
            ...prize,
            slot: code,
            prizeCode: prize.prizeCode || code,
            label: SLOT_LABELS[index],
            tone: index,
            labelStyle: `transform: rotate(${angle}deg) translateY(-148rpx) rotate(${-angle}deg);`
          };
        })
      });
    },

    resultIndex(result) {
      return resolvePrizeIndex(this.data.displayPrizes, result);
    },

    targetRotation(result, withTurns) {
      return targetRotation({
        index: this.resultIndex(result),
        count: SLOT_COUNT,
        currentRotation: this.data.wheelRotation,
        withTurns,
        turns: SPIN_TURNS
      });
    },

    showResultWithoutSpin(result, source) {
      this.setData({
        localResult: result,
        wheelTransition: "none",
        spinning: false,
        winnerIndex: -1
      });
      this.triggerEvent("finish", { result, source });
    },

    spinTo(result) {
      if (!hasResult(result) || this.data.spinning) {
        return;
      }
      this.clearSpinTimer();
      if (this.resultIndex(result) < 0) {
        this.showResultWithoutSpin(result, "unmatched");
        return;
      }
      const rotation = this.targetRotation(result, true);
      this.setData({
        localResult: result,
        spinning: true,
        winnerIndex: -1,
        wheelTransition: "none"
      }, () => {
        this._startTimer = setTimeout(() => {
          this._startTimer = null;
          if (!this.data.spinning) {
            return;
          }
          this.setData({
            wheelRotation: rotation,
            wheelTransition: `transform ${SPIN_DURATION}ms cubic-bezier(0.18, 0.86, 0.12, 1)`
          });
          this._spinTimer = setTimeout(() => this.finishSpin("timeout"), SPIN_TIMEOUT);
        }, 32);
      });
    },

    restoreResult(result) {
      if (!hasResult(result) || this.data.spinning) {
        return;
      }
      this.clearSpinTimer();
      this.setData({
        localResult: result,
        wheelRotation: this.targetRotation(result, false),
        wheelTransition: "none",
        spinning: false,
        winnerIndex: this.resultIndex(result)
      });
    },

    finishSpin(source) {
      if (!this.data.spinning) {
        return;
      }
      this.clearSpinTimer();
      this.setData({
        spinning: false,
        winnerIndex: this.resultIndex(this.data.localResult)
      });
      this.triggerEvent("finish", {
        result: this.data.localResult,
        source
      });
    },

    clearSpinTimer() {
      if (this._startTimer) {
        clearTimeout(this._startTimer);
        this._startTimer = null;
      }
      if (this._spinTimer) {
        clearTimeout(this._spinTimer);
        this._spinTimer = null;
      }
    },

    handleTransitionEnd() {
      this.finishSpin("transitionend");
    }
  }
});
