package dev.min.code.core.device

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 无障碍树里**值得给模型看**的一个节点。
 *
 * [index] 不是它在树里的位置，而是这次输出里的序号（前序遍历，从 0 起，连续）。
 * 模型之后按"点第 N 个"回来找它，所以序号只能由输出顺序决定：容器被裁掉、兄弟被
 * 截断，都不能让已经发出去的号错位。
 */
data class UiNode(
    val index: Int,
    val className: String,   // 取简单类名，如 "Button"，不要全限定名
    val text: String?,       // text 优先，为空时取 contentDescription
    val bounds: Rect,
    val clickable: Boolean,
    val editable: Boolean,
    val checkable: Boolean,
    val checked: Boolean,
    val scrollable: Boolean,
)

/**
 * 一次遍历的结果。
 *
 * [truncated] 只说一件事：撞到了 maxNodes 上限，树没走完。[totalScanned] 里既算进了
 * 输出的节点，也算进了被跳过的（不可见、纯布局容器、超深度的），模型靠这两个数
 * 判断"屏幕上是不是还有我没看到的东西"。
 */
data class UiTree(val nodes: List<UiNode>, val truncated: Boolean, val totalScanned: Int)

/**
 * 遍历只认这个接口，不认 [AccessibilityNodeInfo]。
 *
 * AccessibilityNodeInfo 是 final 类，构造又被 framework 把着：JVM 单测里既 mock 不了，
 * 也造不出来（就算用 mockable android.jar 造出来，读什么都是 0/false，测了等于没测）。
 * 把"从哪读"和"怎么裁"拆开之后，裁剪规则可以用一棵假树完整跑一遍。
 */
interface UiNodeSource {
    val childCount: Int
    fun child(index: Int): UiNodeSource?
    val className: CharSequence?
    val text: CharSequence?
    val contentDescription: CharSequence?
    val visibleToUser: Boolean
    val clickable: Boolean
    val editable: Boolean
    val checkable: Boolean
    val checked: Boolean
    val scrollable: Boolean
    fun boundsInScreen(out: Rect)
}

/** 遍历并裁剪。root 为 null 时返回空树 */
fun serializeUiTree(root: AccessibilityNodeInfo?, maxNodes: Int = 120, maxDepth: Int = 40): UiTree =
    serializeUiTree(root?.let { AccessibilityNodeSource(it) }, maxNodes, maxDepth)

/**
 * [serializeUiTree] 的本体：规则只跑在 [UiNodeSource] 上，真机走适配器，单测走假树。
 *
 * 裁剪顺序（改这里等于改模型看到的世界）：
 * 1. `visibleToUser == false` 的节点整枝跳过，连同子树
 * 2. 有非空文字，或者是 clickable / editable / checkable / scrollable 的，才输出自己
 * 3. 不满足第 2 条的（纯布局容器）不输出自己，但继续下潜 —— 整棵子树不能因为父容器没文字就丢
 * 4. 输出数到 maxNodes 就停止遍历，`truncated = true`
 * 5. 深度到 maxDepth 就只输出本节点、不再下潜（这不算 truncated，是我们自己设的界）
 * 6. totalScanned 记实际访问过的节点数，含被跳过的
 */
fun serializeUiTree(root: UiNodeSource?, maxNodes: Int = 120, maxDepth: Int = 40): UiTree {
    if (root == null) return UiTree(emptyList(), truncated = false, totalScanned = 0)

    val nodes = ArrayList<UiNode>(maxNodes.coerceIn(0, 256))
    // 一次遍历共用一块 Rect，靠 copyBounds 拷出来再存；直接把这块存进 UiNode 的话，
    // 所有节点最后都会指向同一份边界（也就是最后一个节点的边界）
    val scratch = Rect()
    var scanned = 0
    var truncated = false

    /** 返回 true = 预算用完了，整棵树都别再走。只看当前子树的话，兄弟节点还会一个个进来空转 */
    fun visit(node: UiNodeSource, depth: Int): Boolean {
        if (nodes.size >= maxNodes) {
            truncated = true
            return true
        }
        scanned++
        // 规则 1：不可见整枝丢掉。它自己算访问过（模型要知道裁了多少），子树一次都不碰
        if (!node.visibleToUser) return false

        val text = cleanText(node.text) ?: cleanText(node.contentDescription)
        val clickable = node.clickable
        val editable = node.editable
        val checkable = node.checkable
        val scrollable = node.scrollable
        // 规则 2 / 3：没文字又不是任何控件的，是纯布局容器，不输出自己但继续下潜
        if (text != null || clickable || editable || checkable || scrollable) {
            node.boundsInScreen(scratch)
            nodes += UiNode(
                index = nodes.size,
                className = simpleClassName(node.className),
                text = text,
                bounds = copyBounds(scratch),
                clickable = clickable,
                editable = editable,
                checkable = checkable,
                checked = node.checked,
                scrollable = scrollable,
            )
        }

        if (depth >= maxDepth) return false  // 规则 5：本节点照输出，子树不再下潜
        for (i in 0 until node.childCount) {
            val child = node.child(i) ?: continue
            if (visit(child, depth + 1)) return true
        }
        return false
    }

    visit(root, 0)
    return UiTree(nodes, truncated, scanned)
}

