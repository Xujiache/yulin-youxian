package com.xianda.freshdelivery.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianda.freshdelivery.common.BusinessException;
import com.xianda.freshdelivery.config.WechatPayProperties;
import com.xianda.freshdelivery.dto.OrderDetailDto;
import com.xianda.freshdelivery.dto.PaymentDto;
import com.xianda.freshdelivery.dto.PaymentNotifyRequest;
import com.xianda.freshdelivery.dto.RefundDto;
import com.xianda.freshdelivery.dto.RefundNotifyRequest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;

@Service
public class WechatPayClient {
    private static final String SIGN_TYPE = "RSA";
    private static final String AUTH_SCHEMA = "WECHATPAY2-SHA256-RSA2048";
    private static final long CALLBACK_MAX_AGE_SECONDS = 300;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private static final DateTimeFormatter RFC3339_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    private final WechatPayProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .build();
    private final SecureRandom secureRandom = new SecureRandom();

    public WechatPayClient(WechatPayProperties properties) {
        this.properties = properties;
    }

    public String appId() {
        return properties.getAppId();
    }

    public String mchId() {
        return properties.getMchId();
    }

    public boolean isPaymentConfigured() {
        return hasText(properties.getAppId())
                && hasText(properties.getMchId())
                && hasText(properties.getApiV3Key())
                && hasText(properties.getMerchantSerialNo())
                && hasText(properties.getNotifyUrl())
                && hasPrivateKey()
                // 微信支付支持“平台证书”或“微信支付公钥”两种验签材料。
                // 使用公钥模式时，平台证书路径为空是正常配置，不能据此禁止下单。
                && hasVerificationMaterial();
    }

    public boolean isCallbackVerificationConfigured() {
        return hasText(properties.getApiV3Key()) && hasVerificationMaterial();
    }

    public boolean isRefundConfigured() {
        return isPaymentConfigured() && hasText(properties.getRefundNotifyUrl());
    }

    public PaymentDto createJsapiPayment(OrderDetailDto order, String openId) {
        ensurePaymentConfigured();
        if (!hasText(openId)) {
            throw new BusinessException(500, "真实微信支付需要微信 openId");
        }

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("appid", properties.getAppId());
        requestBody.put("mchid", properties.getMchId());
        requestBody.put("description", "禹邻优鲜订单");
        requestBody.put("out_trade_no", paymentOrderNo(order));
        requestBody.put("notify_url", properties.getNotifyUrl());
        requestBody.put("amount", Map.of("total", order.payableAmount(), "currency", "CNY"));
        requestBody.put("payer", Map.of("openid", openId));
        if (hasText(order.paymentExpireAt())) {
            String expireAt = order.paymentExpireAt();
            try {
                // Parse potentially nanosecond-precision string like 2026-08-10T20:00:00.123456789+08:00
                ZonedDateTime zonedDateTime = ZonedDateTime.parse(expireAt);
                requestBody.put("time_expire", zonedDateTime.format(RFC3339_FORMATTER));
            } catch (Exception parseException) {
                // Fallback: try to strip nanoseconds manually (e.g., trim ".xxx" before "+")
                int dotIndex = expireAt.indexOf('.');
                int plusIndex = expireAt.indexOf('+', dotIndex > 0 ? dotIndex : 0);
                if (dotIndex > 0 && plusIndex > dotIndex) {
                    expireAt = expireAt.substring(0, dotIndex) + expireAt.substring(plusIndex);
                }
                requestBody.put("time_expire", expireAt);
            }
        }
        JsonNode response = postJson("/v3/pay/transactions/jsapi", requestBody);
        String prepayId = response.path("prepay_id").asText();
        if (!hasText(prepayId)) {
            throw new BusinessException(502, "微信支付下单未返回 prepay_id");
        }
        String timeStamp = String.valueOf(Instant.now().getEpochSecond());
        String nonceStr = nonce();
        String packageValue = "prepay_id=" + prepayId;
        String paySign = signMiniAppPayment(timeStamp, nonceStr, packageValue);
        return new PaymentDto(order.id(), order.orderNo(), order.status(), timeStamp, nonceStr, packageValue, SIGN_TYPE, paySign);
    }

