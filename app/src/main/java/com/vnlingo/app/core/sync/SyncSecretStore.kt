package com.vnlingo.app.core.sync

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 云同步凭据的本地加密存储（Android 平台标准做法，不引入第三方依赖）。
 *
 * 方案：
 * - **密钥**：在 `AndroidKeyStore` 内生成 AES-256 密钥（别名 [KEY_ALIAS]），
 *   密钥本身**离开不了系统密钥库**（有 TEE/StrongBox 时由硬件保护），
 *   应用只能"要求它做加解密"，拿不到明文密钥；
 * - **算法**：`AES/GCM/NoPadding`（带完整性校验；密文被改动会解密失败而非返回垃圾）；
 * - **IV**：每次加密随机 12 字节，与密文一起 `Base64(IV || ciphertext||tag)` 存储；
 * - **不可用/密钥丢失**（如换机、系统还原、清除数据后从备份恢复 prefs）：
 *   [decrypt] 返回 null，调用方降级为"需要重新登录"，而不是崩溃或返回错误数据。
 *
 * 与 `EncryptedSharedPreferences`（Jetpack Security）等价的安全性，但不额外引入依赖
 * （该库也已被 Google 标记为 deprecated）。
 */
object SyncSecretStore {

    private const val TAG = "SyncSecretStore"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "vnlingo_webdav_credentials_v1"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val IV_LENGTH = 12
    private const val TAG_BITS = 128

    @Volatile
    private var cachedKey: SecretKey? = null

    /** 加密为可存入 SharedPreferences 的字符串；失败返回 null（调用方决定回退策略）。 */
    fun encrypt(plain: String): String? = try {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        Base64.encodeToString(iv + body, Base64.NO_WRAP)
    } catch (t: Throwable) {
        Log.w(TAG, "encrypt failed", t)
        null
    }

    /** 解密；密钥丢失或密文损坏时返回 null。 */
    fun decrypt(token: String?): String? {
        if (token.isNullOrEmpty()) return null
        return try {
            val raw = Base64.decode(token, Base64.NO_WRAP)
            if (raw.size <= IV_LENGTH) return null
            val iv = raw.copyOfRange(0, IV_LENGTH)
            val body = raw.copyOfRange(IV_LENGTH, raw.size)
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(body), Charsets.UTF_8)
        } catch (t: Throwable) {
            Log.w(TAG, "decrypt failed (key lost or data corrupted?)", t)
            null
        }
    }

    /** 取（必要时生成）AndroidKeyStore 中的 AES-256-GCM 密钥。 */
    @Synchronized
    private fun key(): SecretKey {
        cachedKey?.let { return it }
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val existing = ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        val key = existing?.secretKey ?: generateKey()
        cachedKey = key
        return key
    }

    private fun generateKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }
}
