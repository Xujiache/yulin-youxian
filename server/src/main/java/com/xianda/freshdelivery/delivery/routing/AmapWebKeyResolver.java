package com.xianda.freshdelivery.delivery.routing;

import com.xianda.freshdelivery.delivery.common.DeliveryProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Single source of truth for the server-side AMap Web service key.
 * Environment/application properties take precedence and therefore require a
 * restart to change; the database value is the hot-update fallback.
 */
@Component
public class AmapWebKeyResolver {
    private final ObjectProvider<DeliveryProperties> deliveryProperties;
    private final RoutingConfigSource configSource;

    public AmapWebKeyResolver(
            ObjectProvider<DeliveryProperties> deliveryProperties,
            RoutingConfigSource configSource
    ) {
        this.deliveryProperties = deliveryProperties;
        this.configSource = configSource;
    }

    public boolean enabled() {
        DeliveryProperties properties = properties();
        return properties != null && properties.amap() != null && properties.amap().enabled();
    }

    public String resolve() {
        DeliveryProperties properties = properties();
        String environmentKey = properties == null || properties.amap() == null
                ? null
                : properties.amap().webKey();
        if (hasText(environmentKey)) {
            return environmentKey.trim();
        }
        String databaseKey = configSource == null
                ? null
                : configSource.getString(RoutingConfigKeys.AMAP_WEB_KEY);
        return hasText(databaseKey) ? databaseKey.trim() : null;
    }

    private DeliveryProperties properties() {
        return deliveryProperties == null ? null : deliveryProperties.getIfAvailable();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