    public PaymentNotifyRequest queryPayment(OrderDetailDto order) {
        ensurePaymentConfigured();
        String path = "/v3/pay/transactions/out-trade-no/" + paymentOrderNo(order) + "?mchid=" + properties.getMchId();
        JsonNode response = getJson(path);
        String tradeState = text(response, "trade_state");
        String transactionId = response.path("transaction_id").asText("");
        Integer totalAmount = response.path("amount").path("total").isInt()
                ? response.path("amount").path("total").asInt()
                : null;
        return new PaymentNotifyRequest(
                paymentOrderNo(order),
                transactionId,
                tradeState,
                response.path("appid").asText(properties.getAppId()),
                response.path("mchid").asText(properties.getMchId()),
                totalAmount
        );
    }

    public void closePayment(OrderDetailDto order) {
        ensurePaymentConfigured();
        try {
            postJson(
                    "/v3/pay/transactions/out-trade-no/" + paymentOrderNo(order) + "/close",
                    Map.of("mchid", properties.getMchId())
            );
        } catch (BusinessException exception) {
            if (exception.getMessage() != null
                    && (exception.getMessage().contains("ORDER_NOT_EXIST")
                    || exception.getMessage().contains("ORDER_CLOSED"))) {
                return;
            }
            throw exception;
        }
    }

    public RefundNotifyRequest requestRefund(RefundDto refund, OrderDetailDto order) {
        ensureRefundConfigured();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(
                "out_trade_no",
                hasText(refund.paymentOrderNo()) ? refund.paymentOrderNo() : paymentOrderNo(order)
        );
        body.put("out_refund_no", refund.refundNo());
        if (hasText(refund.reason())) {
            body.put("reason", refund.reason());
        }
        body.put("notify_url", properties.getRefundNotifyUrl());
        body.put("amount", Map.of(
                "refund", refund.refundAmount(),
                "total", order.paidAmount() == null || order.paidAmount() <= 0
                        ? order.payableAmount()
                        : order.paidAmount(),
                "currency", "CNY"
        ));
        JsonNode response = postJson("/v3/refund/domestic/refunds", body);
        return refundResult(response, refund.refundNo());
    }

    public RefundNotifyRequest queryRefund(RefundDto refund) {
        ensureRefundConfigured();
        JsonNode response = getJson("/v3/refund/domestic/refunds/" + refund.refundNo());
        return refundResult(response, refund.refundNo());
    }

    private String paymentOrderNo(OrderDetailDto order) {
        return hasText(order.paymentOrderNo()) ? order.paymentOrderNo() : order.orderNo();
    }

    public PaymentNotifyRequest parsePaymentNotify(String body, String timestamp, String nonce, String serial, String signature) {
        JsonNode payload = callbackPayload(body, timestamp, nonce, serial, signature);
        JsonNode decrypted = decryptResource(payload.path("resource"));
        return new PaymentNotifyRequest(
                text(decrypted, "out_trade_no"),
                text(decrypted, "transaction_id"),
                text(decrypted, "trade_state"),
                text(decrypted, "appid"),
                text(decrypted, "mchid"),
                decrypted.path("amount").path("total").isInt()
                        ? decrypted.path("amount").path("total").asInt()
                        : null
        );
    }

    public RefundNotifyRequest parseRefundNotify(String body, String timestamp, String nonce, String serial, String signature) {
        JsonNode payload = callbackPayload(body, timestamp, nonce, serial, signature);
        JsonNode decrypted = decryptResource(payload.path("resource"));
        return new RefundNotifyRequest(
                text(decrypted, "out_refund_no"),
                text(decrypted, "refund_status")
        );
    }

    private JsonNode callbackPayload(String body, String timestamp, String nonce, String serial, String signature) {
        try {
            JsonNode payload = objectMapper.readTree(body);
            if (!payload.hasNonNull("resource")) {
                throw new BusinessException(401, "微信支付回调缺少加密资源");
            }
            verifyCallbackSignature(body, timestamp, nonce, serial, signature);
            return payload;
        } catch (JsonProcessingException exception) {
            throw new BusinessException(400, "微信支付回调 JSON 格式错误");
        }
    }

