package com.xianda.freshdelivery.delivery.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianda.freshdelivery.delivery.common.DeliveryProperties;
import com.xianda.freshdelivery.delivery.routing.AmapWebKeyResolver;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class IntegrationConfiguration {
    private static final Logger log = LoggerFactory.getLogger(IntegrationConfiguration.class);

    @Bean
    @ConditionalOnMissingBean(PushService.class)
    public PushService pushService(DeliveryProperties properties, MessagePushDeviceDao pushDeviceDao) {
        ObjectMapper objectMapper = IntegrationJson.mapper();
        DeliveryProperties.Push push = properties == null ? null : properties.push();
        String provider = push == null ? null : push.provider();
        if (JPushService.NAME.equalsIgnoreCase(normalize(provider))) {
            JPushService jpush = new JPushService(pushDeviceDao, objectMapper, push.jpushAppKey(), push.jpushMasterSecret());
            if (jpush.available()) {
                return jpush;
            }
            log.warn("推送服务商配置为 JPUSH 但缺少 AppKey/MasterSecret，降级为轮询");
        }
        return new NoopPushService();
    }

    @Bean
    @ConditionalOnMissingBean(PrivacyNumberService.class)
    public PrivacyNumberService privacyNumberService(DeliveryProperties properties) {
        ObjectMapper objectMapper = IntegrationJson.mapper();
        DeliveryProperties.PrivacyNumber privacy = properties == null ? null : properties.privacyNumber();
        String provider = privacy == null ? null : privacy.provider();
        if (AliyunAxbService.NAME.equalsIgnoreCase(normalize(provider))
                || "ALIYUN".equalsIgnoreCase(normalize(provider))) {
            AliyunAxbService aliyun = new AliyunAxbService(objectMapper, privacy.aliyunAccessKeyId(),
                    privacy.aliyunAccessKeySecret(), privacy.poolKey());
            if (aliyun.available()) {
                return aliyun;
            }
            log.warn("隐私号服务商配置为阿里云 AXB 但缺少密钥，降级为明文号码并登记 DEGRADED");
        }
        return new NoopPrivacyNumberService();
    }

    @Bean
    @ConditionalOnMissingBean(WeatherService.class)
    public WeatherService weatherService(
            DeliveryProperties properties,
            AmapWebKeyResolver webKeyResolver
    ) {
        ObjectMapper objectMapper = IntegrationJson.mapper();
        DeliveryProperties.Store store = properties == null ? null : properties.store();
        if (!webKeyResolver.enabled()) {
            return new NoopWeatherService();
        }
        return new AmapWeatherService(objectMapper, webKeyResolver,
                store == null ? null : store.lat(),
                store == null ? null : store.lng());
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
