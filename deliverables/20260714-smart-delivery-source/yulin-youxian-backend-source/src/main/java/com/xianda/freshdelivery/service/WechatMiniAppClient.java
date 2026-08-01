package com.xianda.freshdelivery.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianda.freshdelivery.common.BusinessException;
import com.xianda.freshdelivery.config.WechatMiniAppProperties;
import com.xianda.freshdelivery.dto.WxLoginRequest;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Service;

@Service
public class WechatMiniAppClient {
    private final WechatMiniAppProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public WechatMiniAppClient(WechatMiniAppProperties properties) {
        this.properties = properties;
    }

    public String resolveOpenId(WxLoginRequest request) {
        if (properties.isDevelopmentMode()) {
            return developmentOpenId(request);
        }
        ensureConfigured();
        if (!hasText(request.code())) {
            throw new BusinessException(400, "微信登录 code 不能为空");
        }
        JsonNode response = code2Session(request.code().trim());
        int errorCode = response.path("errcode").asInt(0);
        if (errorCode != 0) {
            String message = response.path("errmsg").asText("微信 code2Session 调用失败");
            throw new BusinessException(401, "微信登录失败: " + message);
        }
        String openId = response.path("openid").asText();
        if (!hasText(openId)) {
            throw new BusinessException(401, "微信登录失败: 未返回 openid");
        }
        return openId;
    }

    public boolean isConfigured() {
        return hasText(properties.getAppId()) && hasText(properties.getAppSecret());
    }

    public boolean isDevelopmentMode() {
        return properties.isDevelopmentMode();
    }

    private JsonNode code2Session(String code) {
        String url = properties.getCode2SessionUrl()
                + "?appid=" + encode(properties.getAppId())
                + "&secret=" + encode(properties.getAppSecret())
                + "&js_code=" + encode(code)
                + "&grant_type=authorization_code";
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .GET()
                .header("Accept", "application/json")
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BusinessException(502, "微信 code2Session 接口调用失败");
            }
            return objectMapper.readTree(response.body());
        } catch (BusinessException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new BusinessException(502, "微信 code2Session 响应解析失败");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(502, "微信 code2Session 调用被中断");
        }
    }

    private void ensureConfigured() {
        if (!isConfigured()) {
            throw new BusinessException(500, "微信小程序登录配置不完整");
        }
    }

    private String developmentOpenId(WxLoginRequest request) {
        if (hasText(request.clientId())) {
            return "dev_" + request.clientId().trim();
        }
        return "dev_code_" + request.code().trim();
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
