package com.xianda.freshdelivery.delivery;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xianda.freshdelivery.delivery.repository.DeliveryTestDatabase;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=",
        "spring.flyway.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:deliverycontextboot;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.username=sa",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "persistence.mode=file",
        "delivery.dispatch.loop-enabled=false"
})
class DeliveryContextBootTests {

    static {
        DeliveryTestDatabase.create("deliverycontextboot");
    }

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private RequestMappingHandlerMapping requestMappingHandlerMapping;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void applicationContextWiresEveryDeliveryComponent() {
        assertNotNull(applicationContext);
        List<String> missing = new ArrayList<>();
        for (String required : List.of(
                "com.xianda.freshdelivery.delivery.account.DeliveryConfigService",
                "com.xianda.freshdelivery.delivery.account.RiderAccountService",
                "com.xianda.freshdelivery.delivery.account.RiderAuthService",
                "com.xianda.freshdelivery.delivery.account.RiderShiftService",
                "com.xianda.freshdelivery.delivery.task.DeliveryTaskService",
                "com.xianda.freshdelivery.delivery.task.DeliveryWaveService",
                "com.xianda.freshdelivery.delivery.task.OrderStatusBridge",
                "com.xianda.freshdelivery.delivery.dispatch.DispatchEngine",
                "com.xianda.freshdelivery.delivery.dispatch.RiderScoringService",
                "com.xianda.freshdelivery.delivery.dispatch.BatchingService",
                "com.xianda.freshdelivery.delivery.routing.EtaEngine",
                "com.xianda.freshdelivery.delivery.routing.RoutePlanService",
                "com.xianda.freshdelivery.delivery.routing.AmapWebKeyResolver",
                "com.xianda.freshdelivery.delivery.settlement.RiderScoreService",
                "com.xianda.freshdelivery.delivery.settlement.SettlementService",
                "com.xianda.freshdelivery.delivery.tracking.LocationIngestService",
                "com.xianda.freshdelivery.delivery.tracking.GeofenceService",
                "com.xianda.freshdelivery.delivery.integration.WeatherService",
                "com.xianda.freshdelivery.service.BackupService")) {
            try {
                applicationContext.getBean(Class.forName(required));
            } catch (Exception exception) {
                missing.add(required + " -> " + exception.getClass().getSimpleName());
            }
        }
        assertTrue(missing.isEmpty(), "配送域核心 Bean 未能装配: " + missing);
    }

    @Test
    void everyDeliveryControllerCanRenderBusinessErrorsAsApiResponse() {
        Set<Class<?>> handled = new HashSet<>();
        applicationContext.getBeansWithAnnotation(
                        org.springframework.web.bind.annotation.RestControllerAdvice.class)
                .values()
                .forEach(advice -> handled.add(advice.getClass()));
        assertFalse(handled.isEmpty(), "配送域缺少 DeliveryException 的全局兜底 advice");

        List<String> uncovered = new ArrayList<>();
        for (HandlerMethod handler : requestMappingHandlerMapping.getHandlerMethods().values()) {
            Class<?> controller = handler.getBeanType();
            if (!controller.getPackageName().startsWith("com.xianda.freshdelivery.delivery.controller")) {
                continue;
            }
            boolean ownHandler = false;
            for (Class<?> type = controller; type != null && type != Object.class; type = type.getSuperclass()) {
                for (java.lang.reflect.Method method : type.getDeclaredMethods()) {
                    if (method.isAnnotationPresent(
                            org.springframework.web.bind.annotation.ExceptionHandler.class)) {
                        ownHandler = true;
                    }
                }
            }
            if (!ownHandler && !uncovered.contains(controller.getSimpleName())) {
                uncovered.add(controller.getSimpleName());
            }
        }
        assertTrue(
                applicationContext.containsBean("deliveryExceptionAdvice") || uncovered.isEmpty(),
                "以下控制器既无自带异常处理也无全局 advice 兜底: " + uncovered);
    }

    @Test
    void routePlanningPortResolvesToASinglePrimaryImplementation() {
        Object port = applicationContext.getBean(
                com.xianda.freshdelivery.delivery.dispatch.RoutePlanningPort.class);
        assertNotNull(port);
        assertNotNull(applicationContext.getBean(
                com.xianda.freshdelivery.delivery.task.TaskSettlementPort.class));
        assertNotNull(applicationContext.getBean(
                com.xianda.freshdelivery.delivery.routing.WeatherConditionPort.class));
    }

    @Test
    void contextDatabaseIncludesV11AndFlywayResource() {
        assertTrue(applicationContext
                .getResource("classpath:db/migration/V11__harden_delivery_integrity.sql")
                .exists());
        assertEqualsOne("SELECT COUNT(*) FROM information_schema.tables"
                + " WHERE LOWER(table_schema) = 'public' AND LOWER(table_name) = 'auth_session'");
        assertEqualsOne("SELECT COUNT(*) FROM information_schema.columns"
                + " WHERE LOWER(table_schema) = 'public' AND LOWER(table_name) = 'delivery_task_event'"
                + " AND LOWER(column_name) = 'client_event_id'");
        assertEqualsOne("SELECT COUNT(*) FROM delivery_config"
                + " WHERE config_key = 'routing.amap_max_requests' AND config_value = '64'");
    }

    @Test
    void noHandlerMethodShareTheSameRequestMapping() {
        Set<String> seen = new HashSet<>();
        List<String> duplicates = new ArrayList<>();
        for (java.util.Map.Entry<RequestMappingInfo, HandlerMethod> entry
                : requestMappingHandlerMapping.getHandlerMethods().entrySet()) {
            String signature = entry.getKey().toString();
            if (!seen.add(signature)) {
                duplicates.add(signature + " -> " + entry.getValue());
            }
        }
        assertTrue(duplicates.isEmpty(), "存在重复的请求映射: " + duplicates);
    }

    @Test
    void everyDeliveryEndpointIsRegistered() {
        Set<String> patterns = new HashSet<>();
        requestMappingHandlerMapping.getHandlerMethods().keySet().forEach(info -> {
            if (info.getPathPatternsCondition() != null) {
                info.getPathPatternsCondition().getPatternValues().forEach(patterns::add);
            }
        });
        List<String> missing = new ArrayList<>();
        for (String endpoint : List.of(
                "/api/rider/auth/login",
                "/api/rider/shift/on-duty",
                "/api/rider/tasks",
                "/api/rider/locations/batch",
                "/api/rider/sync",
                "/api/wx/auth/logout",
                "/api/admin/delivery/board",
                "/api/admin/delivery/configs",
                "/api/admin/delivery/riders",
                "/api/wx/delivery/orders/{orderId}/tracking")) {
            if (!patterns.contains(endpoint)) {
                missing.add(endpoint);
            }
        }
        assertTrue(missing.isEmpty(), "契约端点未注册: " + missing);
        assertFalse(patterns.isEmpty());
    }

    private void assertEqualsOne(String sql) {
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class);
        assertTrue(count != null && count == 1, "V11 断言失败: " + sql + " -> " + count);
    }
}
