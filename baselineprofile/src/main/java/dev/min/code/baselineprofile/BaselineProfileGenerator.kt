package dev.min.code.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 在设备上走一遍关键路径，生成 baseline profile。
 *
 * 跑：`./gradlew :app:generateReleaseBaselineProfile`（已连模拟器 / 真机）
 * 目标包是 release 的 `dev.min.code`（插件用 nonMinifiedRelease 变体安装采集）。
 *
 * ## 为什么分成两个 collect
 *
 * `includeInStartupProfile = true` 决定这一趟的类和方法进不进 **startup-prof.txt**。
 * 上一版只有一个 collect，整趟（启动 + 滚动 + 点几下）都挂了这个标志 ——
 * 结果 startup-prof.txt 和 baseline-prof.txt 一字不差，23039 行。
 *
 * 两份档案是干不同事的：baseline profile 是安装时 AOT 编译的候选集合，宁可宽；
 * startup profile 还额外决定 **dex 里的类布局**（启动用得到的类挪到一起，减少
 * 启动时的页错误）。把滚动和点击也塞进去，等于告诉打包器"这些也是启动路径"，
 * 那点布局优化就被稀释掉了 —— 标志越宽，它越没用。
 *
 * 所以：[startup] 只开冷启动到首屏，挂标志；[chrome] 走界面，不挂。
 * 两趟的方法都会并进 baseline-prof.txt，只有 [startup] 的进 startup-prof.txt。
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    /** 冷启动到首屏可见为止 —— 这一段，也只有这一段，算启动路径 */
    @Test
    fun startup() = rule.collect(
        packageName = PACKAGE,
        includeInStartupProfile = true,
    ) {
        pressHome()
        startActivityAndWait()
        device.awaitFirstFrame()
    }

    /** 首屏之后的常见操作。进 baseline profile，不进 startup profile */
    @Test
    fun chrome() = rule.collect(
        packageName = PACKAGE,
        includeInStartupProfile = false,
    ) {
        pressHome()
        startActivityAndWait()
        device.awaitFirstFrame()

        // 滚一下、点几下常见铬件，让 Compose / 海窗 / haze 路径进 profile
        device.swipe(
            device.displayWidth / 2,
            (device.displayHeight * 0.7).toInt(),
            device.displayWidth / 2,
            (device.displayHeight * 0.3).toInt(),
            20,
        )
        device.waitForIdle(800)

        // 点屏幕中部偏上（常见顶栏区域），再点中部（列表/内容）
        device.click(device.displayWidth / 2, (device.displayHeight * 0.12).toInt())
        device.waitForIdle(600)
        device.click(device.displayWidth / 2, (device.displayHeight * 0.45).toInt())
        device.waitForIdle(600)

        device.pressBack()
        device.waitForIdle(500)
    }

    /** 等首屏稳定：本应用的窗口出现，再等一轮 idle */
    private fun UiDevice.awaitFirstFrame() {
        wait(Until.hasObject(By.pkg(PACKAGE).depth(0)), 15_000)
        waitForIdle(2_000)
    }

    private companion object {
        const val PACKAGE = "dev.min.code"
    }
}
