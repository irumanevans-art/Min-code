package dev.min.code.core.settings

/**
 * [SettingsStore] 对「凭据加密」唯一的依赖面：只放它真用到的两个方法。
 *
 * 存在的理由是测试：真实实现走 Android Keystore，JVM 里没有那东西，
 * 「密文解不开 / 解开了读不懂就挡住写入」这条护栏因此一直没法在单测里走一遍。
 * 假实现只要守住下面两条契约就能替身。
 */
internal interface CredentialCipher {
    /** 明文 → 落盘的串。输出必须以 [CIPHER_PREFIX] 开头，[isUnreadableCipher] 靠它认出「这是密文」 */
    fun encrypt(plain: String): String

    /**
     * 落盘的串 → 明文。契约有三档，调用方都在依赖：
     * - 空白串给 `""`（没存过）；
     * - 不带 [CIPHER_PREFIX] 的串原样返回（1.1.9 之前的明文）；
     * - 带前缀却解不开给 **null**，不是空串——「没存过」与「密文在、钥匙没了」必须分得开，
     *   见 [TokenCipher.decrypt]。
     */
    fun decrypt(stored: String): String?
}

/** 密文前缀，[TokenCipher] 写出来的格式是 `v1:<iv>:<密文>` */
internal const val CIPHER_PREFIX = "v1:"
