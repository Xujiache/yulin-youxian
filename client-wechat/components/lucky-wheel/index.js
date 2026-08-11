const { yuan } = require("../../utils/format");

const SPIN_DURATION = 4600;
const SPIN_TIMEOUT = SPIN_DURATION + 700;

function hasResult(result) {
  return Boolean(
    result
    && (
      result.drawId
      || result.prizeId
      || result.prizeName
      || result.payableAmount !== undefined
    )
  );
}

function normalizeDegrees(value) {
  return ((Number(value || 0) % 360) + 360) % 360;
}

function shortenName(value) {
  const text = String(value || "鲜礼");
  return text.length > 7 ? `${text.slice(0, 6)}…` : text;
}

function isGiftResult(result) {
  const type = String((result && result.prizeType) || "").toUpperCase();
  return Boolean(
    result
    && (
      result.productId
      || result.skuId
      || (result.gifts || []).length
      || ["PRODUCT", "GIFT", "PHYSICAL", "SKU"].includes(type)
      || type.includes("GIFT")
      || type.includes("PRODUCT")
    )
  );
}

Component({
  properties: {
    visible: {
      type: Boolean,
      value: false,
      observer(visible) {
        if (!visible) {
          return;
        }
        this.preparePrizes();
        if (hasResult(this.properties.initialResult) && !this.data.spinning) {
          setTimeout(() => this.restoreResult(this.properties.initialResult), 0);
        }
      }
    },
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
        if (this.properties.visible && hasResult(result) && !this.data.spinning) {
          setTimeout(() => this.restoreResult(result), 0);
        }
      }
    },
    drawLoading: {
      type: Boolean,
      value: false
    },
    continueLoading: {
      type: Boolean,
      value: false
    },
    continueText: {
      type: String,
      value: "按最终金额继续支付"
    }
  },

  data: {
    displayPrizes: [],
    wheelRotation: 0,
    wheelTransition: "none",
    spinning: false,
    showResult: false,
    localResult: null,
    resultTitle: "",
    resultDiscountText: "",
    resultPayableText: "0.00",
    resultHasGift: false,
    resultImageUrl: ""
  },

  lifetimes: {
    detached() {
      this.clearSpinTimer();
    }
  },

  methods: {
    preparePrizes() {
      const prizes = Array.isArray(this.properties.prizes) ? this.properties.prizes : [];
      const count = Math.max(prizes.length, 1);
      const radius = count >= 10 ? 174 : count >= 8 ? 184 : 194;
      this.setData({
        displayPrizes: prizes.map((prize, index) => {
          const angle = (360 / count) * index;
          return {
            ...prize,
            shortName: shortenName(prize.name),
            tone: index % 3,
            segmentStyle: `transform: rotate(${angle}deg) translateY(-${radius}rpx) rotate(${-angle}deg);`
          };
        })
      });
    },

    resultIndex(result) {
      const prizes = this.data.displayPrizes || [];
      const matchedIndex = prizes.findIndex(
        (prize) => String(prize.id) === String(result && result.prizeId)
      );
      if (matchedIndex >= 0) {
        return matchedIndex;
      }
      const backendIndex = Number(result && result.prizeIndex);
      if (Number.isInteger(backendIndex) && backendIndex >= 0 && backendIndex < prizes.length) {
        return backendIndex;
      }
      return 0;
    },

    resultViewData(result) {
      const discountAmount = Number((result && result.discountAmount) || 0);
      const resultHasGift = isGiftResult(result);
      return {
        localResult: result,
        resultTitle: (result && result.prizeName) || (discountAmount > 0 ? "本单减免" : "菜篮鲜礼"),
        resultDiscountText: discountAmount > 0 ? `本单立减 ¥${yuan(discountAmount)}` : "",
        resultPayableText: yuan(result && result.payableAmount),
        resultHasGift,
        resultImageUrl: (result && result.imageUrl) || ""
      };
    },

    targetRotation(result, withTurns) {
      const count = Math.max((this.data.displayPrizes || []).length, 1);
      const index = this.resultIndex(result);
      const targetModulo = normalizeDegrees(-(360 / count) * index);
      if (!withTurns) {
        return targetModulo;
      }
      const current = Number(this.data.wheelRotation || 0);
      const delta = normalizeDegrees(targetModulo - normalizeDegrees(current));
      return current + 5 * 360 + delta;
    },

    spinTo(result) {
      if (!hasResult(result) || this.data.spinning) {
        return;
      }
      this.clearSpinTimer();
      const targetRotation = this.targetRotation(result, true);
      this.setData({
        ...this.resultViewData(result),
        showResult: false,
        spinning: true,
        wheelTransition: "none"
      }, () => {
        this._startTimer = setTimeout(() => {
          this._startTimer = null;
          if (!this.data.spinning) {
            return;
          }
          this.setData({
            wheelRotation: targetRotation,
            wheelTransition: `transform ${SPIN_DURATION}ms cubic-bezier(0.12, 0.68, 0.16, 1)`
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
        ...this.resultViewData(result),
        wheelRotation: this.targetRotation(result, false),
        wheelTransition: "none",
        spinning: false,
        showResult: true
      });
    },

    finishSpin(source) {
      if (!this.data.spinning) {
        return;
      }
      this.clearSpinTimer();
      this.setData({
        spinning: false,
        showResult: true
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
    },

    handleDraw() {
      if (this.data.spinning || this.properties.drawLoading) {
        return;
      }
      this.triggerEvent("draw");
    },

    handleClose() {
      if (this.data.spinning || this.properties.drawLoading || this.properties.continueLoading) {
        return;
      }
      this.triggerEvent("close", {
        hasResult: this.data.showResult,
        result: this.data.localResult
      });
    },

    handleContinue() {
      if (!this.data.showResult || this.data.spinning || this.properties.continueLoading) {
        return;
      }
      this.triggerEvent("continue", { result: this.data.localResult });
    },

    stopPanelTap() {}
  }
});
