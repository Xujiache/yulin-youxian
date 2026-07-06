const request = require("../utils/request");

function getAddresses() {
  return request({
    url: "/api/wx/addresses"
  });
}

function createAddress(data) {
  return request({
    url: "/api/wx/addresses",
    method: "POST",
    data
  });
}

function updateAddress(id, data) {
  return request({
    url: `/api/wx/addresses/${id}`,
    method: "PUT",
    data
  });
}

function deleteAddress(id) {
  return request({
    url: `/api/wx/addresses/${id}`,
    method: "DELETE"
  });
}

function setDefaultAddress(id) {
  return request({
    url: `/api/wx/addresses/${id}/default`,
    method: "PUT"
  });
}

module.exports = {
  getAddresses,
  createAddress,
  updateAddress,
  deleteAddress,
  setDefaultAddress
};
