package com.xianda.freshdelivery.delivery.app;

import com.xianda.freshdelivery.delivery.common.DeliveryErrorCode;
import com.xianda.freshdelivery.delivery.common.DeliveryException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.io.ByteArrayInputStream;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import net.dongliu.apk.parser.ApkFile;
import net.dongliu.apk.parser.bean.ApkMeta;
import net.dongliu.apk.parser.bean.ApkSigner;
import net.dongliu.apk.parser.bean.ApkV2Signer;
import net.dongliu.apk.parser.bean.CertificateMeta;
import org.springframework.stereotype.Component;

@Component
public class ApkParserInspector implements ApkInspector {
    @Override
    public ApkInspection inspect(Path apkFile) {
        if (apkFile == null || !apkFile.toFile().isFile()) {
            throw new DeliveryException(DeliveryErrorCode.APP_RELEASE_INVALID, "APK 文件不存在");
        }
        try (ApkFile parser = new ApkFile(apkFile.toFile())) {
            ApkMeta meta = parser.getApkMeta();
            if (meta == null || meta.getPackageName() == null || meta.getPackageName().isBlank()) {
                throw new DeliveryException(DeliveryErrorCode.APP_RELEASE_INVALID, "无法读取 APK 包名");
            }
            Long versionCode = meta.getVersionCode();
            if (versionCode == null || versionCode <= 0 || versionCode > Integer.MAX_VALUE) {
                throw new DeliveryException(DeliveryErrorCode.APP_RELEASE_INVALID, "APK versionCode 无效");
            }
            String versionName = meta.getVersionName() == null || meta.getVersionName().isBlank()
                    ? String.valueOf(versionCode)
                    : meta.getVersionName().trim();
            String certSha256 = firstCertSha256(parser);
            if (certSha256 == null || certSha256.isBlank()) {
                throw new DeliveryException(DeliveryErrorCode.APP_RELEASE_INVALID, "APK 未包含可用签名证书");
            }
            return new ApkInspection(meta.getPackageName().trim(), versionCode.intValue(), versionName, certSha256);
        } catch (DeliveryException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new DeliveryException(DeliveryErrorCode.APP_RELEASE_INVALID, "APK 无法解析：" + exception.getMessage());
        }
    }

    private static String firstCertSha256(ApkFile parser) throws Exception {
        String digest = fromV2(parser);
        if (digest != null) {
            return digest;
        }
        digest = fromV1(parser);
        if (digest != null) {
            return digest;
        }
        try {
            List<CertificateMeta> certificates = parser.getCertificateMetaList();
            if (certificates != null) {
                for (CertificateMeta certificate : certificates) {
                    digest = sha256Of(certificate);
                    if (digest != null) {
                        return digest;
                    }
                }
            }
        } catch (Exception ignored) {
            // v2/v3-only APKs have no META-INF JAR certificate
        }
        return null;
    }

    private static String fromV2(ApkFile parser) {
        try {
            List<ApkV2Signer> signers = parser.getApkV2Singers();
            if (signers == null) {
                return null;
            }
            for (ApkV2Signer signer : signers) {
                if (signer == null || signer.getCertificateMetas() == null) {
                    continue;
                }
                for (CertificateMeta certificate : signer.getCertificateMetas()) {
                    String digest = sha256Of(certificate);
                    if (digest != null) {
                        return digest;
                    }
                }
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    private static String fromV1(ApkFile parser) {
        try {
            List<ApkSigner> signers = parser.getApkSingers();
            if (signers == null) {
                return null;
            }
            for (ApkSigner signer : signers) {
                if (signer == null || signer.getCertificateMetas() == null) {
                    continue;
                }
                for (CertificateMeta certificate : signer.getCertificateMetas()) {
                    String digest = sha256Of(certificate);
                    if (digest != null) {
                        return digest;
                    }
                }
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    private static String sha256Of(CertificateMeta certificate)
            throws NoSuchAlgorithmException, CertificateException {
        if (certificate == null) {
            return null;
        }
        byte[] encoded = certificate.getData();
        if (encoded == null || encoded.length == 0) {
            return null;
        }
        X509Certificate x509 = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(encoded));
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(x509.getEncoded());
        return HexFormat.of().formatHex(digest).toLowerCase(Locale.ROOT);
    }
}
