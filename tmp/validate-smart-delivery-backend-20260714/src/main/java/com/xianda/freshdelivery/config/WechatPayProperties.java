package com.xianda.freshdelivery.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "wechat.pay")
public class WechatPayProperties {
    private boolean developmentMode = false;
    private String appId = "";
    private String mchId = "";
    private String apiV3Key = "";
    private String merchantSerialNo = "";
    private String privateKey = "";
    private String privateKeyPath = "";
    private String platformCertificatePath = "";
    private String baseUrl = "https://api.mch.weixin.qq.com";
    private String notifyUrl = "";
    private String refundNotifyUrl = "";

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

    public String getMchId() {
        return mchId;
    }

    public void setMchId(String mchId) {
        this.mchId = mchId;
    }

    public String getApiV3Key() {
        return apiV3Key;
    }

    public void setApiV3Key(String apiV3Key) {
        this.apiV3Key = apiV3Key;
    }

    public String getMerchantSerialNo() {
        return merchantSerialNo;
    }

    public void setMerchantSerialNo(String merchantSerialNo) {
        this.merchantSerialNo = merchantSerialNo;
    }

    public String getPrivateKey() {
        return privateKey;
    }

    public void setPrivateKey(String privateKey) {
        this.privateKey = privateKey;
    }

    public String getPrivateKeyPath() {
        return privateKeyPath;
    }

    public void setPrivateKeyPath(String privateKeyPath) {
        this.privateKeyPath = privateKeyPath;
    }

    public String getPlatformCertificatePath() {
        return platformCertificatePath;
    }

    public void setPlatformCertificatePath(String platformCertificatePath) {
        this.platformCertificatePath = platformCertificatePath;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getNotifyUrl() {
        return notifyUrl;
    }

    public void setNotifyUrl(String notifyUrl) {
        this.notifyUrl = notifyUrl;
    }

    public String getRefundNotifyUrl() {
        return refundNotifyUrl;
    }

    public void setRefundNotifyUrl(String refundNotifyUrl) {
        this.refundNotifyUrl = refundNotifyUrl;
    }
}
