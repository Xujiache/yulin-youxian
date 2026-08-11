package com.xianda.freshdelivery.delivery.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JPushService implements PushService {
    public static final String NAME = "JPUSH";

    private static final Logger log = LoggerFactory.getLogger(JPushService.class);
    private static final String ENDPOINT = "https://api.jpush.cn/v3/push";
    private static final int TIME_TO_LIVE_SECONDS = 600;

    private final MessagePushDeviceDao pushDeviceDao;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String appKey;
    private final String masterSecret;

    public JPushService(MessagePushDeviceDao pushDeviceDao, ObjectMapper objectMapper, String appKey, String masterSecret) {
        this.pushDeviceDao = pushDeviceDao;
        this.objectMapper = objectMapper;
        this.appKey = appKey;
        this.masterSecret = masterSecret;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public String provider() {
        return NAME;
    }

    @Override
    public boolean available() {
        return appKey != null && !appKey.isBlank() && masterSecret != null && !masterSecret.isBlank();
    }

    @Override
    public PushResult push(PushRequest request) {
        if (!available()) {
            return PushResult.skipped("极光推送未配置 AppKey/MasterSecret，已降级为轮询");
        }
        if (request.riderId() == null) {
            return PushResult.skipped("广播消息不走单设备推送，已降级为轮询");
        }
        List<String> registrationIds;
        try {
            registrationIds = pushDeviceDao.findRegistrationIds(request.riderId());
        } catch (RuntimeException exception) {
            log.warn("查询骑手 {} 推送注册号失败：{}", request.riderId(), exception.getMessage());
            return PushResult.failed("查询推送注册号失败");
        }
        if (registrationIds.isEmpty()) {
            return PushResult.skipped("骑手未上报推送注册号，已降级为轮询");
        }
        try {
            return send(request, registrationIds);
        } catch (RuntimeException exception) {
            log.warn("极光推送失败，降级为轮询：{}", exception.getMessage());
            return PushResult.failed(trim(exception.getMessage()));
        }
    }

    private PushResult send(PushRequest request, List<String> registrationIds) {
        String body = buildBody(request, registrationIds);
        String credential = Base64.getEncoder()
                .encodeToString((appKey + ":" + masterSecret).getBytes(StandardCharsets.UTF_8));
        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(ENDPOINT))
                .timeout(Duration.ofSeconds(5))
                .header("Authorization", "Basic " + credential)
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                return PushResult.failed("极光推送 HTTP " + response.statusCode() + " " + trim(response.body()));
            }
            JsonNode root = objectMapper.readTree(response.body());
            if (root.hasNonNull("error")) {
                return PushResult.failed("极光推送返回错误：" + trim(root.path("error").toString()));
            }
            return PushResult.sent();
        } catch (java.io.IOException exception) {
            return PushResult.failed("极光推送网络异常：" + trim(exception.getMessage()));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return PushResult.failed("极光推送被中断");
        }
    }

    private String buildBody(PushRequest request, List<String> registrationIds) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("platform", "android");
        ObjectNode audience = root.putObject("audience");
        ArrayNode ids = audience.putArray("registration_id");
        registrationIds.forEach(ids::add);
        ObjectNode notification = root.putObject("notification");
        ObjectNode android = notification.putObject("android");
        android.put("alert", request.content());
        android.put("title", request.title());
        android.put("priority", isUrgent(request.priority()) ? 2 : 1);
        android.put("alert_type", isUrgent(request.priority()) ? 7 : 1);
        ObjectNode extras = android.putObject("extras");
        extras.put("messageType", request.messageType());
        extras.put("needVoice", request.needVoice());
        if (request.linkType() != null) {
            extras.put("linkType", request.linkType());
        }
        if (request.linkTarget() != null) {
            extras.put("linkTarget", request.linkTarget());
        }
        if (request.messageId() != null) {
            extras.put("messageId", request.messageId());
        }
        ObjectNode options = root.putObject("options");
        options.put("time_to_live", TIME_TO_LIVE_SECONDS);
        options.put("apns_production", false);
        return root.toString();
    }

    private boolean isUrgent(String priority) {
        return "URGENT".equalsIgnoreCase(priority) || "HIGH".equalsIgnoreCase(priority);
    }

    private static String trim(String value) {
        if (value == null) {
            return "";
        }
        String text = value.replaceAll("\\s+", " ").trim();
        return text.length() > 200 ? text.substring(0, 200) : text;
    }
}
