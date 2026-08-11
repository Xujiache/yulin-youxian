package com.xianda.freshdelivery.delivery.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xianda.freshdelivery.config.WechatMiniAppProperties;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class SubscribeMessageService {
    public static final String NODE_ACCEPTED = "ACCEPTED";
    public static final String NODE_DEPARTED = "DEPARTED";
    public static final String NODE_ARRIVING = "ARRIVING";

    private static final Logger log = LoggerFactory.getLogger(SubscribeMessageService.class);
    private static final String TOKEN_ENDPOINT = "https://api.weixin.qq.com/cgi-bin/token";
    private static final String SEND_ENDPOINT = "https://api.weixin.qq.com/cgi-bin/message/subscribe/send";
    private static final Duration TOKEN_SAFETY_MARGIN = Duration.ofMinutes(5);

    private final WechatMiniAppProperties miniAppProperties;
    private final SubscribeTemplateDao subscribeTemplateDao;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    private volatile String accessToken;
    private volatile Instant accessTokenExpireAt = Instant.EPOCH;

    @Autowired
    public SubscribeMessageService(WechatMiniAppProperties miniAppProperties,
                                   SubscribeTemplateDao subscribeTemplateDao) {
        this(miniAppProperties, subscribeTemplateDao, IntegrationJson.mapper());
    }

    public SubscribeMessageService(WechatMiniAppProperties miniAppProperties,
                                   SubscribeTemplateDao subscribeTemplateDao,
                                   ObjectMapper objectMapper) {
        this.miniAppProperties = miniAppProperties;
        this.subscribeTemplateDao = subscribeTemplateDao;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public boolean available() {
        return notBlank(miniAppProperties.getAppId()) && notBlank(miniAppProperties.getAppSecret());
    }

    public int notifyAccepted(long taskId, String riderName, String etaText, String page) {
        return notify(taskId, NODE_ACCEPTED, orderedData("骑手已接单", riderName, etaText), page);
    }

    public int notifyDeparted(long taskId, String riderName, String etaText, String page) {
        return notify(taskId, NODE_DEPARTED, orderedData("骑手已出发", riderName, etaText), page);
    }

    public int notifyArriving(long taskId, String riderName, String etaText, String page) {
        return notify(taskId, NODE_ARRIVING, orderedData("骑手即将送达", riderName, etaText), page);
    }

    private static Map<String, String> orderedData(String statusText, String riderName, String etaText) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("thing1", statusText);
        data.put("thing2", riderName == null ? "配送员" : riderName);
        data.put("time3", etaText == null ? "" : etaText);
        return data;
    }

    public int notify(long taskId, String node, Map<String, String> data, String page) {
        if (!available()) {
            log.debug("小程序订阅消息未配置 appid/secret，跳过任务 {} 节点 {}", taskId, node);
            return 0;
        }
        List<Subscription> subscriptions;
        try {
            subscriptions = loadSubscriptions(taskId, node);
        } catch (RuntimeException exception) {
            log.warn("读取任务 {} 订阅记录失败：{}", taskId, exception.getMessage());
            return 0;
        }
        int sent = 0;
        for (Subscription subscription : subscriptions) {
            if (send(subscription, data, page)) {
                sent++;
            }
        }
        return sent;
    }

    List<Subscription> loadSubscriptions(long taskId, String node) {
        List<Subscription> result = new ArrayList<>();
        for (SubscribeTemplateDao.SubscribeRow row : subscribeTemplateDao.findSubscriptions(taskId)) {
            JsonNode detail = parse(row.detailJson());
            if (detail == null) {
                continue;
            }
            String openId = firstText(detail, "openId", "openid", "toUser", "touser");
            if (openId == null) {
                continue;
            }
            for (String templateId : templateIds(detail, node)) {
                result.add(new Subscription(openId, templateId));
            }
        }
        return result;
    }

    private List<String> templateIds(JsonNode detail, String node) {
        List<String> ids = new ArrayList<>();
        JsonNode byNode = detail.path("templates").path(node);
        if (byNode.isTextual()) {
            ids.add(byNode.asText());
            return ids;
        }
        if (byNode.isArray()) {
            byNode.forEach(item -> ids.add(item.asText()));
            return ids;
        }
        JsonNode templateIds = detail.path("templateIds");
        if (templateIds.isArray()) {
            templateIds.forEach(item -> ids.add(item.asText()));
        }
        String single = firstText(detail, "templateId", "template_id", "tmplId");
        if (single != null) {
            ids.add(single);
        }
        return ids.stream().filter(SubscribeMessageService::notBlank).distinct().toList();
    }

    private boolean send(Subscription subscription, Map<String, String> data, String page) {
        String token = accessToken();
        if (token == null) {
            return false;
        }
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("touser", subscription.openId());
            root.put("template_id", subscription.templateId());
            root.put("lang", "zh_CN");
            root.put("miniprogram_state", "formal");
            if (notBlank(page)) {
                root.put("page", page);
            }
            ObjectNode payload = root.putObject("data");
            Map<String, String> values = data == null ? Map.of() : new LinkedHashMap<>(data);
            values.forEach((key, value) -> payload.putObject(key).put("value", value));
            HttpRequest request = HttpRequest.newBuilder(URI.create(SEND_ENDPOINT + "?access_token=" + encode(token)))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(root.toString(), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode result = objectMapper.readTree(response.body());
            int errcode = result.path("errcode").asInt(0);
            if (errcode != 0) {
                log.info("订阅消息发送失败 errcode={} errmsg={}", errcode, result.path("errmsg").asText(""));
                return false;
            }
            return true;
        } catch (java.io.IOException | RuntimeException exception) {
            log.info("订阅消息发送异常，已静默忽略：{}", exception.getMessage());
            return false;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private synchronized String accessToken() {
        if (accessToken != null && Instant.now().isBefore(accessTokenExpireAt)) {
            return accessToken;
        }
        try {
            String url = TOKEN_ENDPOINT + "?grant_type=client_credential"
                    + "&appid=" + encode(miniAppProperties.getAppId())
                    + "&secret=" + encode(miniAppProperties.getAppSecret());
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode root = objectMapper.readTree(response.body());
            String token = root.path("access_token").asText(null);
            if (token == null || token.isBlank()) {
                log.info("获取小程序 access_token 失败：{}", root.path("errmsg").asText(""));
                return null;
            }
            long expiresIn = root.path("expires_in").asLong(7200L);
            accessToken = token;
            accessTokenExpireAt = Instant.now().plusSeconds(expiresIn).minus(TOKEN_SAFETY_MARGIN);
            return token;
        } catch (java.io.IOException | RuntimeException exception) {
            log.info("获取小程序 access_token 异常，已静默忽略：{}", exception.getMessage());
            return null;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private JsonNode parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            return null;
        }
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isTextual() && notBlank(value.asText())) {
                return value.asText().trim();
            }
        }
        return null;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    record Subscription(String openId, String templateId) {
    }
}
