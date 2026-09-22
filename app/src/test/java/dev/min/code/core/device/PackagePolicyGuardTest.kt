package dev.min.code.core.device

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 写操作那道闸。
 *
 * 这里错了不像别处：漏放一个银行应用，代价是真金白银，而且事后没有 undo。
 * 所以 fail-closed 这条必须有测试钉住——它最容易在某次"顺手让报错更友好一点"的
 * 改动里被翻转成 fail-open。
 */
class PackagePolicyGuardTest {

    private fun allowed(pkg: String?) =
        PackagePolicyGuard.allowsWrite(pkg) is PackagePolicyGuard.Decision.Allowed

    private fun denied(pkg: String?) =
        PackagePolicyGuard.allowsWrite(pkg) is PackagePolicyGuard.Decision.Denied

    /**
     * **最重要的一条**：认不出前台是什么就拒绝。
     *
     * 认不出来的时刻（系统弹窗盖住、窗口切到一半）恰恰是最不该盲目点下去的时刻。
     */
    @Test
    fun `认不出前台应用时拒绝而不是放行`() {
        assertTrue(denied(null))
        assertTrue(denied(""))
        assertTrue(denied("   "))
    }

    @Test
    fun `普通应用放行`() {
        assertTrue(allowed("com.android.chrome"))
        assertTrue(allowed("dev.min.code"))
        assertTrue(allowed("com.termux"))
    }

    @Test
    fun `支付应用被拦`() {
        assertTrue(denied("com.eg.android.alipaygphone"))
        assertTrue(denied("com.tencent.mm"))
        assertTrue(denied("com.paypal.android.p2pmobile"))
    }

    @Test
    fun `银行应用按前缀拦`() {
        assertTrue(denied("com.chase.sig.android"))
        assertTrue(denied("com.icbc.mobile"))
        assertTrue(denied("cmb.pb.whatever"))
    }

    /** 短信里有验证码：能读短信 + 能点确认 = 绕开所有二次验证 */
    @Test
    fun `短信被拦`() {
        assertTrue(denied("com.google.android.apps.messaging"))
        assertTrue(denied("com.android.mms"))
    }

    /** 包名大小写不该成为绕过手段 */
    @Test
    fun `匹配不分大小写`() {
        assertTrue(denied("COM.CHASE.SIG.ANDROID"))
        assertTrue(denied("Com.Eg.Android.AlipayGphone"))
    }

    /**
     * 拒绝时必须给出**理由**，而且要是给模型看得懂的话。
     * 只回一句 "denied" 的话，模型只会重试同一个操作。
     */
    @Test
    fun `拒绝时带上给模型看的理由`() {
        val decision = PackagePolicyGuard.allowsWrite("com.eg.android.alipaygphone")
        assertTrue(decision is PackagePolicyGuard.Decision.Denied)
        assertTrue((decision as PackagePolicyGuard.Decision.Denied).reason.isNotBlank())
    }
}
