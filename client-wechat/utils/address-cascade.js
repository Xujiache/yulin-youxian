const COMMUNITIES = [
  "兴顺苑A区",
  "兴顺苑B区",
  "泾华园A区",
  "泾花园B区",
  "公园西小区",
  "富祥园A区",
  "富祥园B区"
];

const FLOOR_OPTIONS = Array.from({ length: 6 }, (_, index) => ({
  label: `${index + 1}楼`,
  value: index + 1
}));

function unitValue(communityIndex, building, unit) {
  return `unit-${communityIndex}-${building}-${unit}`;
}

function buildAddressCascadeOptions() {
  return COMMUNITIES.map((community, communityIndex) => ({
    label: community,
    value: `community-${communityIndex}`,
    children: Array.from({ length: 80 }, (_, buildingIndex) => {
      const building = buildingIndex + 1;
      return {
        label: `${building}号楼`,
        value: `building-${communityIndex}-${building}`,
        children: Array.from({ length: 5 }, (_, unitIndex) => {
          const unit = unitIndex + 1;
          return {
            label: `${unit}单元`,
            value: unitValue(communityIndex, building, unit)
          };
        })
      };
    })
  }));
}

function roomOptionsForFloor(floor) {
  const value = Number(floor || 0);
  if (value < 1 || value > 6) return [];
  return [1, 2].map((roomIndex) => {
    const room = `${value}0${roomIndex}`;
    return { label: `${room}室`, value: room };
  });
}

function cascadeValueForSelection(selection = {}) {
  const communityIndex = COMMUNITIES.indexOf(selection.community);
  const building = Number(selection.building || 0);
  const unit = Number(selection.unit || 0);
  if (communityIndex < 0 || building < 1 || building > 80 || unit < 1 || unit > 5) return null;
  return unitValue(communityIndex, building, unit);
}

function selectionFromCascader(selectedOptions = []) {
  const community = selectedOptions[0] ? String(selectedOptions[0].label || "") : "";
  const buildingMatch = String(selectedOptions[1] && selectedOptions[1].label || "").match(/^(\d{1,2})号楼$/);
  const unitMatch = String(selectedOptions[2] && selectedOptions[2].label || "").match(/^([1-5])单元$/);
  return {
    community,
    building: buildingMatch ? Number(buildingMatch[1]) : 0,
    unit: unitMatch ? Number(unitMatch[1]) : 0
  };
}

function parseTemplateAddress(address = {}) {
  const source = `${address.locationName || ""} ${address.detail || ""}`;
  const community = COMMUNITIES.find((item) => source.includes(item)) || "";
  const buildingMatch = source.match(/(\d{1,2})号楼/);
  const unitMatch = source.match(/([1-5])单元/);
  const roomMatch = source.match(/([1-6]0[12])室?/);
  const room = roomMatch ? roomMatch[1] : "";
  return {
    community,
    building: buildingMatch ? Number(buildingMatch[1]) : 0,
    unit: unitMatch ? Number(unitMatch[1]) : 0,
    floor: room ? Number(room.charAt(0)) : 0,
    room
  };
}

function isCompleteSelection(selection = {}) {
  return Boolean(
    COMMUNITIES.includes(selection.community)
      && Number(selection.building) >= 1
      && Number(selection.building) <= 80
      && Number(selection.unit) >= 1
      && Number(selection.unit) <= 5
      && Number(selection.floor) >= 1
      && Number(selection.floor) <= 6
      && roomOptionsForFloor(selection.floor).some((item) => item.value === String(selection.room || ""))
  );
}

function buildAddressDetail(selection = {}) {
  if (!isCompleteSelection(selection)) return "";
  return `${selection.community} ${selection.building}号楼 ${selection.unit}单元 ${selection.room}室`;
}

module.exports = {
  COMMUNITIES,
  FLOOR_OPTIONS,
  buildAddressCascadeOptions,
  buildAddressDetail,
  cascadeValueForSelection,
  isCompleteSelection,
  parseTemplateAddress,
  roomOptionsForFloor,
  selectionFromCascader
};
