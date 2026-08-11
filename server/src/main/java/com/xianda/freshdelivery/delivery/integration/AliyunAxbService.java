package com.xianda.freshdelivery.delivery.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// TODO 未实测：BindAxb / UnbindSubscription 与 HMAC-SHA1 签名按阿里云 RPC 规范实现，尚未接入真实账号联调。
public class AliyunAxbService implements PrivacyNumberService {
    public static final String NAME = "ALIYUN_AXB";
    public static final String IMPLEMENTATION_NOTE =
            "BindAxb/UnbindSubscription 按阿里云 RPC 签名规范实现，未接入真实账号实测";

    private static final Logger log = LoggerFactory.getLogger(AliyunAxbService.class);
    private static final String ENDPOINT = "https://dyplsapi.aliyuncs.com";
    private static final String API_VERSION = "2017-05-25";
    private static final String SIGNATURE_METHOD = "HMAC-SHA1";
    private static final String SIGNATURE_VERSION = "1.0";
    private static final DateTimeFormatter ISO_UTC =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT);

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String accessKeyId;
    private final String accessKeySecret;
    private final String poolKey;

    public AliyunAxbService(ObjectMapper objectMapper, String accessKeyId, String accessKeySecret, String poolKey) {
        this.objectMapper = objectMapper;
        this.accessKeyId = accessKeyId;
        this.accessKeySecret = accessKeySecret;
        this.poolKey = poolKey;
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
        return notBlank(accessKeyId) && notBlank(accessKeySecret) && notBlank(poolKey);
    }

    @Override
    public PrivacyBinding bind(BindRequest request) {
        if (!available()) {
            return degraded(request, "隐私号未配置阿里云密钥，已降级为明文号码");
        }
        try {
            Map<String, String> parameters = new TreeMap<>();
            parameters.put("Action", "BindAxb");
            parameters.put("PoolKey", poolKey);
            parameters.put("PhoneNoA", request.riderPhone());
            parameters.put("PhoneNoB", request.customerPhone());
            parameters.put("Expiration", expiration(request));
            parameters.put("IsRecordingEnabled", "false");
            parameters.put("OutId", "task-" + request.taskId());
            JsonNode response = call(parameters);
            String code = response.path("Code").asText("");
            if (!"OK".equalsIgnoreCase(code)) {
                log.warn("阿里云 AXB 绑定失败：{} {}", code, response.path("Message").asText(""));
                return degraded(request, "隐私号绑定失败，已降级为明文号码");
            }
            JsonNode data = response.path("SecretBindDTO");
            String secretNo = data.path("SecretNo").asText(null);
            String subscriptionId = data.path("SubsId").asText(null);
            if (secretNo == null || secretNo.isBlank()) {
                return degraded(request, "隐私号返回为空，已降级为明文号码");
            }
            return new PrivacyBinding(NAME, subscriptionId, secretNo, false, STATUS_ACTIVE, null);
        } catch (RuntimeException exception) {
            log.warn("阿里云 AXB 绑定异常，降级为明文号码：{}", exception.getMessage());
            return degraded(request, "隐私号服务异常，已降级为明文号码");
        }
    }

    @Override
    public void release(long bindingId, String subscriptionId) {
        if (!available() || subscriptionId == null || subscriptionId.isBlank()) {
            return;
        }
        try {
            Map<String, String> parameters = new TreeMap<>();
            parameters.put("Action", "UnbindSubscription");
            parameters.put("PoolKey", poolKey);
            parameters.put("SubsId", subscriptionId);
            parameters.put("ProductType", "AXB_170");
            call(parameters);
        } catch (RuntimeException exception) {
            log.warn("阿里云 AXB 解绑失败，绑定 {}：{}", bindingId, exception.getMessage());
        }
    }

    private JsonNode call(Map<String, String> businessParameters) {
        Map<String, String> parameters = new TreeMap<>(businessParameters);
        parameters.put("Format", "JSON");
        parameters.put("Version", API_VERSION);
        parameters.put("AccessKeyId", accessKeyId);
        parameters.put("SignatureMethod", SIGNATURE_METHOD);
        parameters.put("SignatureVersion", SIGNATURE_VERSION);
        parameters.put("SignatureNonce", UUID.randomUUID().toString());
        parameters.put("Timestamp", ISO_UTC.format(ZonedDateTime.now(ZoneOffset.UTC)));
        String signature = sign("POST", parameters);
        StringBuilder body = new StringBuilder("Signature=").append(percentEncode(signature));
        parameters.forEach((key, value) ->
                body.append('&').append(percentEncode(key)).append('=').append(percentEncode(value)));
        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(ENDPOINT))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return objectMapper.readTree(response.body());
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("阿里云隐私号网络异常", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("阿里云隐私号调用被中断", exception);
        }
    }

    String sign(String httpMethod, Map<String, String> parameters) {
        StringBuilder canonicalized = new StringBuilder();
        new TreeMap<>(parameters).forEach((key, value) -> canonicalized.append('&')
                .append(percentEncode(key)).append('=').append(percentEncode(value)));
        String stringToSign = httpMethod + "&" + percentEncode("/") + "&"
                + percentEncode(canonicalized.substring(1));
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec((accessKeySecret + "&").getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            return Base64.getEncoder().encodeToString(mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException("阿里云隐私号签名失败", exception);
        }
    }

    private static String percentEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("*", "%2A")
                .replace("%7E", "~");
    }

    private String expiration(BindRequest request) {
        java.time.LocalDateTime expireAt = request.expireAt() == null
                ? java.time.LocalDateTime.now().plusHours(4)
                : request.expireAt();
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(expireAt);
    }

    private PrivacyBinding degraded(BindRequest request, String notice) {
        return new PrivacyBinding(NAME, null, request.customerPhone(), true, STATUS_DEGRADED, notice);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