/**
 * 渲染成给模型看的文本，每节点一行：
 *
 * ```
 * [0] Button "允许" (540,1893)-(980,2013) clickable
 * [2] EditText "" (80,600)-(1000,700) editable
 * ```
 *
 * 行首的序号就是"点第几个"的依据；`text` 为 null 时渲染成空串（有控件语义但没文字，
 * 和"这行没有文本"是同一回事）；标志位按 clickable editable checkable checked
 * scrollable 的顺序追加，false 的不输出。截断时最后补一行说明扫描量与输出量。
 */
fun UiTree.render(): String {
    val lines = nodes.map { it.renderLine() }.toMutableList()
    if (truncated) {
        lines += "… 已截断，扫描了 $totalScanned 个节点，输出前 ${nodes.size} 个。"
    }
    // 一个节点都没有（root 为 null，或者屏幕上只剩不可见的壳）：模型只需要知道"没东西可读"。
    // 唯一的例外是 maxNodes <= 0 —— 那时截断行还在，好让人看出是预算给错了而不是屏幕空了
    return if (lines.isEmpty()) EMPTY_TREE_HINT else lines.joinToString("\n")
}

private const val EMPTY_TREE_HINT = "（屏幕上没有可读取的内容）"

/** 给模型的单条文字上限。整段公告、整屏日志对"点哪个按钮"没有帮助，只是白烧 token */
private const val MAX_TEXT_CHARS = 80

/**
 * 空白折叠 + 去首尾 + 超长截断。
 *
 * 空和空白都归成 null 而不是空串：「没有文字」和「文字是空的」在规则 2 里是同一件事，
 * 用一个值表示能少一处分支；渲染时再按需要写成 `""`。
 */
private fun cleanText(raw: CharSequence?): String? {
    val flat = raw?.toString()?.replace(BLANKS, " ")?.trim().orEmpty()
    if (flat.isEmpty()) return null
    if (flat.length <= MAX_TEXT_CHARS) return flat
    var end = MAX_TEXT_CHARS
    // 第 80 个字符正好是代理对的高位时退一格，否则切出来是半个 emoji（到模型那边就是乱码）
    if (Character.isHighSurrogate(flat[end - 1])) end--
    return flat.substring(0, end) + "…"
}

/** 连续空白。`\s` 之外还要盖住 Android 布局里常见的 NBSP / 全角空格，它们看着也像空格 */
private val BLANKS = Regex("[\\s\\u00A0\\u2007\\u202F\\u3000]+")

/** `android.widget.Button` → `Button`。类名缺失时给 `View` 兜底，那一列不能空着 */
private fun simpleClassName(raw: CharSequence?): String {
    val full = raw?.toString()?.trim().orEmpty()
    return full.substringAfterLast('.').ifEmpty { "View" }
}

/**
 * mockable android.jar 会把 `Rect` 的构造函数体抹成空壳 —— `Rect(l, t, r, b)` 出来四个 0，
 * 只有字段是真的。所以这里不用拷贝构造，逐个字段写。
 */
private fun copyBounds(from: Rect): Rect = Rect().apply {
    left = from.left
    top = from.top
    right = from.right
    bottom = from.bottom
}

private fun UiNode.renderLine(): String = buildString {
    append('[').append(index).append("] ")
    append(className).append(' ')
    append('"').append(text.orEmpty()).append('"')
    append(" (").append(bounds.left).append(',').append(bounds.top)
    append(")-(").append(bounds.right).append(',').append(bounds.bottom).append(')')
    if (clickable) append(" clickable")
    if (editable) append(" editable")
    if (checkable) append(" checkable")
    if (checkable && checked) append(" checked")  // 不在勾选框上的 checked 是噪声，别让模型以为这里有个开关
    if (scrollable) append(" scrollable")
}

/**
 * framework 节点 → [UiNodeSource]。
 *
 * 每个子节点重新包一层，`getChild` 对越界索引返回的 null 直接丢掉。不 recycle：
 * 这棵树是调用方从 AccessibilityService 拿的，所有权在那边。
 */
private class AccessibilityNodeSource(private val node: AccessibilityNodeInfo) : UiNodeSource {
    override val childCount: Int get() = node.childCount
    override fun child(index: Int): UiNodeSource? = node.getChild(index)?.let(::AccessibilityNodeSource)
    override val className: CharSequence? get() = node.className
    override val text: CharSequence? get() = node.text
    override val contentDescription: CharSequence? get() = node.contentDescription
    override val visibleToUser: Boolean get() = node.isVisibleToUser
    override val clickable: Boolean get() = node.isClickable
    override val editable: Boolean get() = node.isEditable
    override val checkable: Boolean get() = node.isCheckable
    // API 36 起 isChecked 换成了三态的 getChecked()（false / true / partial）。这里不迁移：
    // [UiNode.checked] 是个 Boolean，本来就表达不了 partial，迁过去还得降回两态，
    // 徒增一条版本分支。要表达三态得先改 UiNode 的形状，那是另一件事
    @Suppress("DEPRECATION")
    override val checked: Boolean get() = node.isChecked
    override val scrollable: Boolean get() = node.isScrollable
    override fun boundsInScreen(out: Rect) = node.getBoundsInScreen(out)
}
