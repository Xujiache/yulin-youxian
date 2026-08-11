package com.xianda.freshdelivery.delivery;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianda.freshdelivery.delivery.account.NoopRiderTokenResolver;
import com.xianda.freshdelivery.delivery.account.RiderTokenResolver;
import com.xianda.freshdelivery.delivery.common.BcryptPasswordHasher;
import com.xianda.freshdelivery.delivery.common.DeliveryProperties;
import com.xianda.freshdelivery.delivery.common.PasswordHasher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(DeliveryProperties.class)
public class DeliveryDomainConfiguration {

    @Bean
    @ConditionalOnMissingBean(ObjectMapper.class)
    public ObjectMapper deliveryObjectMapper() {
        return new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @Bean
    @ConditionalOnMissingBean(RiderTokenResolver.class)
    public RiderTokenResolver riderTokenResolver() {
        return new NoopRiderTokenResolver();
    }

    @Bean
    @ConditionalOnMissingBean(PasswordHasher.class)
    public PasswordHasher passwordHasher() {
        return new BcryptPasswordHasher();
    }
}
