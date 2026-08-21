package com.xianda.freshdelivery.delivery.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xianda.freshdelivery.delivery.common.DeliveryException;
import com.xianda.freshdelivery.delivery.common.DeliveryProperties;
import com.xianda.freshdelivery.delivery.common.DeliverySwitch;
import com.xianda.freshdelivery.delivery.dto.ConfigUpdateRequest;
import com.xianda.freshdelivery.delivery.dto.DeliveryConfigItemDto;
import com.xianda.freshdelivery.delivery.repository.DeliveryConfigDao;
import com.xianda.freshdelivery.delivery.repository.DeliveryTestDatabase;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

class DeliveryConfigServiceTests {
    private JdbcTemplate jdbcTemplate;
    private DeliveryConfigDao deliveryConfigDao;
    private DeliveryConfigService deliveryConfigService;

    @BeforeEach
    void setUp() {
        jdbcTemplate = DeliveryTestDatabase.create("delivery_config_service");
        deliveryConfigDao = new DeliveryConfigDao(jdbcTemplate);
        deliveryConfigService = new DeliveryConfigService(deliveryConfigDao);
    }

    @Test
    void readsTypedValuesFromSeededConfig() {
        assertEquals("HAVERSINE", deliveryConfigService.getString("routing.matrix_provider"));
        assertEquals(86400, deliveryConfigService.getInt("fatigue.continuous_warn_seconds"));
        assertEquals(200000, deliveryConfigService.getInt("store.gps_sanity_radius_meters"));
        assertTrue(deliveryConfigService.getBool("dispatch.enabled"));
        assertFalse(deliveryConfigService.getBool("delivery.require_verify_code"));
        assertEquals(0, new BigDecimal("15").compareTo(deliveryConfigService.getDecimal("eta.ebike_speed_kmh")));
        assertEquals(0, new BigDecimal("0.35").compareTo(deliveryConfigService.getDecimal("dispatch.weight_added_distance")));
    }

    @Test
    void clampsEbikeSpeedToRedLine() {
        deliveryConfigService.updateConfigs(
                new ConfigUpdateRequest(List.of(new ConfigUpdateRequest.ConfigItem("eta.ebike_speed_kmh", "20"))),
                "TESTER");

        assertEquals(0, new BigDecimal("15").compareTo(deliveryConfigService.getDecimal("eta.ebike_speed_kmh")));
        assertEquals("15", jdbcTemplate.queryForObject(
                "SELECT config_value FROM delivery_config WHERE config_key = 'eta.ebike_speed_kmh'", String.class));
    }

    @Test
    void clampsValuesIntoDeclaredRange() {
        deliveryConfigService.updateConfigs(new ConfigUpdateRequest(List.of(
                new ConfigUpdateRequest.ConfigItem("dispatch.hold_window_seconds", "999"),
                new ConfigUpdateRequest.ConfigItem("eta.range_span_minutes", "0")
        )), "TESTER");

        assertEquals(180, deliveryConfigService.getInt("dispatch.hold_window_seconds"));
        assertEquals(1, deliveryConfigService.getInt("eta.range_span_minutes"));
    }

    @Test
    void cachesUntilUpdateInvalidatesIt() {
        assertEquals(120, deliveryConfigService.getInt("dispatch.hold_window_seconds"));
        deliveryConfigDao.updateValue("dispatch.hold_window_seconds", "30", "DIRECT");
        assertEquals(120, deliveryConfigService.getInt("dispatch.hold_window_seconds"));

        deliveryConfigService.invalidate();
        assertEquals(30, deliveryConfigService.getInt("dispatch.hold_window_seconds"));

        deliveryConfigService.updateConfigs(
                new ConfigUpdateRequest(List.of(new ConfigUpdateRequest.ConfigItem("dispatch.hold_window_seconds", "45"))),
                "TESTER");
        assertEquals(45, deliveryConfigService.getInt("dispatch.hold_window_seconds"));
    }

    @Test
    void rejectsUnknownKeys() {
        assertThrows(DeliveryException.class, () -> deliveryConfigService.getInt("dispatch.not_a_key"));
        assertThrows(DeliveryException.class, () -> deliveryConfigService.updateConfigs(
                new ConfigUpdateRequest(List.of(new ConfigUpdateRequest.ConfigItem("dispatch.not_a_key", "1"))),
                "TESTER"));
    }

