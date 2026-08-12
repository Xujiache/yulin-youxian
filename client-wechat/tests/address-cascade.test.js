const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");

const {
  COMMUNITIES,
  FLOOR_OPTIONS,
  buildAddressCascadeOptions,
  buildAddressDetail,
  cascadeValueForSelection,
  isCompleteSelection,
  parseTemplateAddress,
  roomOptionsForFloor,
  selectionFromCascader
} = require("../utils/address-cascade");

test("address cascade exposes all configured communities, buildings and units", () => {
  const options = buildAddressCascadeOptions();

  assert.deepEqual(COMMUNITIES, [
    "兴顺苑A区",
    "兴顺苑B区",
    "泾华园A区",
    "泾花园B区",
    "公园西小区",
    "富祥园A区",
    "富祥园B区"
  ]);
  assert.equal(options.length, 7);
  assert.equal(options[0].children.length, 80);
  assert.equal(options[0].children[79].label, "80号楼");
  assert.equal(options[0].children[79].children.length, 5);
  assert.equal(options[0].children[79].children[4].label, "5单元");
});

test("floor must be selected before one of its two room numbers", () => {
  assert.deepEqual(FLOOR_OPTIONS.map((item) => item.value), [1, 2, 3, 4, 5, 6]);
  assert.deepEqual(roomOptionsForFloor(1).map((item) => item.value), ["101", "102"]);
  assert.deepEqual(roomOptionsForFloor(6).map((item) => item.value), ["601", "602"]);
  assert.deepEqual(roomOptionsForFloor(7), []);
});

test("selected cascade path produces a complete persisted address", () => {
  const cascadeSelection = selectionFromCascader([
    { label: "公园西小区" },
    { label: "80号楼" },
    { label: "5单元" }
  ]);
  const selection = { ...cascadeSelection, floor: 6, room: "602" };

  assert.equal(cascadeValueForSelection(selection), "unit-4-80-5");
  assert.equal(isCompleteSelection(selection), true);
  assert.equal(buildAddressDetail(selection), "公园西小区 80号楼 5单元 602室");
  assert.deepEqual(
    parseTemplateAddress({ detail: "公园西小区 80号楼 5单元 602室" }),
    selection
  );
});

test("invalid room and out-of-range building cannot be saved", () => {
  assert.equal(isCompleteSelection({
    community: "兴顺苑A区",
    building: 81,
    unit: 1,
    floor: 1,
    room: "101"
  }), false);
  assert.equal(isCompleteSelection({
    community: "兴顺苑A区",
    building: 1,
    unit: 1,
    floor: 1,
    room: "202"
  }), false);
});

test("address page ships a directly resolvable TDesign cascader", () => {
  const projectRoot = path.resolve(__dirname, "..");
  const pageConfig = JSON.parse(
    fs.readFileSync(path.join(projectRoot, "pages", "address", "index.json"), "utf8")
  );
  const componentPath = pageConfig.usingComponents["t-cascader"];

  assert.equal(componentPath, "/miniprogram_npm/tdesign-miniprogram/cascader/cascader");
  for (const extension of ["js", "json", "wxml", "wxss"]) {
    assert.equal(
      fs.existsSync(path.join(projectRoot, `${componentPath}.${extension}`)),
      true,
      `missing TDesign cascader .${extension} artifact`
    );
  }
});
