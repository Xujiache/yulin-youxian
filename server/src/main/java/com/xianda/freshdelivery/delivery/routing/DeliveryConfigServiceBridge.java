package com.xianda.freshdelivery.delivery.routing;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

@Component
public class DeliveryConfigServiceBridge implements RoutingConfigSource {
    private static final Logger log = LoggerFactory.getLogger(DeliveryConfigServiceBridge.class);
    private static final String BEAN_NAME = "deliveryConfigService";
    private static final Object ABSENT = new Object();

    private final ApplicationContext applicationContext;
    private final Map<String, Method> methodCache = new ConcurrentHashMap<>();
    private volatile Object delegate;

    public DeliveryConfigServiceBridge(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Override
    public String getString(String key) {
        Object value = invoke("getString", key);
        return value == null ? null : String.valueOf(value);
    }

    @Override
    public Integer getInt(String key) {
        Object value = invoke("getInt", key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        return null;
    }

    @Override
    public Boolean getBool(String key) {
        Object value = invoke("getBool", key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        return null;
    }

    @Override
    public BigDecimal getDecimal(String key) {
        Object value = invoke("getDecimal", key);
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        return null;
    }

    private Object invoke(String methodName, String key) {
        Object target = resolveDelegate();
        if (target == null) {
            return null;
        }
        Method method = methodCache.computeIfAbsent(methodName, name -> lookup(target, name));
        if (method == null) {
            return null;
        }
        try {
            return method.invoke(target, key);
        } catch (ReflectiveOperationException | RuntimeException ex) {
            log.debug("配置项 {} 读取失败，回落默认值", key, ex);
            return null;
        }
    }

    private Method lookup(Object target, String methodName) {
        try {
            Method method = target.getClass().getMethod(methodName, String.class);
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException ex) {
            log.warn("DeliveryConfigService 缺少方法 {}(String)，路径规划将使用内置默认值", methodName);
            return null;
        }
    }

    private Object resolveDelegate() {
        Object current = delegate;
        if (current == null) {
            current = applicationContext.containsBean(BEAN_NAME) ? applicationContext.getBean(BEAN_NAME) : ABSENT;
            delegate = current;
            if (current == ABSENT) {
                log.info("未发现 {} Bean，路径规划与 ETA 使用内置默认参数", BEAN_NAME);
            }
        }
        return current == ABSENT ? null : current;
    }
}
