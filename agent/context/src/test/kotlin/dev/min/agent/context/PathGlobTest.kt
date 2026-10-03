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
    fun `braces and question marks`() {
        yes("src/**/*.{ts,tsx}", "src/ui/a.tsx")
        yes("src/**/*.{ts,tsx}", "src/a.ts")
        no("src/**/*.{ts,tsx}", "src/a.js")
        yes("{src,lib}/**/*.kt", "lib/x/y.kt")
        yes("src/{a,b{1,2}}/x.ts", "src/b2/x.ts")
        no("src/{a,b{1,2}}/x.ts", "src/b3/x.ts")
        yes("src/?.ts", "src/a.ts")
        no("src/?.ts", "src/ab.ts")
    }

    @Test
    fun `backslash escapes the next character, so a literal bracket is written with one`() {
        yes("app/\\[id\\]/page.tsx", "app/[id]/page.tsx")
        no("app/\\[id\\]/page.tsx", "app/i/page.tsx")
        yes("foo\\*bar.ts", "foo*bar.ts")
        no("foo\\*bar.ts", "fooxbar.ts")
        yes("a\\{b,c\\}.ts", "a{b,c}.ts")
        no("a\\{b,c\\}.ts", "ab.ts")
        // 末尾孤零零的反斜杠是字面反斜杠
        yes("dir\\", "dir\\")
    }

    @Test
    fun `an unescaped open bracket invalidates the whole pattern instead of being a character set`() {
        // CC 文档：未转义的 [ 让模式匹配不到任何文件
        no("src/[ab].ts", "src/a.ts")
        no("src/[ab].ts", "src/[ab].ts")
        no("app/[id]/page.tsx", "app/[id]/page.tsx")
        no("app/[id]/page.tsx", "app/i/page.tsx")
        no("src/[!ab].ts", "src/c.ts")
        assertTrue(PathGlob.compile("src/[ab].ts") is PathGlob.Compiled.Invalid)
        assertTrue(PathGlob.compile("src/\\[ab\\].ts") is PathGlob.Compiled.Ok)
    }

    @Test
    fun `patterns that used to blow up the regex now simply match nothing`() {
        listOf("[z-a].ts", "app/**/[[...slug]]/page.tsx", "[[:alpha:]].ts", "[]a].ts", "a&&b/[a&&b].ts").forEach { pattern ->
            assertEquals("$pattern 不抛异常、不匹配", false, PathGlob.matches(pattern, "a.ts"))
            assertTrue(PathGlob.compile(pattern) is PathGlob.Compiled.Invalid)
        }
    }

    @Test
    fun `a run of double-star segments is as fast as one`() {
        val pattern = "**/**/**/**/**/**/**/x.ts"
        val deep = (1..30).joinToString("/") { "d$it" } + "/y.tsx"
        val started = System.nanoTime()
        no(pattern, deep)
        yes(pattern, "a/b/c/x.ts")
        assertTrue("不应回溯爆炸", (System.nanoTime() - started) / 1_000_000 < 2_000)
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
