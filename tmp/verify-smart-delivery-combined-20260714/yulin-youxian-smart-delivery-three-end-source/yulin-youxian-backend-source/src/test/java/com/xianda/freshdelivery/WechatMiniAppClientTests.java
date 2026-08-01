package com.xianda.freshdelivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.xianda.freshdelivery.common.BusinessException;
import com.xianda.freshdelivery.config.WechatMiniAppProperties;
import com.xianda.freshdelivery.dto.WxLoginRequest;
import com.xianda.freshdelivery.service.WechatMiniAppClient;
import org.junit.jupiter.api.Test;

class WechatMiniAppClientTests {
    @Test
    void developmentModeUsesStableClientIdOpenId() {
        WechatMiniAppProperties properties = new WechatMiniAppProperties();
        properties.setDevelopmentMode(true);
        WechatMiniAppClient client = new WechatMiniAppClient(properties);

        String openId = client.resolveOpenId(new WxLoginRequest("dev-code", "client-1", null, null));

        assertEquals("dev_client-1", openId);
    }

    @Test
    void productionModeRequiresMiniAppCredentials() {
        WechatMiniAppProperties properties = new WechatMiniAppProperties();
        properties.setDevelopmentMode(false);
        WechatMiniAppClient client = new WechatMiniAppClient(properties);

        assertThrows(BusinessException.class, () -> client.resolveOpenId(new WxLoginRequest("real-code", null, null, null)));
    }
}
