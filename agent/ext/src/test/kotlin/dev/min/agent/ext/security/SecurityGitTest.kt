package dev.min.agent.ext.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** security-guidance 取改动清单的 git 命令与输出解析（纯逻辑）。 */
class SecurityGitTest {
    private val a = "a".repeat(40)
    private val b = "b".repeat(40)
    private val c = "c".repeat(64)

    @Test
    fun `the snapshot script never touches the real index, refs or work tree`() {
        val s = SecurityGit.SNAPSHOT_SCRIPT
        // 只在临时索引上 add / write-tree
        assertTrue(s.contains("GIT_INDEX_FILE=\"\$i\" git add -A") && s.contains("write-tree"))
        assertTrue(s.contains("rm -f \"\$i\""))
        for (forbidden in listOf("git commit", "git stash", "git reset", "git checkout", "update-ref", "git rm")) assertFalse(forbidden, s.contains(forbidden))
        // 临时索引名带进程号，两个并发的快照不会互相踩
        assertTrue(s.contains("min-sg-index.\$\$"))
    }

    @Test
    fun `a tree hash is read from the last line and nothing else counts`() {
        assertEquals(a, SecurityGit.parseTree("$a\n"))
        assertEquals(c, SecurityGit.parseTree("warning: something\n$c\n"))
        assertNull(SecurityGit.parseTree(""))
        assertNull(SecurityGit.parseTree("fatal: not a git repository"))
        assertNull(SecurityGit.parseTree("${a}x"))
        assertNull(SecurityGit.parseTree("A".repeat(40)))
    }

    @Test
    fun `only real hashes can be put into a command`() {
        assertTrue(SecurityGit.diffScript(a, b).contains("diff --no-color --no-ext-diff --name-only $a $b"))
        for (bad in listOf("$a; rm -rf /", "HEAD", "", "$a $b")) {
            val failed = try { SecurityGit.diffScript(bad, b); false } catch (e: IllegalArgumentException) { true }
            assertTrue(bad, failed)
        }
        val failed = try { SecurityGit.pushLogScript(listOf("main"), emptyList()); false } catch (e: IllegalArgumentException) { true }
        assertTrue(failed)
    }

    @Test
    fun `the diff output splits into the file list and one patch per file`() {
        val out = """
            TOP=/repo
            ---MIN-SG-NAMES---
            src/a.py
            docs/b c.md
            ---MIN-SG-PATCH---
            diff --git a/src/a.py b/src/a.py
            index 1..2 100644
            --- a/src/a.py
            +++ b/src/a.py
            @@ -1 +1 @@
            -x
            +eval(y)
            diff --git a/docs/b c.md b/docs/b c.md
            +++ b/docs/b c.md
            +hello
        """.trimIndent()
        val d = SecurityGit.parseDiff(out)!!
        assertEquals("/repo", d.top)
        assertEquals(listOf("src/a.py", "docs/b c.md"), d.changes.map { it.rel })
        assertTrue(d.changes[0].patch.contains("+eval(y)") && !d.changes[0].patch.contains("hello"))
        assertTrue(d.changes[1].patch.contains("+hello"))
        assertNull(SecurityGit.parseDiff("garbage"))
    }

    @Test
    fun `a file whose patch was cut off by the size cap stays in the list with an empty patch`() {
        val d = SecurityGit.parseDiff("TOP=/r\n---MIN-SG-NAMES---\nbig.bin\nsmall.txt\n---MIN-SG-PATCH---\ndiff --git a/small.txt b/small.txt\n+x\n")!!
        assertEquals(listOf("big.bin", "small.txt"), d.changes.map { it.rel })
        assertEquals("", d.changes[0].patch)
        assertTrue(d.changes[1].patch.contains("+x"))
    }

    @Test
    fun `what a push delivered is whatever remote ref moved or appeared`() {
        val before = SecurityGit.parseRefs("$a refs/remotes/origin/main\n$a refs/remotes/origin/HEAD\nnot-a-hash refs/remotes/x\n")
        assertEquals(mapOf("refs/remotes/origin/main" to a, "refs/remotes/origin/HEAD" to a), before)
        val after = before + ("refs/remotes/origin/main" to b) + ("refs/remotes/origin/feature" to c) + ("refs/remotes/origin/HEAD" to b)
        assertEquals(listOf(b, c), SecurityGit.pushedTips(before, after))
        // 什么都没动（Everything up-to-date）：没有新提交
        assertTrue(SecurityGit.pushedTips(before, before).isEmpty())
    }

    @Test
    fun `the push log only counts commits the remote did not have`() {
        val s = SecurityGit.pushLogScript(listOf(b, c), listOf(a, a))
        assertTrue(s, s.contains("git rev-list --count $b $c --not $a "))
        assertTrue(s.contains("--reverse") && s.contains("--max-count=${SecurityGit.MAX_PUSH_COMMITS}"))
        // 推送前远端什么都没有：不带 --not
        assertFalse(SecurityGit.pushLogScript(listOf(b), emptyList()).contains("--not"))

        val log = SecurityGit.parsePushLog("COUNT=3\ncommit $a\nfirst\n\ndiff --git a/x b/x\n+1\n")!!
        assertEquals(3, log.commits)
        assertTrue(log.text.startsWith("commit $a"))
        assertNull(SecurityGit.parsePushLog("COUNT=0\n"))
        assertNull(SecurityGit.parsePushLog("COUNT=\n"))
        assertNull(SecurityGit.parsePushLog("nonsense"))
    }
}
