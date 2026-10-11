package dev.min.agent.ext.skills

import dev.min.agent.ext.config.ConfigSource
import dev.min.agent.ext.config.InMemoryFs
import dev.min.agent.spi.GuestPath
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 随包自带的 skill（CC 的 bundled skills）：清单、来源标「内置」、正文从资源读、`disable-model-invocation` 照文档写。
 * 期望对照 CC skills 文档：bundled skills 是 prompt 型，名字与 `/loop`、`/code-review`、`/batch` 等一致。
 */
class BundledSkillsTest {
    @Test
    fun `the four bundled skills are listed in their fixed order with the built-in source label`() {
        val defs = BundledSkills.defs()
        assertEquals(listOf("loop", "code-review", "simplify", "batch"), defs.map { it.name })
        assertTrue(defs.all { it.source == ConfigSource.Bundled })
        assertTrue(defs.all { it.sourceLabel == "内置" })
        assertTrue(defs.all { it.path.value.startsWith(BundledSkills.PATH_PREFIX) && it.path.value.endsWith("/SKILL.md") })
    }

    @Test
    fun `every bundled skill has a non-empty description and body`() {
        for (d in BundledSkills.defs()) {
            assertTrue("${d.name} 描述为空", d.description.isNotBlank())
            assertTrue("${d.name} 正文为空", d.body.isNotBlank())
        }
    }

    @Test
    fun `batch is user-only because it makes large changes, the others are model-invocable`() {
        val byName = BundledSkills.defs().associateBy { it.name }
        assertTrue(byName.getValue("batch").disableModelInvocation)
        assertFalse(byName.getValue("loop").disableModelInvocation)
        assertFalse(byName.getValue("code-review").disableModelInvocation)
        assertFalse(byName.getValue("simplify").disableModelInvocation)
    }

    @Test
    fun `the body is read from the package resources through the same entry point as disk skills`() {
        val fs = InMemoryFs()
        val body = runBlocking { SkillFiles.readBody(fs, GuestPath(BundledSkills.path("loop"))) }
        assertNotNull(body)
        assertTrue(body!!.contains("CronCreate"))
        // 不碰文件系统：内存文件系统里什么都没有
        assertTrue(fs.reads.isEmpty())
    }

    @Test
    fun `an unknown bundled name has no body`() {
        val body = runBlocking { SkillFiles.readBody(InMemoryFs(), GuestPath(BundledSkills.path("nope"))) }
        assertNull(body)
    }

    @Test
    fun `the code-review skill names the ReportFindings tool and drops --comment when there is no GitHub`() {
        val body = BundledSkills.defs().single { it.name == "code-review" }.body
        assertTrue(body.contains("ReportFindings"))
        assertTrue(body.contains("--comment"))
        assertTrue(body.contains("low"))
        assertTrue(body.contains("max"))
    }
}
