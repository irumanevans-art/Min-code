package dev.min.agent.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

/**
 * `--add-dir`（CC cli-reference）：一个旗标后面可以跟一串目录，也可以重复写；每个都要是已存在的目录。
 * 目录要解析成绝对路径（相对 --cwd 的默认目录）。
 */
class AddDirArgsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun work(): Path = Files.createDirectories(tmp.root.toPath().resolve("work"))

    @Test
    fun `one flag takes several directories up to the next flag`() {
        val work = work()
        val a = Files.createDirectories(tmp.root.toPath().resolve("apps"))
        val b = Files.createDirectories(tmp.root.toPath().resolve("lib"))
        val args = parseArgs(arrayOf("--model", "test", "--add-dir", a.toString(), b.toString(), "--bare"), work)
        assertEquals(listOf(a.toAbsolutePath().normalize(), b.toAbsolutePath().normalize()), args.addDirs)
        assertTrue(args.bare)
    }

    @Test
    fun `the flag can repeat and relative paths resolve against the default directory`() {
        val work = work()
        Files.createDirectories(work.resolve("sub"))
        val args = parseArgs(arrayOf("--model", "test", "--add-dir", "sub", "--add-dir", work.toString()), work)
        assertEquals(listOf(work.resolve("sub").toAbsolutePath().normalize(), work.toAbsolutePath().normalize()), args.addDirs)
    }

    @Test
    fun `a path that is not an existing directory is a usage error`() {
        val work = work()
        try {
            parseArgs(arrayOf("--model", "test", "--add-dir", work.resolve("missing").toString()), work)
            fail("expected a usage error")
        } catch (e: UsageException) {
            assertTrue(e.message.orEmpty().contains("--add-dir 不是目录"))
        }
    }

    @Test
    fun `the flag with no directory is a usage error`() {
        val work = work()
        try {
            parseArgs(arrayOf("--model", "test", "--add-dir"), work)
            fail("expected a usage error")
        } catch (e: UsageException) {
            assertTrue(e.message.orEmpty().contains("--add-dir 缺少目录"))
        }
    }
}
