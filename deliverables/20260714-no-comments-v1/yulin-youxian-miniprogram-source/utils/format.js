function yuan(fen) {
    return (Number(fen || 0) / 100).toFixed(2);
}
function money(fen) {
    return "¥" + yuan(fen);
}
function quantityText(quantity, unit) {
    const n = Number(quantity || 0);
    const text = Number.isInteger(n) ? String(n) : String(n).replace(/0+$/, "").replace(/\.$/, "");
    return text + unit;
}
function lineAmount(unitPrice, quantity) {
    return Math.round(Number(unitPrice || 0) * Number(quantity || 0));
}
module.exports = {
    yuan,
    money,
    quantityText,
    lineAmount
};