    @Test
    void listsItemsWithFormMetadataAndCategoryGrouping() {
        List<DeliveryConfigItemDto> etaItems = deliveryConfigService.list("ETA");
        assertEquals(9, etaItems.size());
        assertTrue(etaItems.stream().allMatch(item -> item.displayName() != null && !item.displayName().isBlank()));

        Map<String, List<DeliveryConfigItemDto>> grouped = deliveryConfigService.listGroupedByCategory();
        assertEquals(14, grouped.size());
        // V11 seeds three operator-visible settings; amap.web_key remains write-only.
        // V15 adds dispatch.mode.
        // V19 adds customer trail window/max points and live ETA throttle.
        assertEquals(80, grouped.values().stream().mapToInt(List::size).sum());
        assertEquals(4, grouped.get("STORE").size());
        assertEquals(4, grouped.get("FATIGUE").size());
    }

    /**
     * 后台订单页靠 dispatch.enabled 决定显示「拣货完成」还是老的「配送」按钮。
     * 总闸关掉后这个键必须对外报 false,否则订单页照走新流程,点下去才被后端拒绝。
     */
    @Test
    void reportsDispatchDisabledWhileDeliveryMasterSwitchIsOff() {
        DeliveryConfigService offService = new DeliveryConfigService(deliveryConfigDao, deliverySwitchOff());

        DeliveryConfigItemDto dispatchFlag = offService.list("DISPATCH").stream()
                .filter(item -> "dispatch.enabled".equals(item.key()))
                .findFirst()
                .orElseThrow();

        assertEquals("false", dispatchFlag.value());
        assertFalse(dispatchFlag.editable());
        // 库里的值不动:总闸合上后店主原来的设置照旧生效
        assertEquals("true", jdbcTemplate.queryForObject(
                "SELECT config_value FROM delivery_config WHERE config_key = 'dispatch.enabled'", String.class));
        assertTrue(deliveryConfigService.list("DISPATCH").stream()
                .filter(item -> "dispatch.enabled".equals(item.key()))
                .allMatch(item -> "true".equals(item.value())));
    }

    @Test
    void masterSwitchOnlyRewritesTheDispatchFlag() {
        DeliveryConfigService offService = new DeliveryConfigService(deliveryConfigDao, deliverySwitchOff());

        List<DeliveryConfigItemDto> on = deliveryConfigService.list(null);
        List<DeliveryConfigItemDto> off = offService.list(null);

        assertEquals(on.size(), off.size());
        assertEquals(1, countDifferences(on, off));
    }

    private static int countDifferences(List<DeliveryConfigItemDto> left, List<DeliveryConfigItemDto> right) {
        int differences = 0;
        for (int index = 0; index < left.size(); index++) {
            if (!left.get(index).equals(right.get(index))) {
                differences++;
            }
        }
        return differences;
    }

    private static DeliverySwitch deliverySwitchOff() {
        DeliveryProperties disabled = new DeliveryProperties(false, null, null, null, null, null, null, null);
        return new DeliverySwitch(new SingletonObjectProvider<>(disabled));
    }

    private record SingletonObjectProvider<T>(T value) implements ObjectProvider<T> {
        @Override
        public T getObject() {
            return value;
        }

        @Override
        public T getObject(Object... args) {
            return value;
        }

        @Override
        public T getIfAvailable() {
            return value;
        }

        @Override
        public T getIfUnique() {
            return value;
        }
    }

