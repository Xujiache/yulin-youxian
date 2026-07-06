const request = require("../utils/request");
const { normalizeCartItem } = require("./normalize");

async function getCart() {
  const cart = await request({
    url: "/api/wx/cart"
  });
  return {
    ...cart,
    items: (cart.items || []).map(normalizeCartItem)
  };
}

async function addCartItem(productId, quantity) {
  const item = await request({
    url: "/api/wx/cart/items",
    method: "POST",
    data: {
      productId,
      quantity
    }
  });
  return normalizeCartItem(item);
}

async function updateCartItem(id, quantity) {
  const item = await request({
    url: `/api/wx/cart/items/${id}`,
    method: "PUT",
    data: { quantity }
  });
  return normalizeCartItem(item);
}

async function setCartItemSelected(id, selected) {
  const item = await request({
    url: `/api/wx/cart/items/${id}/selected`,
    method: "PUT",
    data: { selected }
  });
  return normalizeCartItem(item);
}

function deleteCartItem(id) {
  return request({
    url: `/api/wx/cart/items/${id}`,
    method: "DELETE"
  });
}

function clearSelectedCartItems() {
  return request({
    url: "/api/wx/cart/selected",
    method: "DELETE"
  });
}

module.exports = {
  getCart,
  addCartItem,
  updateCartItem,
  setCartItemSelected,
  deleteCartItem,
  clearSelectedCartItems
};
