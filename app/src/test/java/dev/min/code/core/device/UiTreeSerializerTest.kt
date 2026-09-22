package dev.min.code.core.device

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 裁剪规则的单测。
 *
 * 一棵真树是造不出来的：AccessibilityNodeInfo 是 final 类，构造在 framework 手里，
 * 用 mockable android.jar 造出来的节点读什么都是 0/false —— 那种"能跑"的测试等于没测。
 * 所以这里喂 [UiNodeSource] 假树，序列化器本体一个字都不差地跑同一套规则。
 */
class UiTreeSerializerTest {

    @Test
    fun `纯布局容器被扁平化，子节点照常输出`() {
        val root = FakeNode(
            className = "android.widget.FrameLayout",
            children = listOf(
                FakeNode(
                    className = "android.widget.LinearLayout",
                    children = listOf(
                        FakeNode(
                            className = "android.widget.Button",
                            text = "允许",
                            clickable = true,
                            area = rect(540, 1893, 980, 2013),
                        ),
                        FakeNode(
                            className = "android.widget.TextView",
                            text = "这个应用想要访问你的照片",
                            area = rect(120, 1200, 1400, 1400),
                        ),
                    ),
                ),
            ),
        )

        val tree = serializeUiTree(root)

        assertEquals(listOf("Button", "TextView"), tree.nodes.map { it.className })
        assertEquals(listOf(0, 1), tree.nodes.map { it.index })  // 两层容器不占号，index 从 0 连续
        assertFalse(tree.truncated)
        // 两层容器也访问过。模型靠这个数知道"屏幕上还有多少我没看到"
        assertEquals(4, tree.totalScanned)
    }

    @Test
    fun `render 一行一个节点，null 文本渲染成空串`() {
        val root = FakeNode(
            className = "android.widget.FrameLayout",
            children = listOf(
                FakeNode(
                    className = "android.widget.Button",
                    text = "允许",
                    clickable = true,
                    area = rect(540, 1893, 980, 2013),
                ),
                // 能编辑但没文字：必须留下来，模型才知道这里有个输入框可以打字
                FakeNode(
                    className = "android.widget.EditText",
                    editable = true,
                    area = rect(80, 600, 1000, 700),
                ),
            ),
        )

        assertEquals(
            """
            [0] Button "允许" (540,1893)-(980,2013) clickable
            [1] EditText "" (80,600)-(1000,700) editable
            """.trimIndent(),
            serializeUiTree(root).render(),
        )
    }

    @Test
    fun `标志位按固定顺序追加，checked 只在 checkable 时才出现`() {
        val root = FakeNode(
            className = "android.widget.FrameLayout",
            children = listOf(
                FakeNode(
                    className = "android.widget.CheckBox",
                    text = "记住选择",
                    clickable = true,
                    checkable = true,
                    checked = true,
                    area = rect(80, 800, 600, 880),
                ),
                FakeNode(
                    className = "android.widget.TextView",
                    text = "全都来",
                    clickable = true,
                    editable = true,
                    checkable = true,
                    scrollable = true,
                    area = rect(0, 0, 10, 10),
                ),
                // framework 偶尔把 checked 给到非勾选框上：数据里照实记，渲染时不能当勾选框报
                FakeNode(
                    className = "android.widget.TextView",
                    text = "不在框里",
                    checked = true,
                    area = rect(1, 2, 3, 4),
                ),
            ),
        )

        val tree = serializeUiTree(root)

        assertEquals(
            listOf(
                """[0] CheckBox "记住选择" (80,800)-(600,880) clickable checkable checked""",
                """[1] TextView "全都来" (0,0)-(10,10) clickable editable checkable scrollable""",
                """[2] TextView "不在框里" (1,2)-(3,4)""",
            ),
            tree.render().lines(),
        )
        assertTrue(tree.nodes[2].checked)
    }

    @Test
    fun `不可见的节点整枝丢掉，子树一次都不访问`() {
        val root = FakeNode(
            className = "android.widget.FrameLayout",
            children = listOf(
                FakeNode(
                    className = "android.widget.LinearLayout",
                    visibleToUser = false,
                    children = listOf(
                        FakeNode(className = "android.widget.Button", text = "藏在里面的按钮", clickable = true),
                    ),
                ),
                FakeNode(className = "android.widget.Button", text = "露在外面的按钮", clickable = true),
            ),
        )

        val tree = serializeUiTree(root)

        assertEquals(listOf("露在外面的按钮"), tree.nodes.map { it.text })
        // root + 不可见的容器 + 可见按钮；不可见那枝里的按钮根本没被访问（整枝跳过）
        assertEquals(3, tree.totalScanned)
        assertFalse(tree.truncated)
    }

    @Test
    fun `整棵树都不可见时渲染成没有可读取的内容`() {
        val root = FakeNode(className = "android.widget.FrameLayout", visibleToUser = false)

        val tree = serializeUiTree(root)

        assertTrue(tree.nodes.isEmpty())
        assertEquals(1, tree.totalScanned)  // 它自己被访问过，只是没输出
        assertEquals("（屏幕上没有可读取的内容）", tree.render())
    }

    @Test
    fun `text 为空时回退到 contentDescription，两者都空则为 null`() {
        val root = FakeNode(
            className = "android.widget.FrameLayout",
            children = listOf(
                FakeNode(className = "android.widget.ImageButton", contentDescription = "关闭", clickable = true),
                // 只有空白：等于没文字，字段给 null 而不是空串
                FakeNode(
                    className = "android.widget.ImageButton",
                    text = "   ",
                    contentDescription = "\n\t",
                    clickable = true,
                ),
                FakeNode(className = "android.widget.TextView", text = "有字的", contentDescription = "念出来的"),
            ),
        )

        val tree = serializeUiTree(root)

        assertEquals(listOf("关闭", null, "有字的"), tree.nodes.map { it.text })
        assertNull(tree.nodes[1].text)
    }