    @Test
    void exposesOnlyTheJsKeyAndNeverLeaksTheServerWebKey() {
        List<DeliveryConfigItemDto> amapItems = deliveryConfigService.list("AMAP");

        assertEquals(List.of("amap.js_key"),
                amapItems.stream().map(DeliveryConfigItemDto::key).toList());
        assertTrue(amapItems.stream().allMatch(item -> "STRING".equals(item.valueType())));
        assertTrue(amapItems.stream().allMatch(item -> item.value().isEmpty()));
        assertTrue(amapItems.stream().allMatch(DeliveryConfigItemDto::editable));
        assertEquals("", deliveryConfigService.getString("amap.js_key"));
        assertEquals("", deliveryConfigService.getString("amap.web_key"));

        deliveryConfigService.updateConfigs(
                new ConfigUpdateRequest(List.of(
                        new ConfigUpdateRequest.ConfigItem("amap.js_key", "  key-abc  "),
                        new ConfigUpdateRequest.ConfigItem("amap.web_key", "server-secret"))),
                "TESTER");

        assertEquals("key-abc", deliveryConfigService.getString("amap.js_key"));
        assertEquals("server-secret", deliveryConfigService.getString("amap.web_key"));
        assertTrue(deliveryConfigService.list(null).stream()
                .noneMatch(item -> "amap.web_key".equals(item.key()) || "server-secret".equals(item.value())));
        assertTrue(deliveryConfigService.listGroupedByCategory().get("AMAP").stream()
                .noneMatch(item -> "amap.web_key".equals(item.key())));
    }

    @Test
    void validatesTheWholeBatchBeforeWritingAnything() {
        assertThrows(DeliveryException.class, () -> deliveryConfigService.updateConfigs(
                new ConfigUpdateRequest(List.of(
                        new ConfigUpdateRequest.ConfigItem("dispatch.hold_window_seconds", "30"),
                        new ConfigUpdateRequest.ConfigItem("dispatch.not_a_key", "1"))),
                "TESTER"));

        assertEquals("120", jdbcTemplate.queryForObject(
                "SELECT config_value FROM delivery_config WHERE config_key = 'dispatch.hold_window_seconds'",
                String.class));
    }

    @Test
    void rollsBackTheWholeBatchAndInvalidatesCacheWhenPersistenceFails() {
        DeliveryConfigDao failingDao = new DeliveryConfigDao(jdbcTemplate) {
            @Override
            public void updateValues(List<ConfigValueUpdate> updates) {
                super.updateValues(updates);
                throw new IllegalStateException("simulated batch failure");
            }
        };
        DeliveryConfigService failingService = new DeliveryConfigService(failingDao);
        assertEquals(120, failingService.getInt("dispatch.hold_window_seconds"));

        DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(
                Objects.requireNonNull(jdbcTemplate.getDataSource()));
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        assertThrows(IllegalStateException.class, () -> transactionTemplate.executeWithoutResult(status ->
                failingService.updateConfigs(
                        new ConfigUpdateRequest(List.of(
                                new ConfigUpdateRequest.ConfigItem("dispatch.hold_window_seconds", "30"),
                                new ConfigUpdateRequest.ConfigItem("eta.range_span_minutes", "5"))),
                        "TESTER")));

        assertEquals("120", jdbcTemplate.queryForObject(
                "SELECT config_value FROM delivery_config WHERE config_key = 'dispatch.hold_window_seconds'",
                String.class));
        assertEquals("10", jdbcTemplate.queryForObject(
                "SELECT config_value FROM delivery_config WHERE config_key = 'eta.range_span_minutes'",
                String.class));

        deliveryConfigDao.updateValue("dispatch.hold_window_seconds", "45", "DIRECT");
        assertEquals(45, failingService.getInt("dispatch.hold_window_seconds"),
                "回滚后缓存必须失效，不能继续发布事务前快照");
    }

    @Test
    void environmentAmapKeyOverridesDatabaseAndIsRestartManaged() {
        DeliveryProperties properties = new DeliveryProperties(
                true, null, new DeliveryProperties.Amap("env-web", "env-js", true),
                null, null, null, null, null);
        DeliveryConfigService environmentService = new DeliveryConfigService(
                deliveryConfigDao, DeliverySwitch.alwaysEnabled(), new SingletonObjectProvider<>(properties));

        DeliveryConfigItemDto jsKey = environmentService.list("AMAP").get(0);
        assertEquals("amap.js_key", jsKey.key());
        assertEquals("env-js", jsKey.value());
        assertFalse(jsKey.editable());
        assertTrue(jsKey.description().contains("需重启"));
        assertEquals("env-web", environmentService.getString("amap.web_key"));
        assertThrows(DeliveryException.class, () -> environmentService.updateConfigs(
                new ConfigUpdateRequest(List.of(new ConfigUpdateRequest.ConfigItem("amap.js_key", "db-js"))),
                "TESTER"));
    }
}
