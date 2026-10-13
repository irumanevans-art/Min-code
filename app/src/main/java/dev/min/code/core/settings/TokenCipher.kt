package dev.min.code.core.settings

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Keystore-backed token storage. The format is `v1:<iv>:<ciphertext>` in DataStore. */
internal object TokenCipher : CredentialCipher {
    private const val KEY_ALIAS = "min.anthropic.auth-token"
    private const val PREFIX = CIPHER_PREFIX
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    private fun isEncrypted(value: String): Boolean = value.startsWith(PREFIX)

    override fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val body = Base64.encodeToString(cipher.doFinal(plain.toByteArray(StandardCharsets.UTF_8)), Base64.NO_WRAP)
        return "$PREFIX$iv:$body"
    }

    /**
     * 解不开时返回 **null**，不是空串。
     *
     * 这两件事必须分得开：空串是「没存过」，null 是「密文在这儿但 Keystore 打不开」。
     * 早先一律给空串，于是密钥失效（用户改了锁屏、换机恢复了备份）之后整张配置表
     * 读成空，界面显示「还没配过」，而用户随手新增一条就会把原密文覆盖掉 —— 那时候
     * 才是真的找不回来了。
     */
    override fun decrypt(stored: String): String? {
        if (stored.isBlank()) return ""
        if (!isEncrypted(stored)) return stored
        return runCatching {
            val parts = stored.split(':', limit = 3)
            require(parts.size == 3)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key(),
                GCMParameterSpec(128, Base64.decode(parts[1], Base64.DEFAULT)),
            )
            String(cipher.doFinal(Base64.decode(parts[2], Base64.DEFAULT)), StandardCharsets.UTF_8)
        }.getOrNull()
    }

    @Synchronized
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }
}
