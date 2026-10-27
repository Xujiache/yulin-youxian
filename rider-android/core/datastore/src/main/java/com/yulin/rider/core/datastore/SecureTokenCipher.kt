package com.yulin.rider.core.datastore

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 使用 Android Keystore 加密令牌。DataStore 里只保存 AES-GCM 密文，密钥不可导出且不参与系统备份。
 */
@Singleton
class SecureTokenCipher @Inject constructor() {

    fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val encrypted = Base64.encodeToString(
            cipher.doFinal(plainText.toByteArray(Charsets.UTF_8)),
            Base64.NO_WRAP,
        )
        return "$FORMAT_PREFIX:$iv:$encrypted"
    }

    /**
     * 旧版本留下的明文原样返回，调用方随后会原地迁移；密文损坏或 Keystore 密钥丢失时返回 null，
     * 绝不能把无法验证的字节当成有效会话继续使用。
     */
    fun decrypt(stored: String?): String? {
        if (stored.isNullOrBlank()) return null
        if (!isEncrypted(stored)) return stored
        return runCatching {
            val parts = stored.split(':', limit = 3)
            require(parts.size == 3)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(GCM_TAG_BITS, Base64.decode(parts[1], Base64.NO_WRAP)),
            )
            cipher.doFinal(Base64.decode(parts[2], Base64.NO_WRAP)).toString(Charsets.UTF_8)
        }.getOrNull()
    }

    fun isEncrypted(value: String?): Boolean = value?.startsWith("$FORMAT_PREFIX:") == true

    @Synchronized
    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            generateKey()
        }
    }

    private companion object {
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "rider.session.tokens.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val FORMAT_PREFIX = "ks1"
    }
}
