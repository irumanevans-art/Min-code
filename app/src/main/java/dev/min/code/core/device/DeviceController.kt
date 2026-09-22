package dev.min.code.core.device

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 六个设备工具的实现。MCP server 只管收发帧，真正干活的在这里。
 *
 * ## 索引怎么保持有效
 *
 * `device_tap(index)` 里的 index 来自上一次 `device_ui_tree`。这中间屏幕随时可能变
 * （动画没停、通知弹出来、应用自己刷新了列表），所以**每次操作都重新读一遍树**、
 * 在新树上按 index 取节点，而不是缓存上次那棵。
 *
 * 这挡不住「屏幕真的变了，第 3 个元素已经是别的东西」——那种情况没有任何本地手段能
 * 识别，只能靠工具描述反复告诉模型「操作前先重新读」，以及靠每次点击都要人过目。
 * 但它至少挡住了最常见的那种失效：节点对象本身被系统回收，拿着它去 performAction
 * 会静默失败，而模型收到的是「成功」。
 *
 * ## 为什么所有方法都返回文本而不是结构
 *
 * 出口是 MCP 的 `content[0].text`，模型读到的就是这段字。与其让上层再把结构翻译成话，
 * 不如在这里一次写清楚——尤其是失败时，一句「为什么失败、下一步该怎么办」比一个
 * error code 有用得多。
 */
