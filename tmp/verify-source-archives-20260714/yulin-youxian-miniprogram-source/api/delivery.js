const request = require("../utils/request");
function getDeliverySlots() {
    return request({
        url: "/api/wx/delivery-slots"
    });
}
module.exports = {
    getDeliverySlots
};
