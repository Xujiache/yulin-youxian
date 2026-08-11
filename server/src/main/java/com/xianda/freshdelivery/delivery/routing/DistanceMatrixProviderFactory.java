package com.xianda.freshdelivery.delivery.routing;

import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class DistanceMatrixProviderFactory {
    private static final Logger log = LoggerFactory.getLogger(DistanceMatrixProviderFactory.class);

    private final List<DistanceMatrixProvider> providers;
    private final HaversineMatrixProvider fallback;
    private final RoutingSettings settings;

    public DistanceMatrixProviderFactory(List<DistanceMatrixProvider> providers,
                                         HaversineMatrixProvider fallback,
                                         RoutingSettings settings) {
        this.providers = providers;
        this.fallback = fallback;
        this.settings = settings;
    }

    public DistanceMatrixProvider resolve() {
        String configured = settings.matrixProvider();
        if (configured == null || configured.isBlank()) {
            return fallback;
        }
        String normalized = configured.trim().toUpperCase(Locale.ROOT);
        if (HaversineMatrixProvider.NAME.equals(normalized)) {
            return fallback;
        }
        for (DistanceMatrixProvider provider : providers) {
            if (!provider.name().equalsIgnoreCase(normalized)) {
                continue;
            }
            if (provider.available()) {
                return provider;
            }
            log.warn("矩阵提供方 {} 不可用（未启用或缺少 Key），回落 {}", normalized, HaversineMatrixProvider.NAME);
            return fallback;
        }
        log.warn("未知的 routing.matrix_provider={}，回落 {}", configured, HaversineMatrixProvider.NAME);
        return fallback;
    }

    public HaversineMatrixProvider fallback() {
        return fallback;
    }
}
