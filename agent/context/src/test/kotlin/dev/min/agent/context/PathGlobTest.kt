package dev.min.agent.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 规则 `paths:` 的 glob：期望值照 CC 文档里的例子，匹配对象是相对项目根的路径 */
class PathGlobTest {
    private fun yes(pattern: String, path: String) = assertTrue("$pattern 应匹配 $path", PathGlob.matches(pattern, path))
    private fun no(pattern: String, path: String) = assertFalse("$pattern 不应匹配 $path", PathGlob.matches(pattern, path))

    @Test
    fun `double star spans directories and also matches zero of them`() {
        yes("src/**/*.ts", "src/a.ts")
        yes("src/**/*.ts", "src/api/v1/a.ts")
        no("src/**/*.ts", "lib/a.ts")
        no("src/**/*.ts", "src/a.tsx")
        yes("**/*.ts", "a.ts")
        yes("**/*.ts", "deep/er/a.ts")
        yes("src/**", "src/a/b/c.md")
        no("src/**", "srcs/a")
    }

    @Test
    fun `single star stays inside one directory`() {
        yes("src/components/*.tsx", "src/components/Button.tsx")
        no("src/components/*.tsx", "src/components/ui/Button.tsx")
        // 不含斜杠的模式只匹配根下的文件，不是 gitignore 的任意深度
        yes("*.md", "README.md")
        no("*.md", "docs/README.md")
    }

    @Test
    fun `braces, question marks and character classes`() {
        yes("src/**/*.{ts,tsx}", "src/ui/a.tsx")
        yes("src/**/*.{ts,tsx}", "src/a.ts")
        no("src/**/*.{ts,tsx}", "src/a.js")
        yes("{src,lib}/**/*.kt", "lib/x/y.kt")
        yes("src/{a,b{1,2}}/x.ts", "src/b2/x.ts")
        no("src/{a,b{1,2}}/x.ts", "src/b3/x.ts")
        yes("src/?.ts", "src/a.ts")
        no("src/?.ts", "src/ab.ts")
        yes("src/[ab].ts", "src/b.ts")
        no("src/[ab].ts", "src/c.ts")
        yes("src/[!ab].ts", "src/c.ts")
    }

    @Test
    fun `regex metacharacters in the pattern are literal`() {
        yes("a+b(1).txt", "a+b(1).txt")
        no("a.b", "axb")
        yes("docs/v1.0/**", "docs/v1.0/x")
        // 没闭合的花括号不会让匹配抛异常
        assertEquals(false, PathGlob.matches("src/{a,b", "src/c"))
    }

    @Test
    fun `leading dot slash and slash are relative to the project root`() {
        yes("./src/**", "src/a.ts")
        yes("/src/**", "src/a.ts")
        no("", "src/a.ts")
    }
}
