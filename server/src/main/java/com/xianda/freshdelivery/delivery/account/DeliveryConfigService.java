package com.xianda.freshdelivery.delivery.account;

import com.xianda.freshdelivery.delivery.common.DeliveryException;
import com.xianda.freshdelivery.delivery.common.DeliveryProperties;
import com.xianda.freshdelivery.delivery.common.DeliverySwitch;
import com.xianda.freshdelivery.delivery.domain.DeliveryConfig;
import com.xianda.freshdelivery.delivery.dto.ConfigUpdateRequest;
import com.xianda.freshdelivery.delivery.dto.DeliveryConfigItemDto;
import com.xianda.freshdelivery.delivery.repository.DeliveryConfigDao;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class DeliveryConfigService {
    public static final String KEY_EBIKE_SPEED_KMH = "eta.ebike_speed_kmh";
    public static final String KEY_DISPATCH_ENABLED = "dispatch.enabled";
    public static final String KEY_AMAP_JS_KEY = "amap.js_key";
    public static final String KEY_AMAP_WEB_KEY = "amap.web_key";
    public static final BigDecimal EBIKE_SPEED_HARD_LIMIT_KMH = new BigDecimal("15");

    private static final Set<String> TRUE_VALUES = Set.of("true", "1", "yes", "on", "y");

    private final DeliveryConfigDao deliveryConfigDao;
    private final DeliverySwitch deliverySwitch;
    private final ObjectProvider<DeliveryProperties> deliveryProperties;
    private volatile Map<String, DeliveryConfig> cache;

    @Autowired
    public DeliveryConfigService(DeliveryConfigDao deliveryConfigDao,
                                 DeliverySwitch deliverySwitch,
                                 ObjectProvider<DeliveryProperties> deliveryProperties) {
        this.deliveryConfigDao = deliveryConfigDao;
        this.deliverySwitch = deliverySwitch;
        this.deliveryProperties = deliveryProperties;
    }

    public DeliveryConfigService(DeliveryConfigDao deliveryConfigDao, DeliverySwitch deliverySwitch) {
        this(deliveryConfigDao, deliverySwitch, null);
    }

    public DeliveryConfigService(DeliveryConfigDao deliveryConfigDao) {
        this(deliveryConfigDao, DeliverySwitch.alwaysEnabled());
    }

    public String getString(String key) {
        return effectiveValue(require(key));
    }

    public int getInt(String key) {
        return decimalOf(key).intValue();
    }

    public boolean getBool(String key) {
        return isTrue(effectiveValue(require(key)));
    }

    public BigDecimal getDecimal(String key) {
        return decimalOf(key);
    }

    public boolean containsKey(String key) {
        return snapshot().containsKey(key);
    }

    public List<DeliveryConfigItemDto> list(String category) {
        boolean deliveryEnabled = deliverySwitch.enabled();
        return list(snapshot(), category, deliveryEnabled);
    }

    private List<DeliveryConfigItemDto> list(
            Map<String, DeliveryConfig> values,
            String category,
            boolean deliveryEnabled
    ) {
        return values.values().stream()
                .filter(config -> !KEY_AMAP_WEB_KEY.equals(config.configKey()))
                .filter(config -> category == null || category.isBlank() || category.trim().equalsIgnoreCase(config.category()))
                .sorted(Comparator.comparing(DeliveryConfig::category).thenComparing(DeliveryConfig::configKey))
                .map(config -> toDto(config, deliveryEnabled))
                .toList();
    }

    public Map<String, List<DeliveryConfigItemDto>> listGroupedByCategory() {
        Map<String, List<DeliveryConfigItemDto>> grouped = new LinkedHashMap<>();
        for (DeliveryConfigItemDto item : list(null)) {
            grouped.computeIfAbsent(item.category(), key -> new ArrayList<>()).add(item);
        }
        return grouped;
    }

    @Transactional
    public synchronized List<DeliveryConfigItemDto> updateConfigs(ConfigUpdateRequest request, String operator) {
        if (request == null || request.items() == null || request.items().isEmpty()) {
            throw new DeliveryException(400, "配置项不能为空");
        }
        Map<String, DeliveryConfig> current = snapshot();
        List<DeliveryConfigDao.ConfigValueUpdate> updates = new ArrayList<>(request.items().size());
        Map<String, String> normalizedValues = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        for (ConfigUpdateRequest.ConfigItem item : request.items()) {
            if (item == null || item.key() == null || item.key().isBlank()) {
                throw new DeliveryException(400, "配置项 key 不能为空");
            }
            String key = item.key().trim();
            if (!seen.add(key)) {
                throw new DeliveryException(400, "配置项重复: " + key);
            }
            DeliveryConfig config = current.get(key);
            if (config == null) {
                throw new DeliveryException(400, "配送配置项不存在: " + item.key());
            }
            if (Boolean.FALSE.equals(config.editable())) {
                throw new DeliveryException(400, "配置项不可修改: " + config.configKey());
            }
            if (!isBlank(environmentValue(config.configKey()))) {
                throw new DeliveryException(400,
                        "配置项 " + config.configKey() + " 当前由环境变量提供，修改环境变量后需重启服务");
            }
            String normalized = normalizeValue(config, item.value());
            normalizedValues.put(config.configKey(), normalized);
            updates.add(new DeliveryConfigDao.ConfigValueUpdate(config.configKey(), normalized, operator));
        }

        try {
            deliveryConfigDao.updateValues(updates);
        } finally {
            invalidateAfterTransactionCompletion();
        }

        Map<String, DeliveryConfig> updated = new LinkedHashMap<>(current);
        for (Map.Entry<String, String> entry : normalizedValues.entrySet()) {
            updated.computeIfPresent(entry.getKey(),
                    (key, config) -> withValue(config, entry.getValue(), operator));
        }
        return list(updated, null, deliverySwitch.enabled());
    }

    public void invalidate() {
        this.cache = null;
    }

    private void invalidateAfterTransactionCompletion() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            invalidate();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                invalidate();
            }
        });
    }

    private DeliveryConfig require(String key) {
        DeliveryConfig config = snapshot().get(key);
        if (config == null) {
            throw new DeliveryException(500, "配送配置项不存在: " + key);
        }
        return config;
    }

    private BigDecimal decimalOf(String key) {
        String value = effectiveValue(require(key));
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException exception) {
            throw new DeliveryException(500, "配送配置项不是数字: " + key + "=" + value);
        }
    }

    private Map<String, DeliveryConfig> snapshot() {
        Map<String, DeliveryConfig> local = cache;
        if (local != null) {
            return local;
        }
        synchronized (this) {
            if (cache == null) {
                Map<String, DeliveryConfig> loaded = new LinkedHashMap<>();
                for (DeliveryConfig config : deliveryConfigDao.findAll()) {
                    loaded.put(config.configKey(), config);
                }
                cache = Map.copyOf(loaded);
            }
            return cache;
        }
    }

    private String normalizeValue(DeliveryConfig config, String rawValue) {
        String value = rawValue == null ? "" : rawValue.trim();
        if (value.isEmpty() && !isAmapKey(config.configKey())) {
            throw new DeliveryException(400, "配置项值不能为空: " + config.configKey());
        }
        String type = config.valueType() == null ? "STRING" : config.valueType().toUpperCase();
        return switch (type) {
            case "INT" -> clampNumeric(config, parseNumber(config, value)).toBigInteger().toString();
            case "DECIMAL" -> clampNumeric(config, parseNumber(config, value)).stripTrailingZeros().toPlainString();
            case "BOOL" -> String.valueOf(isTrue(value));
            default -> value;
        };
    }

    private BigDecimal parseNumber(DeliveryConfig config, String value) {
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException exception) {
            throw new DeliveryException(400, "配置项需要数字: " + config.configKey() + "=" + value);
        }
    }

    private BigDecimal clampNumeric(DeliveryConfig config, BigDecimal value) {
        BigDecimal result = value;
        BigDecimal min = toDecimal(config.minValue());
        BigDecimal max = toDecimal(config.maxValue());
        if (min != null && result.compareTo(min) < 0) {
            result = min;
        }
        if (max != null && result.compareTo(max) > 0) {
            result = max;
        }
        if (KEY_EBIKE_SPEED_KMH.equals(config.configKey()) && result.compareTo(EBIKE_SPEED_HARD_LIMIT_KMH) > 0) {
            result = EBIKE_SPEED_HARD_LIMIT_KMH;
        }
        return result;
    }

    private static boolean isTrue(String value) {
        return value != null && TRUE_VALUES.contains(value.trim().toLowerCase(Locale.ROOT));
    }

    private static BigDecimal toDecimal(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private String effectiveValue(DeliveryConfig config) {
        String environmentValue = environmentValue(config.configKey());
        return isBlank(environmentValue) ? config.configValue() : environmentValue.trim();
    }

    private String environmentValue(String key) {
        DeliveryProperties properties = deliveryProperties == null ? null : deliveryProperties.getIfAvailable();
        DeliveryProperties.Amap amap = properties == null ? null : properties.amap();
        if (amap == null) {
            return null;
        }
        return switch (key) {
            case KEY_AMAP_JS_KEY -> amap.jsKey();
            case KEY_AMAP_WEB_KEY -> amap.webKey();
            default -> null;
        };
    }

    private static boolean isAmapKey(String key) {
        return KEY_AMAP_JS_KEY.equals(key) || KEY_AMAP_WEB_KEY.equals(key);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static DeliveryConfig withValue(DeliveryConfig config, String value, String operator) {
        return new DeliveryConfig(
                config.configKey(),
                value,
                config.valueType(),
                config.category(),
                config.displayName(),
                config.description(),
                config.minValue(),
                config.maxValue(),
                config.editable(),
                operator,
                config.updatedAt());
    }

    /**
     * 总闸关闭时对外把 dispatch.enabled 报成 false。后台订单页就是靠这个键决定
     * 显示「拣货完成」还是老的「配送」按钮,不这么处理的话关了总闸订单页照样走新流程,
     * 点下去才被后端拒绝。库里的值不动,总闸合上后店主原来的设置照旧生效。
     */
    private DeliveryConfigItemDto toDto(DeliveryConfig config, boolean deliveryEnabled) {
        boolean forcedOff = !deliveryEnabled && KEY_DISPATCH_ENABLED.equals(config.configKey());
        boolean environmentManaged = !isBlank(environmentValue(config.configKey()));
        String runtimeSemantics = environmentManaged
                ? "当前由环境变量提供；环境变量变更后需重启服务。"
                : "通过配置接口保存后运行时热生效。";
        String description = config.description() == null || config.description().isBlank()
                ? runtimeSemantics
                : config.description() + " " + runtimeSemantics;
        return new DeliveryConfigItemDto(
                config.configKey(),
                forcedOff ? "false" : effectiveValue(config),
                config.valueType(),
                config.category(),
                config.displayName(),
                description,
                config.minValue(),
                config.maxValue(),
                forcedOff || environmentManaged ? Boolean.FALSE : config.editable()
        );
    }
}
