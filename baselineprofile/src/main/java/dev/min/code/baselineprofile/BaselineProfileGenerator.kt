package dev.min.code.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 在设备上走一遍启动 + 会话页关键路径，生成 baseline profile。
 *
 * 跑：`./gradlew :app:generateReleaseBaselineProfile`（已连模拟器 / 真机）
 * 目标包是 release 的 `dev.min.code`（插件用 nonMinifiedRelease 变体安装采集）。
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun startupAndChrome() = rule.collect(
        packageName = PACKAGE,
        includeInStartupProfile = true,
    ) {
        pressHome()
        startActivityAndWait()

        // 等首屏稳定：启动页 / 会话页 / 设置入口任一出现
        device.wait(Until.hasObject(By.pkg(PACKAGE).depth(0)), 15_000)
        device.waitForIdle(2_000)

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

    private companion object {
        const val PACKAGE = "dev.min.code"
    }
}
