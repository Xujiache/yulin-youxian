package com.xianda.freshdelivery;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.xianda.freshdelivery.common.BusinessException;
import com.xianda.freshdelivery.config.WechatMiniAppProperties;
import com.xianda.freshdelivery.dto.WxLoginRequest;
import com.xianda.freshdelivery.service.WechatMiniAppClient;
import org.junit.jupiter.api.Test;

class WechatMiniAppClientTests {
    @Test
    void requiresMiniAppCredentials() {
        WechatMiniAppProperties properties = new WechatMiniAppProperties();
        WechatMiniAppClient client = new WechatMiniAppClient(properties);

        assertThrows(BusinessException.class, () -> client.resolveOpenId(new WxLoginRequest("real-code", null, null)));
    }
}
