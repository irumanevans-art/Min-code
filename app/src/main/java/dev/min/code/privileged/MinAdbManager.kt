package dev.min.code.privileged

import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAKeyGenParameterSpec
import java.security.spec.RSAPublicKeySpec
import java.util.Date
import java.util.Locale

/**
 * 具体的 ADB 连接管理器：给 [AbsAdbConnectionManager] 提供一副固定的 RSA 密钥 + 自签证书。
 *
 * 这副密钥就是这台设备在 adbd 眼里的身份。配对（[AbsAdbConnectionManager.pair]）成功后，
 * 公钥进入 adbd 的已授权列表；之后的无线调试 TLS 连接（[AbsAdbConnectionManager.connect]）
 * 用同一副密钥握手，不再需要配对码。所以密钥必须持久化——换了密钥就等于换了一台新设备，得重配。
 *
 * 密钥/证书存在应用私有目录 `filesDir/adb/`，首次使用时生成。证书用 BouncyCastle 构建，
 * 和 Shizuku 的做法一致（不引 repackaged sun.security）。
 */
class MinAdbManager(context: Context) : AbsAdbConnectionManager() {

    private val privateKey: PrivateKey
    private val certificate: Certificate

    init {
        setApi(Build.VERSION.SDK_INT)

        val dir = File(context.filesDir, "adb").apply { mkdirs() }
        val keyFile = File(dir, "adbkey")
        val certFile = File(dir, "cert.pem")

        if (keyFile.exists() && certFile.exists()) {
            privateKey = KeyFactory.getInstance("RSA")
                .generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
            certificate = CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(certFile.readBytes()))
        } else {
            val keyPair = KeyPairGenerator.getInstance("RSA").apply {
                initialize(2048, SecureRandom())
            }.generateKeyPair()
            privateKey = keyPair.private
            val publicKey = KeyFactory.getInstance("RSA").generatePublic(
                RSAPublicKeySpec(
                    (keyPair.public as RSAPublicKey).modulus,
                    RSAKeyGenParameterSpec.F4,
                ),
            ) as RSAPublicKey

            val signer = JcaContentSignerBuilder("SHA256withRSA").build(privateKey)
            val holder = X509v3CertificateBuilder(
                X500Name("CN=min-code"),
                BigInteger.ONE,
                Date(0),
                Date(2461449600L * 1000), // 2048 年，足够远
                Locale.ROOT,
                X500Name("CN=min-code"),
                SubjectPublicKeyInfo.getInstance(publicKey.encoded),
            ).build(signer)
            certificate = CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(holder.encoded)) as X509Certificate

            keyFile.writeBytes(privateKey.encoded) // PKCS#8
            certFile.writeBytes(certificate.encoded)
        }
    }

    override fun getPrivateKey(): PrivateKey = privateKey

    override fun getCertificate(): Certificate = certificate

    override fun getDeviceName(): String = "min-code"
}
