package com.xianda.freshdelivery.service;

import com.xianda.freshdelivery.dto.AddressDto;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DeliveryAddressIntelligence {
    private static final String NUMBER_TOKEN = "[0-9A-Za-z零〇一二两三四五六七八九十百千万]+";
    private static final Pattern BUILDING_PATTERN = Pattern.compile("(?:第)?(" + NUMBER_TOKEN + ")\\s*(?:号)?\\s*(号楼|栋|幢|座)", Pattern.CASE_INSENSITIVE);
    private static final Pattern UNIT_PATTERN = Pattern.compile("(?:第)?(" + NUMBER_TOKEN + ")\\s*单元", Pattern.CASE_INSENSITIVE);
    private static final Pattern FLOOR_PATTERN = Pattern.compile("(?:第)?(" + NUMBER_TOKEN + ")\\s*(?:层|楼层)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ROOM_PATTERN = Pattern.compile("(?:第)?(" + NUMBER_TOKEN + ")\\s*(?:室|房)", Pattern.CASE_INSENSITIVE);
    private static final Pattern TIME_PATTERN = Pattern.compile("(\\d{1,2}):(\\d{2})");
    private static final Map<Character, Integer> CHINESE_DIGITS = Map.ofEntries(
            Map.entry('零', 0), Map.entry('〇', 0), Map.entry('一', 1), Map.entry('二', 2),
            Map.entry('两', 2), Map.entry('三', 3), Map.entry('四', 4), Map.entry('五', 5),
            Map.entry('六', 6), Map.entry('七', 7), Map.entry('八', 8), Map.entry('九', 9)
    );

    private DeliveryAddressIntelligence() {
    }

    public static Profile analyze(AddressDto address) {
        AddressDto safeAddress = address == null
                ? new AddressDto(null, "", "", "", "", null, null, false)
                : address;
        String location = displayText(safeAddress.locationName());
        String detail = displayText(safeAddress.detail());
        String fullAddress = (location + " " + detail).trim();
        Matcher buildingMatcher = BUILDING_PATTERN.matcher(detail);
        boolean buildingFound = buildingMatcher.find();
        String buildingLabel = buildingFound ? buildingLabel(buildingMatcher.group(1), buildingMatcher.group(2)) : "同一地址";
        String buildingSortKey = buildingFound ? tokenSortKey(buildingMatcher.group(1)) : "9:" + normalizeKey(fullAddress);
        String areaLabel = location.isBlank() ? inferArea(detail, safeAddress) : location;
        String areaSortKey = normalizeKey(areaLabel);
        String groupKey = areaSortKey + "|" + (buildingFound ? "building:" + buildingSortKey : "address:" + normalizeKey(fullAddress));
        String roomToken = token(ROOM_PATTERN, detail);
        Integer roomNumber = parseNumber(roomToken);
        String floorSortKey = tokenSortKey(token(FLOOR_PATTERN, detail));
        if (floorSortKey.startsWith("9:") && roomNumber != null && roomNumber >= 100) {
            floorSortKey = numericSortKey(roomNumber / 100);
        }
        return new Profile(
                areaLabel,
                buildingLabel,
                groupKey,
                areaSortKey,
                buildingSortKey,
                tokenSortKey(token(UNIT_PATTERN, detail)),
                floorSortKey,
                tokenSortKey(roomToken),
                normalizeKey(fullAddress)
        );
    }

    public static String deliverySlotSortKey(String deliverySlot) {
        Matcher matcher = TIME_PATTERN.matcher(displayText(deliverySlot));
        if (!matcher.find()) {
            return "9999";
        }
        return String.format(Locale.ROOT, "%02d%02d", Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)));
    }

    private static String inferArea(String detail, AddressDto address) {
        String base = BUILDING_PATTERN.matcher(detail).replaceAll("");
        base = UNIT_PATTERN.matcher(base).replaceAll("");
        base = FLOOR_PATTERN.matcher(base).replaceAll("");
        base = ROOM_PATTERN.matcher(base).replaceAll("");
        base = displayText(base);
        if (!base.isBlank()) {
            return base;
        }
        if (address.latitude() != null && address.longitude() != null) {
            return String.format(Locale.ROOT, "地图位置 %.4f, %.4f", address.latitude(), address.longitude());
        }
        return "未填写区域";
    }

    private static String token(Pattern pattern, String source) {
        Matcher matcher = pattern.matcher(source);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String buildingLabel(String token, String suffix) {
        Integer number = parseNumber(token);
        String display = number == null ? displayText(token).toUpperCase(Locale.ROOT) : number.toString();
        return "座".equals(suffix) ? display + "座" : display + "号楼";
    }

    private static String tokenSortKey(String token) {
        Integer number = parseNumber(token);
        if (number != null) {
            return numericSortKey(number);
        }
        String normalized = normalizeKey(token);
        return normalized.isBlank() ? "9:" : "1:" + normalized;
    }

    private static String numericSortKey(int number) {
        return String.format(Locale.ROOT, "0:%08d", number);
    }

    private static Integer parseNumber(String source) {
        String value = displayText(source);
        if (value.isBlank()) {
            return null;
        }
        if (value.chars().allMatch(Character::isDigit)) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        int total = 0;
        int current = 0;
        boolean recognized = false;
        for (char character : value.toCharArray()) {
            Integer digit = CHINESE_DIGITS.get(character);
            if (digit != null) {
                current = digit;
                recognized = true;
                continue;
            }
            int unit = switch (character) {
                case '十' -> 10;
                case '百' -> 100;
                case '千' -> 1000;
                case '万' -> 10000;
                default -> 0;
            };
            if (unit == 0) {
                return null;
            }
            recognized = true;
            current = current == 0 ? 1 : current;
            total += current * unit;
            current = 0;
        }
        return recognized ? total + current : null;
    }

    private static String displayText(String source) {
        if (source == null) {
            return "";
        }
        return Normalizer.normalize(source, Normalizer.Form.NFKC)
                .replaceAll("[\\s,，。;；、]+", " ")
                .trim();
    }

    private static String normalizeKey(String source) {
        return displayText(source)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^0-9a-z\\p{IsHan}]", "");
    }

    public record Profile(
            String areaLabel,
            String buildingLabel,
            String groupKey,
            String areaSortKey,
            String buildingSortKey,
            String unitSortKey,
            String floorSortKey,
            String roomSortKey,
            String exactAddressKey
    ) {
    }
}
