package dev.min.code.core.device

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "MinA11y"

/**
 * 让 agent 能看见屏幕、并替人点击输入的那条通道。
 *
 * ## 为什么是无障碍服务而不是 Shizuku
 *
 * Shizuku 要用户每次重启设备后用 ADB 重新激活一次（无 root 时），这个门槛会劝退
 * 绝大多数人。无障碍服务在系统设置里开一次就永久有效，不需要电脑。
 *
 * 代价是 Android 13+ 对**侧载**应用开无障碍加了一道「受限设置」——用户在无障碍列表里
 * 看到的开关是灰的，得先去「应用信息 → ⋮ → 允许受限设置 → 输 PIN」才点得动。
 * Min 走 GitHub APK 分发，必然撞上，所以引导文案里必须把这一步写出来
 * （`settings_device_control_restricted`），否则用户只会以为这个功能是坏的。
 *
 * ## 这个服务**不消费事件流**
 *
 * 每次操作都是现读 [rootInActiveWindow]，不订阅 `typeAllMask`。订阅全部事件等于让系统
 * 把用户在任何应用里的每一次点击、每一个输入框变化都推过来——那是我们根本不看、
 * 却实打实拿到手的隐私数据。配置见 `res/xml/device_accessibility_service.xml`。
 *
 * ## 生命周期
 *
 * 系统绑定/解绑这个服务，我们无法主动启停。所以用一个静态 [instance] 让
 * [DeviceController] 拿到它；服务没连上时那个引用是 null，上层据此告诉用户「还没开」，
 * 而不是抛异常。
 */
class MinAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        // 关掉框架的节点缓存。
        //
        // 这条和上面"不订阅事件流"是一对：框架的缓存靠 typeWindowContentChanged 之类的
        // 事件来失效，而我们为了不收集用户在每个应用里的一举一动，只订了窗口切换。
        // 两者叠加的结果在真机上是这样的——点击确实生效了、抽屉也确实打开了，
        // 可下一次 rootInActiveWindow 读回来的还是点击前那棵树，于是模型以为自己没点动、
        // 又点一次。**报成功却读到旧状态，比报失败更难查。**
        //
        // 关掉缓存的代价是每次读屏都要跨进程取一遍，多几毫秒；换来的是读到的一定是此刻的屏幕。
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            runCatching { setCacheEnabled(false) }
                .onFailure { Log.w(TAG, "setCacheEnabled(false) failed", it) }
        }
        instance.set(this)
        Log.i(TAG, "accessibility service connected")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance.compareAndSet(this, null)
        Log.i(TAG, "accessibility service unbound")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance.compareAndSet(this, null)
        super.onDestroy()
    }

    /**
     * 不做任何事。
     *
     * 配置里只订阅了 `typeWindowStateChanged`，留着这个钩子是为了将来判断
     * 「屏幕变了、上一次 ui_tree 的索引该失效了」。现在不实现，是因为在没有真机
     * 验证过之前，猜一个失效策略只会让模型收到似是而非的报错。
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    // -----------------------------------------------------------------------
    // 操作
    // -----------------------------------------------------------------------

    /**
     * 当前操作目标所在的 display。
     *
     * 单屏时永远是 [Display.DEFAULT_DISPLAY]。虚拟屏启用后由上层切到那块屏的 id——
     * 手势、截屏、读窗都认这个值，避免「读的是副屏、点的是主屏」。
     */
    private val targetDisplayId = AtomicInteger(Display.DEFAULT_DISPLAY)

    /** 切换操作目标的 display。虚拟屏起来 / 拆掉时由上层调 */
    fun setTargetDisplay(displayId: Int) {
        targetDisplayId.set(displayId)
        Log.i(TAG, "target display -> $displayId")
    }

    fun targetDisplay(): Int = targetDisplayId.get()

    /**
     * 当前目标 display 上的活动窗口根节点。
     *
     * ## 为什么不直接用 [rootInActiveWindow]
     *
     * 那个 API **跟焦点走**。虚拟屏场景下你碰一下主屏，焦点就回主屏，下一次读屏
     * 读到的是你正在刷的内容而不是 agent 那块屏——模型会据此在你的界面上乱点。
     *
     * API 30+ 用 [getWindowsOnAllDisplays] 按 displayId 精确取；取不到（屏上没窗、
     * 或 API 不够）再退回 [rootInActiveWindow]，单屏行为与改之前一致。
     *
     * 拿到之后强制 [AccessibilityNodeInfo.refresh] 一次：即便缓存已经关了
     * （见 [onServiceConnected]），根节点本身仍可能是上一次事件时的快照。
     */
    fun rootNode(): AccessibilityNodeInfo? = runCatching {
        val preferred = rootOnDisplay(targetDisplayId.get())
        (preferred ?: rootInActiveWindow)?.also { runCatching { it.refresh() } }
    }.getOrNull()

    /**
     * 指定 display 上层级最高的应用窗口根节点。
     *
     * 同一块屏上可能叠着状态栏、导航栏、输入法、应用窗——我们要的是应用窗。
     * 按 [AccessibilityWindowInfo.getLayer] 降序，跳过非应用类型，取第一棵有根节点的。
     */
    private fun rootOnDisplay(displayId: Int): AccessibilityNodeInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val byDisplay = runCatching { windowsOnAllDisplays }.getOrNull() ?: return null
        val windows = byDisplay[displayId] ?: return null
        return windows
            .asSequence()
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .sortedByDescending { it.layer }
            .mapNotNull { runCatching { it.root }.getOrNull() }
            .firstOrNull()
    }

    /**
     * 点击一个节点。
     *
     * 优先 `ACTION_CLICK`：那是控件自己声明的点击语义，比往坐标上拍一下可靠得多
     * （能正确触发 onClick、不受遮挡影响、不会因为动画位移而点空）。
     *
     * 节点自己不可点时**向上找最近的可点祖先**：列表项里真正带 clickable 的常常是外层的
     * 容器，而带文字、被模型认出来的是里面那个 TextView。不往上找的话，模型每次都会
     * 挑中那个文字节点然后收到「点不动」。
     *
     * 两条都不成立才退回 [tapAt] 拍坐标。
     */
    fun click(node: AccessibilityNodeInfo): Boolean {
        val target = android.graphics.Rect()
        node.getBoundsInScreen(target)

        // 1. 先**无条件**在目标自己身上试一次，不看 isClickable。
        //
        // Compose 的按钮在无障碍树里普遍是 `isClickable == false` 的裸 View——点击是通过
        // AccessibilityAction 注册的，不反映在那个属性上（真机实测：Min 自己的 IconButton
        // 就是 `class=android.view.View clickable=false`，却带着 contentDescription）。
        // 先看 isClickable 再决定试不试，等于对整个 Compose 生态直接跳过最准的那条路。
        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true

        // 2. 往上找可点祖先，但**祖先不能比目标大太多**。
        //
        // 列表项里真正带 clickable 的常常是外层容器，所以要往上找；可是不设面积限制的话，
        // 一路能找到铺满整屏的根容器——它确实 clickable，performAction 也返回 true，
        // 于是我们报告"已点击"而屏幕纹丝不动。**报成功却没生效是最坏的失败模式**：
        // 模型会据此往下走，而它脚下的屏幕根本没动。
        var current: AccessibilityNodeInfo? = node.parent
        var hops = 0
        val targetArea = target.width().toLong() * target.height()
        while (current != null && hops < MAX_CLICKABLE_ANCESTOR_HOPS) {
            val bounds = android.graphics.Rect()
            current.getBoundsInScreen(bounds)
            val area = bounds.width().toLong() * bounds.height()
            val tooBig = targetArea > 0 && area > targetArea * MAX_ANCESTOR_AREA_RATIO
            if (tooBig) break
            if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            current = current.parent
            hops++
        }

        // 3. 拍坐标。对上面两条都不认的控件（自绘、WebView 里的元素）这是唯一还能走的路
        if (target.isEmpty) return false
        return tapAt(target.centerX().toFloat(), target.centerY().toFloat())
    }

    /** 往输入框里填字。用 ACTION_SET_TEXT 一次替换，不模拟逐键输入 */
    fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /** 按返回键 */
    fun back(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    /**
     * 截屏。
     *
     * 走无障碍自带的 [takeScreenshot]（API 30+）而**不是** MediaProjection：后者每次都要
     * 弹一个系统授权框、还要拉一个前台服务，而我们已经有无障碍权限了，那里面本来就含这个能力。
     *
     * 这是 [rootNode] 读不到东西时的**兜底**，不是常规手段：一张图的 token 成本比一棵
     * UI 树高一个量级，而且模型只能拿到坐标、拿不到「这个控件可点吗」。
     * 什么时候该用它见 `DeviceTools` 里 `device_screenshot` 的描述。
     *
     * @return 位图；API 30 以下、或系统拒绝（安全界面禁止截屏）时为 null
     */
    fun screenshot(): android.graphics.Bitmap? {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return null
        val latch = CountDownLatch(1)
        val holder = AtomicReference<android.graphics.Bitmap?>(null)
        // takeScreenshot 会**直接抛** SecurityException（而不是走 onFailure）：
        // 配置里少了 canTakeScreenshot、或者某些 ROM 自己关掉了这个能力时就是这样。
        // 不接住的话异常一路冒到 DeviceMcpServer 的 socket 线程，那次请求连一个错误响应
        // 都回不出去，调用方只拿到一个空的连接——比给它一句"截不到屏"难查得多
        try {
            takeScreenshot(
            // 跟手势一样，截哪块屏由 targetDisplayId 决定。虚拟屏场景下截 DEFAULT
            // 等于拍到用户正在看的那块，而 agent 要的是它自己那块
            targetDisplayId.get(),
            { it.run() },
            object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    // HardwareBuffer 用完必须关，否则每截一张漏一块图形内存
                    result.hardwareBuffer.use { buffer ->
                        holder.set(
                            android.graphics.Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                                // 硬件位图读不了像素，压缩前得先拷成软件位图
                                ?.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                        )
                    }
                    latch.countDown()
                }

                override fun onFailure(errorCode: Int) {
                    Log.w(TAG, "takeScreenshot failed: $errorCode")
                    latch.countDown()
                }
            },
            )
        } catch (e: Exception) {
            Log.w(TAG, "takeScreenshot rejected", e)
            return null
        }
        return if (latch.await(SCREENSHOT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) holder.get() else null
    }

    /**
     * 在坐标上拍一下。[click] 的兜底路径，不单独对外暴露成工具——
     * 让模型直接给坐标是在请它犯错：分辨率、状态栏高度、弹窗偏移，每一样都能让它点空。
     */
    private fun tapAt(x: Float, y: Float): Boolean = dispatch(
        Path().apply { moveTo(x, y) },
        durationMs = TAP_DURATION_MS,
    )

    /**
     * 滑动。
     *
     * [dx]/[dy] 是**手指的位移方向**，和内容滚动方向相反——想看下面的内容，手指要往上滑。
     * 这层翻译放在调用方（[DeviceController]）做，这里只管照着划。
     */
    fun swipe(fromX: Float, fromY: Float, dx: Float, dy: Float): Boolean = dispatch(
        Path().apply {
            moveTo(fromX, fromY)
            lineTo(fromX + dx, fromY + dy)
        },
        durationMs = SWIPE_DURATION_MS,
    )

    /**
     * 把手势交给系统并**同步等它跑完**。
     *
     * 必须等：MCP 那头是一次请求一次应答，手势还在半路就回「成功」的话，模型下一步
     * 去读屏幕会读到动画中途的状态，然后基于一个不存在的界面继续操作。
     * `dispatchGesture` 本身是异步的，所以这里用 latch 把它掰回同步。
     *
     * ## 多屏
     *
     * [GestureDescription.Builder.setDisplayId] 决定手势落在哪块屏上。不设的话默认
     * [Display.DEFAULT_DISPLAY]——虚拟屏场景下就会出现「读的是副屏、点的是主屏」。
     * 节点级的 [AccessibilityNodeInfo.performAction] 不走这条路径，不受影响；
     * 只有坐标兜底（[tapAt]）和滑动会命中这里。
     */
    private fun dispatch(path: Path, durationMs: Long): Boolean {
        val builder = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setDisplayId(targetDisplayId.get())
        }
        val gesture = builder.build()
        val latch = CountDownLatch(1)
        var completed = false
        val accepted = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(description: GestureDescription?) {
                    completed = true
                    latch.countDown()
                }

                override fun onCancelled(description: GestureDescription?) {
                    completed = false
                    latch.countDown()
                }
            },
            null,
        )
        if (!accepted) return false
        // 超时按失败算，但不能不设上限：系统偶尔既不回调 completed 也不回调 cancelled
        // （锁屏、来电打断），不设超时这条 MCP 请求就永远挂在那里
        val finished = latch.await(durationMs + GESTURE_TIMEOUT_SLACK_MS, TimeUnit.MILLISECONDS)
        return finished && completed
    }

    companion object {
        /** 服务连上时由系统填，断开时清空。没连上就是 null，上层据此报「还没开」 */
        val instance = AtomicReference<MinAccessibilityService?>(null)

        /** 当前有没有连上 */
        fun connected(): Boolean = instance.get() != null

        /**
         * 从被点的节点往上找几层可点祖先。
         *
         * 五层是经验值：列表项常见的嵌套是 TextView → LinearLayout → 可点的 ItemView，
         * 三层就够；给到五层留余量。再往上就有把整屏容器点掉的风险了。
         */
        private const val MAX_CLICKABLE_ANCESTOR_HOPS = 5

        /**
         * 祖先面积最多是目标的多少倍。
         *
         * 一个按钮外面包两三层布局，面积撑大几倍是正常的；撑大几十倍就说明那已经是
         * 整个页面的容器，点它不会有任何效果。8 倍拦得住整屏容器，又放得过正常的嵌套。
         */
        private const val MAX_ANCESTOR_AREA_RATIO = 8

        private const val TAP_DURATION_MS = 50L
        // 450ms：比"甩"慢一点、比"拖"快一点。太快（≤200）容易被当成 fling 直接飞到底，
        // 太慢（≥800）列表会跟手顿一下。450 在真机上是一次可控的翻页手感
        private const val SWIPE_DURATION_MS = 450L
        private const val GESTURE_TIMEOUT_SLACK_MS = 2_000L
        private const val SCREENSHOT_TIMEOUT_MS = 5_000L
    }
}