    private void verifyCallbackSignature(String body, String timestamp, String nonce, String serial, String wechatSignature) {
        if (!isCallbackVerificationConfigured()
                || !hasText(timestamp)
                || !hasText(nonce)
                || !hasText(serial)
                || !hasText(wechatSignature)) {
            throw new BusinessException(401, "微信支付回调验签配置不完整");
        }
        try {
            verifyCallbackTimestamp(timestamp);
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(loadVerificationKey(serial, 401));
            verifier.update((timestamp + "\n" + nonce + "\n" + body + "\n").getBytes(StandardCharsets.UTF_8));
            boolean valid = verifier.verify(Base64.getDecoder().decode(wechatSignature));
            if (!valid) {
                throw new BusinessException(401, "微信支付回调签名无效");
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(401, "微信支付回调验签失败");
        }
    }

    private void verifyCallbackTimestamp(String timestamp) {
        try {
            long callbackTimestamp = Long.parseLong(timestamp);
            if (Math.abs(Instant.now().getEpochSecond() - callbackTimestamp) > CALLBACK_MAX_AGE_SECONDS) {
                throw new BusinessException(401, "微信支付回调时间戳已过期");
            }
        } catch (NumberFormatException exception) {
            throw new BusinessException(401, "微信支付回调时间戳无效");
        }
    }

    private JsonNode decryptResource(JsonNode resource) {
        if (!hasText(properties.getApiV3Key())) {
            throw new BusinessException(500, "微信支付 API v3 密钥未配置");
        }
        try {
            byte[] key = properties.getApiV3Key().getBytes(StandardCharsets.UTF_8);
            if (key.length != 32) {
                throw new BusinessException(500, "微信支付 API v3 密钥必须为 32 字节");
            }
            String associatedData = resource.path("associated_data").asText("");
            String nonce = text(resource, "nonce");
            byte[] ciphertext = Base64.getDecoder().decode(text(resource, "ciphertext"));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce.getBytes(StandardCharsets.UTF_8)));
            if (hasText(associatedData)) {
                cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
            }
            byte[] plaintext = cipher.doFinal(ciphertext);
            return objectMapper.readTree(plaintext);
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(400, "微信支付回调资源解密失败");
        }
    }

    private JsonNode postJson(String path, Map<String, Object> body) {
        try {
            return requestJson("POST", path, objectMapper.writeValueAsString(body));
        } catch (JsonProcessingException exception) {
            throw new BusinessException(500, "微信支付请求数据序列化失败");
        }
    }

    private JsonNode getJson(String path) {
        return requestJson("GET", path, "");
    }

    private JsonNode requestJson(String method, String path, String body) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(normalizedBaseUrl() + path))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Authorization", authorization(method, path, body))
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json");
            if (hasPublicKeyConfig()) {
                builder.header("Wechatpay-Serial", properties.getPublicKeyId());
            }
            HttpRequest request = "GET".equals(method)
                    ? builder.GET().build()
                    : builder.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BusinessException(502, "微信支付接口调用失败: " + response.body());
            }
            verifyResponseSignature(response);
            return response.body().isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(response.body());
        } catch (BusinessException exception) {
            throw exception;
        } catch (HttpTimeoutException exception) {
            throw new BusinessException(504, "微信支付接口请求超时");
        } catch (IOException exception) {
            throw new BusinessException(502, "微信支付接口响应解析失败");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(502, "微信支付接口调用被中断");
        }
    }

    private void verifyResponseSignature(HttpResponse<String> response) {
        String body = response.body() == null ? "" : response.body();
        String timestamp = response.headers().firstValue("Wechatpay-Timestamp").orElse("");
        String nonce = response.headers().firstValue("Wechatpay-Nonce").orElse("");
        String serial = response.headers().firstValue("Wechatpay-Serial").orElse("");
        String signature = response.headers().firstValue("Wechatpay-Signature").orElse("");
        if (!isCallbackVerificationConfigured()
                || !hasText(timestamp)
                || !hasText(nonce)
                || !hasText(serial)
                || !hasText(signature)) {
            throw new BusinessException(502, "微信支付接口应答验签配置或响应头不完整");
        }
        try {
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(loadVerificationKey(serial, 502));
            verifier.update((timestamp + "\n" + nonce + "\n" + body + "\n").getBytes(StandardCharsets.UTF_8));
            if (!verifier.verify(Base64.getDecoder().decode(signature))) {
                throw new BusinessException(502, "微信支付接口应答签名无效");
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(502, "微信支付接口应答验签失败");
        }
    }

    private PublicKey loadVerificationKey(String serial, int errorCode) {
        if (hasPublicKeyConfig()) {
            if (!properties.getPublicKeyId().trim().equalsIgnoreCase(serial.trim())) {
                throw new BusinessException(errorCode, "微信支付公钥 ID 不匹配");
            }
            return loadWechatPayPublicKey();
        }
        X509Certificate certificate = loadPlatformCertificate();
        String certificateSerial = certificate.getSerialNumber().toString(16).toUpperCase(Locale.ROOT);
        if (!certificateSerial.equals(serial.trim().toUpperCase(Locale.ROOT))) {
            throw new BusinessException(errorCode, "微信支付平台证书序列号不匹配");
        }
        return certificate.getPublicKey();
    }

    private String authorization(String method, String canonicalUrl, String body) {
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String nonceStr = nonce();
        String message = method + "\n" + canonicalUrl + "\n" + timestamp + "\n" + nonceStr + "\n" + body + "\n";
        String signature = sign(message);
        return AUTH_SCHEMA
                + " mchid=\"" + properties.getMchId() + "\","
                + "nonce_str=\"" + nonceStr + "\","
                + "timestamp=\"" + timestamp + "\","
                + "serial_no=\"" + properties.getMerchantSerialNo() + "\","
                + "signature=\"" + signature + "\"";
    }

    private String signMiniAppPayment(String timeStamp, String nonceStr, String packageValue) {
        String message = properties.getAppId() + "\n" + timeStamp + "\n" + nonceStr + "\n" + packageValue + "\n";
        return sign(message);
    }

    private String sign(String message) {
        try {
            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(loadPrivateKey());
            signer.update(message.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signer.sign());
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(500, "微信支付签名生成失败");
        }
    }

    private PrivateKey loadPrivateKey() {
        try {
            String pem = hasText(properties.getPrivateKey())
                    ? properties.getPrivateKey().replace("\\n", "\n")
                    : Files.readString(Path.of(properties.getPrivateKeyPath()), StandardCharsets.UTF_8);
            if (pem.contains("BEGIN RSA PRIVATE KEY")) {
                throw new BusinessException(500, "微信支付商户私钥需使用 PKCS8 格式 BEGIN PRIVATE KEY");
            }
            String normalized = pem
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] decoded = Base64.getDecoder().decode(normalized);
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(decoded));
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(500, "微信支付商户私钥读取失败");
        }
    }

    private X509Certificate loadPlatformCertificate() {
        try {
            byte[] data = Files.readAllBytes(Path.of(properties.getPlatformCertificatePath()));
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            return (X509Certificate) factory.generateCertificate(new ByteArrayInputStream(data));
        } catch (Exception exception) {
            throw new BusinessException(500, "微信支付平台证书读取失败");
        }
    }

    private PublicKey loadWechatPayPublicKey() {
        try {
            String pem = Files.readString(Path.of(properties.getPublicKeyPath()), StandardCharsets.UTF_8);
            String normalized = pem
                    .replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] decoded = Base64.getDecoder().decode(normalized);
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(decoded));
        } catch (Exception exception) {
            throw new BusinessException(500, "微信支付公钥读取失败");
        }
    }

    private void ensurePaymentConfigured() {
        if (!isPaymentConfigured()) {
            throw new BusinessException(500, "微信支付配置不完整");
        }
    }

    private void ensureRefundConfigured() {
        ensurePaymentConfigured();
        if (!hasText(properties.getRefundNotifyUrl())) {
            throw new BusinessException(500, "微信退款通知地址未配置");
        }
    }

    private boolean hasPrivateKey() {
        return hasText(properties.getPrivateKey()) || hasText(properties.getPrivateKeyPath());
    }

    private boolean hasVerificationMaterial() {
        return hasPublicKeyConfig() || hasText(properties.getPlatformCertificatePath());
    }

    private boolean hasPublicKeyConfig() {
        return hasText(properties.getPublicKeyId()) && hasText(properties.getPublicKeyPath());
    }

    private String normalizedBaseUrl() {
        String baseUrl = hasText(properties.getBaseUrl()) ? properties.getBaseUrl() : "https://api.mch.weixin.qq.com";
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    private String nonce() {
        byte[] bytes = new byte[16];
        secureRandom.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private String text(JsonNode node, String field) {
        String value = node.path(field).asText();
        if (!hasText(value)) {
            throw new BusinessException(400, "微信支付回调缺少字段: " + field);
        }
        return value;
    }

    private RefundNotifyRequest refundResult(JsonNode response, String fallbackRefundNo) {
        String refundNo = response.path("out_refund_no").asText(fallbackRefundNo);
        String status = response.path("status").asText("");
        if (!hasText(status)) {
            throw new BusinessException(502, "微信退款接口未返回退款状态");
        }
        return new RefundNotifyRequest(refundNo, status);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