class DeviceController(
    private val context: Context,
    /**
     * 虚拟屏会话。壳服务未拉起或会话未开始时 [AgentDisplaySession.isActive] 为 false，
     * [openApp] 走真屏；活跃时改走 shell 侧的 launchOnDisplay。
     */
    private val agentDisplay: AgentDisplaySession? = null,
) {

    /**
     * 读屏。唯一的只读操作，不过 [PackagePolicyGuard] 那道闸。
     *
     * ## 读不到东西时主动指路
     *
     * UI 树对**原生控件**有效，对 Canvas 自绘的界面（游戏、图表、老版本 Flutter、
     * 某些 WebView）什么都读不到——那些界面在无障碍看来就是一整块空白的 View。
     *
     * 这时候只回一句"没读到"是最坏的：模型会认为这件事做不到，或者反复重试同一个调用。
     * 所以节点少到不像一个真实界面时，明确告诉它还有 `device_screenshot` 这条路。
     * 判断标准放得很松（[THIN_TREE_NODES]），宁可多提一次——提了它不一定用，不提它一定卡住。
     */
    fun uiTree(): Result {
        val service = MinAccessibilityService.instance.get()
            ?: return Result.failure(NOT_CONNECTED)
        val root = service.rootNode()
            ?: return Result.failure(
                "读不到当前窗口的内容。可能正停在锁屏或某个系统界面上——这种屏幕不对无障碍服务开放。" +
                    "如果不是锁屏，试试 device_screenshot 直接看一眼。"
            )
        val tree = serializeUiTree(root)
        val rendered = tree.render()
        if (tree.nodes.size <= THIN_TREE_NODES) {
            return Result.success(
                rendered + "\n\n" +
                    "（只读到 ${tree.nodes.size} 个元素，扫了 ${tree.totalScanned} 个节点。" +
                    "这个界面可能是自绘的——游戏、图表、部分 WebView 在无障碍里就是一块空白。" +
                    "用 device_screenshot 看一眼，再按坐标点。）"
            )
        }
        return Result.success(rendered)
    }

    /**
     * 截屏兜底。
     *
     * 只在 [uiTree] 读不出东西时用。这里不做"自动降级"——读不到就悄悄返回一张图会
     * 让每次读屏的 token 成本变成一个薛定谔的数，而模型完全不知道自己刚花了多少。
     * 让它自己决定要不要付这个钱。
     */
    fun screenshot(): ImageResult {
        val service = MinAccessibilityService.instance.get()
            ?: return ImageResult.failure(NOT_CONNECTED)
        val bitmap = service.screenshot()
            ?: return ImageResult.failure(
                "截不到屏。可能这个界面标了 FLAG_SECURE（银行、密码框、部分视频应用会这样），" +
                    "系统禁止对它截屏；也可能是系统版本低于 Android 11。"
            )
        return try {
            val scaled = scaleForModel(bitmap)
            val bytes = java.io.ByteArrayOutputStream().use { out ->
                scaled.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                out.toByteArray()
            }
            if (scaled !== bitmap) scaled.recycle()
            ImageResult.success(
                text = "当前屏幕（${scaled.width}×${scaled.height}，已按比例缩小）。" +
                    "注意：这张图里没有元素索引，device_tap 用不了——看清之后请回到 device_ui_tree 找可点的元素。",
                base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP),
            )
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * 缩到模型读得起的尺寸。
     *
     * 长边压到 [MAX_SCREENSHOT_EDGE]：视觉 token 大致按像素面积算，一张 1260×2800 的
     * 原图是四千多 token，压到 768 长边之后一千出头，而界面上的字仍然认得清。
     * 不缩的话「兜底手段」会变成「每用一次烧掉半个上下文」。
     */
    private fun scaleForModel(source: android.graphics.Bitmap): android.graphics.Bitmap {
        val longest = maxOf(source.width, source.height)
        if (longest <= MAX_SCREENSHOT_EDGE) return source
        val ratio = MAX_SCREENSHOT_EDGE.toFloat() / longest
        return android.graphics.Bitmap.createScaledBitmap(
            source,
            (source.width * ratio).toInt().coerceAtLeast(1),
            (source.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
    }

    /** 带图的返回。失败时退化成纯文本 */
    data class ImageResult(
        val text: String,
        val base64: String?,
        val isError: Boolean,
    ) {
        companion object {
            fun success(text: String, base64: String) = ImageResult(text, base64, isError = false)
            fun failure(text: String) = ImageResult(text, null, isError = true)
        }
    }

    fun tap(index: Int): Result = withNode(index) { service, node, label ->
        if (service.click(node)) {
            Result.success("已点击 $label")
        } else {
            Result.failure(
                "点不动 $label。这个元素可能被别的东西盖住了，或者它根本不接受点击——" +
                    "重新读一次屏幕，换一个带 clickable 标记的元素。"
            )
        }
    }

    fun input(index: Int, text: String): Result = withNode(index) { service, node, label ->
        when {
            !node.isEditable -> Result.failure(
                "$label 不是输入框，填不进字。重新读一次屏幕，找带 editable 标记的那个。"
            )
            service.setText(node, text) -> Result.success("已在 $label 里填入：$text")
            else -> Result.failure("往 $label 填字失败，这个输入框可能不接受直接赋值。")
        }
    }

    /**
     * 滑动。
     *
     * [direction] 说的是**内容要往哪个方向走**（`down` = 看下面的内容），所以手指的
     * 位移方向是相反的。这层翻译放在这里而不是让模型自己想：让模型去推理「往下翻要
     * 把手指往上划」，它有一半的时候会反。
     */
    fun swipe(direction: String, index: Int?): Result {
        val service = MinAccessibilityService.instance.get()
            ?: return Result.failure(NOT_CONNECTED)
        guardWrite()?.let { return it }

        val bounds = android.graphics.Rect()
        if (index != null) {
            val tree = serializeUiTree(service.rootNode())
            val node = nodeAt(service, tree, index)
                ?: return Result.failure(indexOutOfRange(index, tree.nodes.size))
            node.getBoundsInScreen(bounds)
        } else {
            val metrics = screenSize()
            bounds.set(0, 0, metrics.first, metrics.second)
        }
        if (bounds.isEmpty) return Result.failure("要滑动的区域是空的，换一个元素或省略 index 滑整屏。")

        // 行程是区域高度的 [SWIPE_FRACTION]，不是从头甩到尾。
        //
        // 以前是 0.6：在整屏上等于一次甩过大半个屏幕，设置列表会被直接甩到底，
        // 中间那些项一闪而过。真机实测（vivo 设置找电池、无障碍列表找 Min）两次都
        // 因为这一下太猛而漏掉目标。0.35 ≈ 一屏内容翻过约三分之一，列表里的项还
        // 认得清，模型可以边滑边读。从边缘起手仍会被系统返回手势截走，所以起终点
        // 都留在中间带。
        val cx = bounds.centerX().toFloat()
        val cy = bounds.centerY().toFloat()
        val dx = bounds.width() * SWIPE_FRACTION
        val dy = bounds.height() * SWIPE_FRACTION
        val (fromX, fromY, moveX, moveY) = when (direction.lowercase()) {
            // 看下面的内容 = 手指往上
            "down" -> Quad(cx, cy + dy / 2, 0f, -dy)
            "up" -> Quad(cx, cy - dy / 2, 0f, dy)
            "left" -> Quad(cx + dx / 2, cy, -dx, 0f)
            "right" -> Quad(cx - dx / 2, cy, dx, 0f)
            else -> return Result.failure("方向只能是 up / down / left / right，收到的是 $direction")
        }
        return if (service.swipe(fromX, fromY, moveX, moveY)) {
            Result.success("已向 $direction 滑动")
        } else {
            Result.failure("滑动没有生效，这块区域可能不可滚动。")
        }
    }

    /**
     * 打开一个应用。
     *
     * **只查目标应用，不查当前前台**——这和 tap / input / swipe 正相反。那几个是
     * "在当前这个应用里动手"，所以要看你此刻站在哪；打开应用是**离开**当前界面，
     * 站在哪儿无关紧要。
     *
     * 一开始这里也调了 [guardWrite]，真机上立刻撞出死锁：人停在系统设置页时，
     * 前台是 `com.android.settings`（在名单里），于是 agent 连"打开别的应用"都被拒绝，
     * 而那恰恰是离开那个界面的唯一办法。
     */
    fun openApp(packageName: String): Result {
        PackagePolicyGuard.allowsWrite(packageName).let {
            if (it is PackagePolicyGuard.Decision.Denied) {
                return Result.failure("不能打开 $packageName：${it.reason}")
            }
        }
        // 虚拟屏会话活跃 → 让 shell 进程把 Activity 启到那块屏上，主屏不动
        val session = agentDisplay
        if (session != null && session.isActive) {
            return runCatching {
                session.launch(packageName)
                Result.success("已在虚拟屏打开 $packageName")
            }.getOrElse { Result.failure("在虚拟屏打开 $packageName 失败：${it.message}") }
        }
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return Result.failure(
                "这台设备上没装 $packageName，或者它没有可启动的入口界面。"
            )
        return runCatching {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Result.success("已打开 $packageName")
        }.getOrElse { Result.failure("打开 $packageName 失败：${it.message}") }
    }

    fun back(): Result {
        val service = MinAccessibilityService.instance.get()
            ?: return Result.failure(NOT_CONNECTED)
        guardWrite()?.let { return it }
        return if (service.back()) Result.success("已按返回键") else Result.failure("返回键没有生效。")
    }

    // -----------------------------------------------------------------------

    /**
     * 取节点 + 过闸 的公共前半段。
     *
     * 每次都重新 [serializeUiTree] 一遍：缓存上一棵树的 [AccessibilityNodeInfo] 拿去
     * performAction 会静默失败（节点已被系统回收），而模型收到的是「成功」——
     * 那比报错难查得多。
     */
    private inline fun withNode(
        index: Int,
        action: (MinAccessibilityService, AccessibilityNodeInfo, String) -> Result,
    ): Result {
        val service = MinAccessibilityService.instance.get()
            ?: return Result.failure(NOT_CONNECTED)
        guardWrite()?.let { return it }
        val tree = serializeUiTree(service.rootNode())
        val node = nodeAt(service, tree, index)
            ?: return Result.failure(indexOutOfRange(index, tree.nodes.size))
        val label = tree.nodes[index].let { n ->
            val text = n.text
            if (text.isNullOrBlank()) "[$index] ${n.className}" else "[$index] ${n.className} \"$text\""
        }
        return action(service, node, label)
    }

    /**
     * 按序列化时的 index 在真树上找回对应节点。
     *
     * [serializeUiTree] 产出的是数据快照，不持有 [AccessibilityNodeInfo]（持有了就要管
     * 回收）。所以这里照同一套规则重走一遍真树，数到第 index 个被保留的节点。
     * 两边的保留规则必须一致，否则数出来的不是同一个——这也是为什么规则只写在
     * `UiTreeSerializer` 里一份，这里只借它的遍历顺序。
     */
    private fun nodeAt(
        service: MinAccessibilityService,
        tree: UiTree,
        index: Int,
    ): AccessibilityNodeInfo? {
        if (index < 0 || index >= tree.nodes.size) return null
        val target = tree.nodes[index]
        val root = service.rootNode() ?: return null
        return findByBounds(root, target, 0)
    }

    /**
     * 用屏幕坐标把快照里的节点对回真树。
     *
     * 坐标是快照里唯一能在两棵树之间对上的标识——index 是我们自己编的，
     * `AccessibilityNodeInfo` 也没有稳定 id（`viewIdResourceName` 在列表项里会重复）。
     * 两次读取之间屏幕没动的话，同一个控件的 bounds 一定相同。
     */
    private fun findByBounds(
        node: AccessibilityNodeInfo,
        target: UiNode,
        depth: Int,
    ): AccessibilityNodeInfo? {
        if (depth > MAX_MATCH_DEPTH) return null
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)
        if (bounds == target.bounds && node.className?.toString()?.substringAfterLast('.') == target.className) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findByBounds(child, target, depth + 1)?.let { return it }
        }
        return null
    }

    /** 写操作前的那道闸。允许时返回 null */
    private fun guardWrite(): Result? {
        val foreground = MinAccessibilityService.instance.get()?.rootNode()?.packageName?.toString()
        return when (val decision = PackagePolicyGuard.allowsWrite(foreground)) {
            is PackagePolicyGuard.Decision.Allowed -> null
            is PackagePolicyGuard.Decision.Denied -> Result.failure(
                "这一步被拦下了：${decision.reason}。请你自己来做这一步，做完告诉我继续。"
            )
        }
    }

    /**
     * 当前目标 display 的像素尺寸。
     *
     * 整屏滑动用这个算行程。虚拟屏启用后如果仍读主屏尺寸，手势坐标会按 1260×2800
     * 去打一块可能只有 800×1600 的屏——轻则滑空，重则触发到主屏上的系统手势。
     */
    private fun screenSize(): Pair<Int, Int> {
        val displayId = MinAccessibilityService.instance.get()?.targetDisplay()
            ?: android.view.Display.DEFAULT_DISPLAY
        val dm = context.getSystemService(DisplayManager::class.java)
        val display = dm?.getDisplay(displayId) ?: dm?.getDisplay(android.view.Display.DEFAULT_DISPLAY)
        if (display != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            runCatching {
                val metrics = context.createDisplayContext(display).resources.displayMetrics
                return metrics.widthPixels to metrics.heightPixels
            }
        }
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display?.getRealMetrics(metrics)
        return metrics.widthPixels to metrics.heightPixels
    }

    private fun indexOutOfRange(index: Int, size: Int): String =
        if (size == 0) {
            "屏幕上现在没有可操作的元素。先用 device_ui_tree 看一眼。"
        } else {
            "没有 [$index] 这个元素，当前屏幕只有 [0] 到 [${size - 1}]。" +
                "索引可能已经过期了——重新读一次屏幕再试。"
        }

    /** 成功与失败都带一句给模型看的话，直接进 MCP 的 content[0].text */
    data class Result(val text: String, val isError: Boolean) {
        companion object {
            fun success(text: String) = Result(text, isError = false)
            fun failure(text: String) = Result(text, isError = true)
        }
    }

    private data class Quad(val a: Float, val b: Float, val c: Float, val d: Float)

    private companion object {
        const val NOT_CONNECTED =
            "设备操控还没打开。请到 Min 的「设置 → 设备操控」开启，" +
                "那里会告诉你怎么在系统设置里授予无障碍权限。"

        /**
         * 滑动行程占区域尺寸的比例。
         *
         * 0.35 ≈ 翻过三分之一屏。再大就容易直接甩到底（真机上已经因此漏过目标两次）；
         * 再小则翻得太慢，模型要滑很多次。从边缘起手仍会被系统返回手势截走，
         * 所以起终点都留在中间带——这个约束和行程大小无关。
         */
        const val SWIPE_FRACTION = 0.35f

        /** 回找节点时的递归上限，和序列化那边的 maxDepth 同量级 */
        const val MAX_MATCH_DEPTH = 40

        /**
         * 少到这个数就提示改用截图。
         *
         * 真机上一个普通界面读出来是几十上百个元素（vivo 桌面 55 个，系统设置页 11 个），
         * 所以 3 个基本等于"什么都没读到"。放松一点无所谓：多提一句话几十个 token，
         * 不提则是模型原地打转。
         */
        const val THIN_TREE_NODES = 3

        /** 截图长边上限。视觉 token 按面积算，1260×2800 压到这个尺寸大约省掉四分之三 */
        const val MAX_SCREENSHOT_EDGE = 768
    }
}
