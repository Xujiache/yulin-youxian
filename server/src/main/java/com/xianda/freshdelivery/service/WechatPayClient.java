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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;

@Service
public class WechatPayClient {
    private static final String SIGN_TYPE = "RSA";
    private static final String AUTH_SCHEMA = "WECHATPAY2-SHA256-RSA2048";

    private final WechatPayProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final SecureRandom secureRandom = new SecureRandom();

    public WechatPayClient(WechatPayProperties properties) {
        this.properties = properties;
    }

    public boolean isDevelopmentMode() {
        return properties.isDevelopmentMode();
    }

    public boolean isPaymentConfigured() {
        return hasText(properties.getAppId())
                && hasText(properties.getMchId())
                && hasText(properties.getMerchantSerialNo())
                && hasPrivateKey();
    }

    public boolean isCallbackVerificationConfigured() {
        return hasText(properties.getApiV3Key()) && hasText(properties.getPlatformCertificatePath());
    }

    public PaymentDto createJsapiPayment(OrderDetailDto order, String openId) {
        if (properties.isDevelopmentMode()) {
            return developmentPayment(order);
        }
        ensurePaymentConfigured();
        if (!hasText(openId) || openId.startsWith("dev_")) {
            throw new BusinessException(500, "真实微信支付需要正式微信 openId，请关闭小程序登录 development 模式并配置 AppID/AppSecret");
        }

        JsonNode response = postJson("/v3/pay/transactions/jsapi", Map.of(
                "appid", properties.getAppId(),
                "mchid", properties.getMchId(),
                "description", "禹邻优鲜订单",
                "out_trade_no", order.orderNo(),
                "notify_url", properties.getNotifyUrl(),
                "amount", Map.of("total", order.payableAmount(), "currency", "CNY"),
                "payer", Map.of("openid", openId)
        ));
        String prepayId = response.path("prepay_id").asText();
        if (!hasText(prepayId)) {
            throw new BusinessException(502, "微信支付下单未返回 prepay_id");
        }
        String timeStamp = String.valueOf(Instant.now().getEpochSecond());
        String nonceStr = nonce();
        String packageValue = "prepay_id=" + prepayId;
        String paySign = signMiniAppPayment(timeStamp, nonceStr, packageValue);
        return new PaymentDto(order.id(), order.orderNo(), order.status(), timeStamp, nonceStr, packageValue, SIGN_TYPE, paySign, false);
    }

    public void requestRefund(RefundDto refund, OrderDetailDto order) {
        if (properties.isDevelopmentMode()) {
            return;
        }
        ensureRefundConfigured();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("out_trade_no", order.orderNo());
        body.put("out_refund_no", refund.refundNo());
        if (hasText(refund.reason())) {
            body.put("reason", refund.reason());
        }
        body.put("notify_url", properties.getRefundNotifyUrl());
        body.put("amount", Map.of(
                "refund", refund.refundAmount(),
                "total", order.payableAmount(),
                "currency", "CNY"
        ));
        postJson("/v3/refund/domestic/refunds", body);
    }

    public PaymentNotifyRequest parsePaymentNotify(String body, String timestamp, String nonce, String signature) {
        JsonNode payload = callbackPayload(body, timestamp, nonce, signature);
        if (payload.has("resource")) {
            JsonNode decrypted = decryptResource(payload.path("resource"));
            return new PaymentNotifyRequest(
                    text(decrypted, "out_trade_no"),
                    text(decrypted, "transaction_id"),
                    text(decrypted, "trade_state")
            );
        }
        try {
            return objectMapper.readValue(body, PaymentNotifyRequest.class);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(400, "微信支付回调数据格式错误");
        }
    }

    public RefundNotifyRequest parseRefundNotify(String body, String timestamp, String nonce, String signature) {
        JsonNode payload = callbackPayload(body, timestamp, nonce, signature);
        if (payload.has("resource")) {
            JsonNode decrypted = decryptResource(payload.path("resource"));
            return new RefundNotifyRequest(
                    text(decrypted, "out_refund_no"),
                    text(decrypted, "refund_status")
            );
        }
        try {
            return objectMapper.readValue(body, RefundNotifyRequest.class);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(400, "微信退款回调数据格式错误");
        }
    }

    private PaymentDto developmentPayment(OrderDetailDto order) {
        String timeStamp = String.valueOf(Instant.now().getEpochSecond());
        return new PaymentDto(
                order.id(),
                order.orderNo(),
                order.status(),
                timeStamp,
                nonce(),
                "prepay_id=development_" + order.orderNo(),
                SIGN_TYPE,
                "development-pay-sign",
                true
        );
    }

    private JsonNode callbackPayload(String body, String timestamp, String nonce, String signature) {
        try {
            JsonNode payload = objectMapper.readTree(body);
            if (payload.has("resource")) {
                verifyCallbackSignature(body, timestamp, nonce, signature);
            }
            return payload;
        } catch (JsonProcessingException exception) {
            throw new BusinessException(400, "微信支付回调 JSON 格式错误");
        }
    }

    private void verifyCallbackSignature(String body, String timestamp, String nonce, String wechatSignature) {
        if (properties.isDevelopmentMode() && !hasText(properties.getPlatformCertificatePath())) {
            return;
        }
        if (!isCallbackVerificationConfigured() || !hasText(timestamp) || !hasText(nonce) || !hasText(wechatSignature)) {
            throw new BusinessException(401, "微信支付回调验签配置不完整");
        }
        try {
            X509Certificate certificate = loadPlatformCertificate();
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(certificate.getPublicKey());
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
            String json = objectMapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder(URI.create(normalizedBaseUrl() + path))
                    .header("Authorization", authorization("POST", path, json))
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BusinessException(502, "微信支付接口调用失败: " + response.body());
            }
            return response.body().isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(response.body());
        } catch (BusinessException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new BusinessException(502, "微信支付接口响应解析失败");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(502, "微信支付接口调用被中断");
        }
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

    private void ensurePaymentConfigured() {
        if (!isPaymentConfigured() || !hasText(properties.getNotifyUrl())) {
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

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
