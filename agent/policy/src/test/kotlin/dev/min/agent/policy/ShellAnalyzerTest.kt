package dev.min.agent.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ShellAnalyzerTest {
    private fun parsed(script: String): List<SimpleCommand> = when (val a = ShellAnalyzer.analyze(script)) {
        is ShellAnalysis.Parsed -> a.commands
        is ShellAnalysis.Untrusted -> fail("应能解析：$script（${a.reason}）") as Nothing
    }

    private fun assertUntrusted(script: String) {
        val a = ShellAnalyzer.analyze(script)
        assertTrue("应看不透：$script，实际 $a", a is ShellAnalysis.Untrusted)
    }

    private fun argvs(script: String) = parsed(script).map { it.argv }

    @Test
    fun `splits on every separator`() {
        assertEquals(
            listOf(listOf("a"), listOf("b"), listOf("c"), listOf("d"), listOf("e"), listOf("f"), listOf("g")),
            argvs("a; b && c || d | e & f\ng"),
        )
        assertEquals(listOf(listOf("a"), listOf("b")), argvs("a |& b"))
        assertEquals(listOf(listOf("a"), listOf("b")), argvs("a\r\nb"))
    }

    @Test
    fun `quotes and escapes produce the words bash would see`() {
        assertEquals(listOf(listOf("echo", "a b", "c d", "e f")), argvs("""echo 'a b' "c d" e\ f"""))
        assertEquals(listOf(listOf("rm", "-rf", "/")), argvs("""'r''m' -rf /"""))
        assertEquals(listOf(listOf("rm", "-rf", "/")), argvs("""r\m -rf /"""))
        assertEquals(listOf(listOf("echo", "a\"b\\c")), argvs("""echo "a\"b\\c""""))
        // 引号里的分隔符只是字面量
        assertEquals(listOf(listOf("echo", "a; rm -rf /")), argvs("""echo "a; rm -rf /""""))
        // 续行
        assertEquals(listOf(listOf("ls", "-la")), argvs("ls \\\n-la"))
    }

    @Test
    fun `comments are dropped but a hash inside a word is kept`() {
        assertEquals(listOf(listOf("ls")), argvs("ls # rm -rf /"))
        assertEquals(listOf(listOf("echo", "a#b")), argvs("echo a#b"))
    }

    @Test
    fun `leading assignments and harmless wrappers are stripped`() {
        val cmd = parsed("FOO=1 BAR='x y' timeout -s KILL 10 nice -n 5 nohup env -i A=b rm -rf /").single()
        assertEquals(listOf("rm", "-rf", "/"), cmd.argv)
        assertEquals("rm", cmd.program)
        assertTrue(cmd.originalText.startsWith("timeout"))
        assertEquals(listOf("make"), parsed("time -p stdbuf -oL make").single().argv)
        assertEquals(listOf("ls"), parsed("exec ls").single().argv)
        assertEquals(listOf("ls"), parsed("command ls").single().argv)
    }

    @Test
    fun `a line with only assignments runs nothing`() {
        assertEquals(emptyList<SimpleCommand>(), parsed("FOO=1 BAR=2"))
    }

    @Test
    fun `command -v is a query, not an execution`() {
        assertEquals(listOf("command", "-v", "rm"), parsed("command -v rm").single().argv)
    }

    @Test
    fun `program is the basename of the executable`() {
        assertEquals("rm", parsed("/bin/rm -rf x").single().program)
    }

    @Test
    fun `shell -c scripts are analyzed recursively`() {
        assertEquals(listOf(listOf("ls"), listOf("rm", "-rf", "/")), argvs("bash -c 'ls; rm -rf /'"))
        assertEquals(listOf(listOf("rm", "-rf", "/")), argvs("sh -c \"bash -c 'rm -rf /'\""))
        assertUntrusted("sh -c \"bash -c 'zsh -c \\\"rm -rf /\\\"'\"")
        assertUntrusted("bash -c")
        assertUntrusted("bash -c \"\$CMD\"")
    }

    @Test
    fun `outer redirects apply to every command inside shell -c`() {
        val cmds = parsed("bash -c 'a; b' > out.txt")
        assertTrue(cmds.all { c -> c.redirects.any { it.writesFile && it.target == "out.txt" } })
    }

    @Test
    fun `redirections are separated from argv`() {
        val cmd = parsed("cat a 2>/dev/null >out.txt 2>&1 <in.txt >>log").single()
        assertEquals(listOf("cat", "a"), cmd.argv)
        assertEquals(
            listOf(Redirect("2>", "/dev/null"), Redirect(">", "out.txt"), Redirect("2>&", "&1"), Redirect("<", "in.txt"), Redirect(">>", "log")),
            cmd.redirects,
        )
        assertEquals(listOf("out.txt", "log"), cmd.redirects.filter { it.writesFile }.map { it.target })
    }

    @Test
    fun `ampersand redirections write files`() {
        assertEquals(listOf(Redirect("&>", "all.log")), parsed("make &> all.log").single().redirects)
        assertEquals(listOf(Redirect(">&", "f")), parsed("make >& f").single().redirects)
        assertEquals(listOf(Redirect(">&", "&2")), parsed("echo x >&2").single().redirects)
        assertFalse(Redirect(">&", "&2").writesFile)
        assertTrue(Redirect(">|", "f").writesFile)
    }

    @Test
    fun `a digit inside a word is not a file descriptor`() {
        assertEquals(listOf("echo", "a2"), parsed("echo a2>f").single().argv)
        assertEquals(listOf(Redirect(">", "f")), parsed("echo a2>f").single().redirects)
    }

    @Test
    fun `variables are marked as expansions`() {
        assertTrue(parsed("rm -rf \$DIR").single().hasExpansion)
        assertTrue(parsed("rm -rf \"\${DIR}/x\"").single().hasExpansion)
        assertTrue(parsed("echo \$1 \$@").single().hasExpansion)
        assertFalse(parsed("echo 'not \$a var'").single().hasExpansion)
        assertFalse(parsed("echo cost: \$").single().hasExpansion)
    }

    @Test
    fun `anything static analysis cannot see through is untrusted`() {
        listOf(
            "echo `rm -rf /`",
            "echo \"`id`\"",
            "echo \$(rm -rf /)",
            "echo \"\$(id)\"",
            "echo \$((1+2))",
            "(cd /; rm -rf .)",
            "diff <(ls a) <(ls b)",
            "tee >(wc)",
            "cat <<EOF\nrm\nEOF",
            "cat <<< 'x'",
            "eval 'rm -rf /'",
            "source ./evil.sh",
            ". ./evil.sh",
            "if true; then rm -rf /; fi",
            "for f in *; do rm \"\$f\"; done",
            "while true; do :; done",
            "{ rm -rf /; }",
            "! rm x",
            "[[ -f x ]]",
            "function f { rm; }",
            "echo \${x:-\$(id)}",
            "echo 'unclosed",
            "echo \"unclosed",
            "echo \${unclosed",
            "ls \\",
            "echo > \$OUT",
            "echo >",
            "timeout",
        ).forEach(::assertUntrusted)
    }

    @Test
    fun `ANSI-C quoting cannot smuggle a command name`() {
        // $'\x72m' 在 bash 里是 rm：字面上看不出来，只能不信任
        assertUntrusted("\$'\\x72m' -rf /")
        assertUntrusted("echo \$\"msg\"")
    }

    @Test
    fun `quoted reserved words are ordinary words`() {
        assertEquals(listOf("echo", "if"), parsed("echo if").single().argv)
        assertEquals(listOf("if"), parsed("'if'").single().argv)
    }

    @Test
    fun `wrapper written as quoted word is the program itself`() {
        assertEquals("timeout", parsed("'timeout' 5 ls").single().program)
    }
}
