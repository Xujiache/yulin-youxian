package com.xianda.freshdelivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.xianda.freshdelivery.common.BusinessException;
import com.xianda.freshdelivery.config.WechatPayProperties;
import com.xianda.freshdelivery.dto.OrderDetailDto;
import com.xianda.freshdelivery.dto.PaymentNotifyRequest;
import com.xianda.freshdelivery.service.WechatPayClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WechatPayClientTests {
    @TempDir
    Path tempDir;

    @Test
    void paymentRequiresCompleteWechatPayConfiguration() {
        WechatPayClient client = new WechatPayClient(new WechatPayProperties());
        OrderDetailDto order = new OrderDetailDto(1L, "XD20260705001", "待支付", null, "今日 14:00-16:00", List.of(), 0, 0, 0, 100, 0, 0, "", "", "", "", 1000L, List.of(), "");

        assertThrows(BusinessException.class, () -> client.createJsapiPayment(order, "real-openid"));
    }

    @Test
    void publicKeyModeDoesNotRequirePlatformCertificateForPaymentConfiguration() throws Exception {
        Path publicKeyPath = tempDir.resolve("wechatpay_pub_key.pem");
        Files.writeString(publicKeyPath, "-----BEGIN PUBLIC KEY-----\nTEST\n-----END PUBLIC KEY-----\n");

        WechatPayProperties properties = new WechatPayProperties();
        properties.setAppId("wx-test");
        properties.setMchId("1900000109");
        properties.setApiV3Key("1234567890abcdefghijklmnopqrstuv");
        properties.setMerchantSerialNo("MERCHANT_SERIAL");
        properties.setPrivateKey("configured-private-key");
        properties.setPublicKeyId("PUB_KEY_ID_TEST");
        properties.setPublicKeyPath(publicKeyPath.toString());
        properties.setNotifyUrl("https://example.test/api/wx/payments/wechat/notify");

        assertEquals(true, new WechatPayClient(properties).isPaymentConfigured());
    }

    @Test
    void paymentConfigurationStillRequiresEitherPublicKeyOrPlatformCertificate() {
        WechatPayProperties properties = new WechatPayProperties();
        properties.setAppId("wx-test");
        properties.setMchId("1900000109");
        properties.setApiV3Key("1234567890abcdefghijklmnopqrstuv");
        properties.setMerchantSerialNo("MERCHANT_SERIAL");
        properties.setPrivateKey("configured-private-key");
        properties.setNotifyUrl("https://example.test/api/wx/payments/wechat/notify");

        assertEquals(false, new WechatPayClient(properties).isPaymentConfigured());
    }

    @Test
    void rejectsUnsignedDirectPaymentNotifyPayload() {
        WechatPayClient client = new WechatPayClient(new WechatPayProperties());

        assertThrows(BusinessException.class, () -> client.parsePaymentNotify(
                "{\"orderNo\":\"XD1\",\"transactionId\":\"TX1\",\"tradeState\":\"SUCCESS\"}",
                null,
                null,
                null,
                null
        ));
    }

    @Test
    void publicKeyModeVerifiesAndDecryptsProductionCallback() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        Path publicKeyPath = tempDir.resolve("wechatpay_pub_key.pem");
        Files.writeString(
                publicKeyPath,
                "-----BEGIN PUBLIC KEY-----\n"
                        + Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded())
                        + "\n-----END PUBLIC KEY-----\n"
        );

        String apiV3Key = "1234567890abcdefghijklmnopqrstuv";
        String resourceNonce = "123456789012";
        String associatedData = "transaction";
        String plaintext = """
                {"out_trade_no":"XD1","transaction_id":"TX1","trade_state":"SUCCESS","appid":"wx-test","mchid":"1900000109","amount":{"total":100}}
                """.trim();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(
                Cipher.ENCRYPT_MODE,
                new SecretKeySpec(apiV3Key.getBytes(StandardCharsets.UTF_8), "AES"),
                new GCMParameterSpec(128, resourceNonce.getBytes(StandardCharsets.UTF_8))
        );
        cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
        String ciphertext = Base64.getEncoder().encodeToString(cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8)));
        String body = """
                {"resource":{"algorithm":"AEAD_AES_256_GCM","ciphertext":"%s","associated_data":"%s","nonce":"%s"}}
                """.formatted(ciphertext, associatedData, resourceNonce).trim();

        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String nonce = "callback-nonce";
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(keyPair.getPrivate());
        signer.update((timestamp + "\n" + nonce + "\n" + body + "\n").getBytes(StandardCharsets.UTF_8));
        String signature = Base64.getEncoder().encodeToString(signer.sign());

        WechatPayProperties properties = new WechatPayProperties();
        properties.setApiV3Key(apiV3Key);
        properties.setPublicKeyId("PUB_KEY_ID_TEST");
        properties.setPublicKeyPath(publicKeyPath.toString());
        WechatPayClient client = new WechatPayClient(properties);

        PaymentNotifyRequest request = client.parsePaymentNotify(
                body,
                timestamp,
                nonce,
                "PUB_KEY_ID_TEST",
                signature
        );

        assertEquals("XD1", request.orderNo());
        assertEquals("TX1", request.transactionId());
        assertEquals(100, request.totalAmount());
    }

    @Test
    void rejectsUnsignedDirectRefundNotifyPayload() {
        WechatPayClient client = new WechatPayClient(new WechatPayProperties());

        assertThrows(BusinessException.class, () -> client.parseRefundNotify(
                "{\"refundNo\":\"RF1\",\"refundStatus\":\"SUCCESS\"}",
                null,
                null,
                null,
                null
        ));
    }
}