    @Test
    fun `空白折叠成单空格，超过 80 字截断并加省略号`() {
        val collapsed = serializeUiTree(
            FakeNode(className = "android.widget.TextView", text = "  第一行\n\n第二行\t\u00A0 第三行  "),
        )
        assertEquals("第一行 第二行 第三行", collapsed.nodes.single().text)

        // 回退来的 contentDescription 也走同一套清洗
        val long = "长".repeat(200)
        val truncated = serializeUiTree(FakeNode(className = "android.widget.TextView", contentDescription = long))
        assertEquals("长".repeat(80) + "…", truncated.nodes.single().text)
    }

    @Test
    fun `截断不会劈开 emoji 的代理对`() {
        // 第 80 个字符正好是 🙂 的高位：宁可少一个字，也不能吐出半个代理对
        val text = "a".repeat(79) + "🙂" + "b".repeat(10)

        val node = serializeUiTree(FakeNode(className = "android.widget.TextView", text = text)).nodes.single()

        assertEquals("a".repeat(79) + "…", node.text)
    }

    @Test
    fun `输出达到 maxNodes 就停止遍历，index 仍然连续`() {
        val root = FakeNode(
            className = "android.widget.FrameLayout",
            children = (0 until 5).map { FakeNode(className = "android.widget.TextView", text = "第 $it 项") },
        )

        val tree = serializeUiTree(root, maxNodes = 3)

        assertTrue(tree.truncated)
        assertEquals(listOf(0, 1, 2), tree.nodes.map { it.index })
        assertEquals(listOf("第 0 项", "第 1 项", "第 2 项"), tree.nodes.map { it.text })
        // root + 三个进了输出的节点。第四个孩子只是被"够到"就停了，没算进扫描
        assertEquals(4, tree.totalScanned)
        assertTrue(tree.render().endsWith("… 已截断，扫描了 4 个节点，输出前 3 个。"))
    }

    @Test
    fun `默认预算 120 个节点`() {
        val root = FakeNode(
            className = "android.widget.FrameLayout",
            children = (0 until 130).map { FakeNode(className = "android.widget.TextView", text = "第 $it 项") },
        )

        val tree = serializeUiTree(root)

        assertEquals(120, tree.nodes.size)
        assertEquals(119, tree.nodes.last().index)
        assertTrue(tree.truncated)
    }

    @Test
    fun `树走完了就不加截断行`() {
        val tree = serializeUiTree(FakeNode(className = "android.widget.TextView", text = "只有一行"))

        assertFalse(tree.truncated)
        assertEquals("""[0] TextView "只有一行" (0,0)-(0,0)""", tree.render())
    }

    @Test
    fun `到 maxDepth 不再下潜，但这不算截断`() {
        val root = FakeNode(
            className = "android.widget.FrameLayout",
            children = listOf(
                FakeNode(
                    className = "android.widget.LinearLayout",
                    children = listOf(
                        FakeNode(className = "android.widget.Button", text = "太深了", clickable = true),
                    ),
                ),
            ),
        )

        val tree = serializeUiTree(root, maxDepth = 1)

        assertTrue(tree.nodes.isEmpty())
        // root + 第一层容器；再往下就没去了，所以那颗按钮不在扫描数里
        assertEquals(2, tree.totalScanned)
        // 深度上限是我们自己设的界，跟"树被裁掉了一半"不是一回事
        assertFalse(tree.truncated)
    }

    @Test
    fun `root 为 null 是空树`() {
        val root: AccessibilityNodeInfo? = null

        val tree = serializeUiTree(root)

        assertTrue(tree.nodes.isEmpty())
        assertFalse(tree.truncated)
        assertEquals(0, tree.totalScanned)
        assertEquals("（屏幕上没有可读取的内容）", tree.render())
    }
}

/**
 * 假节点。默认值挑成"最没存在感"的那种（FrameLayout / 无文字 / 不可点），
 * 每个用例只写它真正关心的那几个字段。
 */
private class FakeNode(
    override val className: CharSequence? = "android.widget.FrameLayout",
    override val text: CharSequence? = null,
    override val contentDescription: CharSequence? = null,
    override val visibleToUser: Boolean = true,
    override val clickable: Boolean = false,
    override val editable: Boolean = false,
    override val checkable: Boolean = false,
    override val checked: Boolean = false,
    override val scrollable: Boolean = false,
    private val area: Rect = rect(0, 0, 0, 0),
    private val children: List<FakeNode> = emptyList(),
) : UiNodeSource {
    override val childCount: Int get() = children.size
    override fun child(index: Int): UiNodeSource? = children.getOrNull(index)

    override fun boundsInScreen(out: Rect) {
        out.left = area.left
        out.top = area.top
        out.right = area.right
        out.bottom = area.bottom
    }
}

/**
 * mockable android.jar 把构造函数体抹成了空壳（只留 super()），`Rect(l, t, r, b)` 出来是
 * 四个 0；字段本身是真的，所以边界值只能一个个写进去 —— 不这么写，断言里的坐标全是 0。
 */
private fun rect(l: Int, t: Int, r: Int, b: Int): Rect = Rect().apply {
    left = l
    top = t
    right = r
    bottom = b
}
