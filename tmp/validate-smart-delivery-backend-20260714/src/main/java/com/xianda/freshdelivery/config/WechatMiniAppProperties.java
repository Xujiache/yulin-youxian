package com.xianda.freshdelivery.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "wechat.miniapp")
public class WechatMiniAppProperties {
    private boolean developmentMode = false;
    private String appId = "";
    private String appSecret = "";
    private String code2SessionUrl = "https://api.weixin.qq.com/sns/jscode2session";

    public boolean isDevelopmentMode() {
        return developmentMode;
    }

    public void setDevelopmentMode(boolean developmentMode) {
        this.developmentMode = developmentMode;
    }

    public String getAppId() {
        return appId;
    }

    public void setAppId(String appId) {
        this.appId = appId;
    }

    public String getAppSecret() {
        return appSecret;
    }

    public void setAppSecret(String appSecret) {
        this.appSecret = appSecret;
    }

    public String getCode2SessionUrl() {
        return code2SessionUrl;
    }

    public void setCode2SessionUrl(String code2SessionUrl) {
        this.code2SessionUrl = code2SessionUrl;
    }
}
