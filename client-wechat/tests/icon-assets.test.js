const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");

const projectRoot = path.resolve(__dirname, "..");

function read(relativePath) {
  return fs.readFileSync(path.join(projectRoot, relativePath), "utf8");
}

function assertAssetsReferenced(source, assetPaths) {
  for (const assetPath of assetPaths) {
    assert.match(source, new RegExp(assetPath.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")));
    assert.equal(
      fs.existsSync(path.join(projectRoot, assetPath.replace(/^\//, ""))),
      true,
      `${assetPath} must exist`
    );
  }
}

test("primary navigation keeps the generated PNG icon set", () => {
  const source = read("components/app-tabbar/index.js");
  assertAssetsReferenced(source, [
    "/assets/icons/tab-home.png",
    "/assets/icons/tab-home-active.png",
    "/assets/icons/tab-category.png",
    "/assets/icons/tab-category-active.png",
    "/assets/icons/tab-cart.png",
    "/assets/icons/tab-cart-active.png",
    "/assets/icons/tab-orders.png",
    "/assets/icons/tab-orders-active.png",
    "/assets/icons/tab-profile.png",
    "/assets/icons/tab-profile-active.png"
  ]);
});

test("profile keeps visible order and menu PNG icons", () => {
  const source = read("pages/profile/index.js");
  assertAssetsReferenced(source, [
    "/assets/icons/order-pending-payment.png",
    "/assets/icons/order-pending-shipment.png",
    "/assets/icons/order-pending-receipt.png",
    "/assets/icons/order-pending-review.png",
    "/assets/icons/profile-menu-location.png",
    "/assets/icons/profile-menu-refund.png",
    "/assets/icons/profile-menu-service.png",
    "/assets/icons/profile-menu-store.png",
    "/assets/icons/profile-menu-settings.png"
  ]);
});

test("product footer keeps the generated share and cart artwork", () => {
  const source = read("pages/product-detail/index.wxml");
  assertAssetsReferenced(source, [
    "/assets/icons/share-generated.png",
    "/assets/icons/cart-generated.png"
  ]);
});

test("startup preload keeps primary PNG icons warm", () => {
  const source = read("utils/image-cache.js");
  assertAssetsReferenced(source, [
    "/assets/icons/order-pending-payment.png",
    "/assets/icons/profile-menu-location.png",
    "/assets/icons/tab-home.png",
    "/assets/icons/tab-profile-active.png"
  ]);
});
