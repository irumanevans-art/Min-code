package dev.min.code.core.claudecode

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 删东西的命令不托管（[looksDestructive]）：托管 = Min 自己跑掉，会绕过 CLI 2.1.281 起
 * 对危险删除的审批（bypass 下也要批）。宁可错杀 —— 错杀只是少一个预览位。
 */
class HostedBashSafetyTest {

    private fun bash(command: String) = buildJsonObject {
        put("command", command)
        put("run_in_background", JsonPrimitive(true))
    }

    @Test
    fun `deleting before starting a server is not hosted`() {
        assertNull(hostedBashPlan(bash("""rm -rf "${'$'}(pwd)"/dist && npm run dev &""")))
        assertNull(hostedBashPlan(bash("""rm -rf "${'$'}(pwd)"""")))
    }

    @Test
    fun `a plain server is still hosted`() {
        assertEquals("npm run dev", hostedBashPlan(bash("npm run dev &"))?.command)
        assertEquals(
            "for i in ${'$'}(seq 1 600); do echo tick ${'$'}i; sleep 1; done",
            hostedBashPlan(bash("for i in ${'$'}(seq 1 600); do echo tick ${'$'}i; sleep 1; done"))?.command,
        )
    }

    @Test
    fun `destructive commands in their usual disguises are caught`() {
        listOf(
            "rm dist/app.js && vite",
            "/bin/rm -r build; npm start",
            "sudo rm -f /tmp/x",
            "cd app && npm run dev \$(rm -rf node_modules)",
            "rmdir out",
            "find . -name '*.pyc' -delete; python app.py",
            "find build -exec rm {} +",
            "git clean -fdx && npm run dev",
            "git reset --hard HEAD~1",
            "git checkout -- . && vite",
            "git restore src",
            "git stash drop",
            "git branch -D old",
            "git push origin main --force",
            "git push -f",
            "dd if=/dev/zero of=disk.img bs=1M count=1",
            "echo x > /dev/sda",
            "chmod -R 777 /",
            "chown --recursive me .",
            "mv data /dev/null",
            "truncate -s 0 app.log && node server.js",
            "shred secrets.txt",
            "mkfs.ext4 /dev/loop0",
        ).forEach { assertTrue(it, looksDestructive(it)) }
    }

    @Test
    fun `ordinary server commands are not mistaken for deletion`() {
        listOf(
            "npm run dev",
            "python3 -m http.server 8080",
            "npx serve dist --confirm",
            "node form-server.js",
            "uvicorn app:main --reload",
            "npm-rm-helper start",
            "git checkout main && npm run dev",
            "git push origin main",
            "tail -f logs/app.log",
            "chmod +x run.sh && ./run.sh",
        ).forEach { assertFalse(it, looksDestructive(it)) }
    }

    /** 钉住收紧后的 git push 规则：+refspec 强推变体必须命中 */
    @Test
    fun `force-push refspec variants are caught`() {
        listOf(
            "git push origin +main",
            "git push origin +local:remote/track",
            "git push --force origin main",
            // --force 前缀连带命中 -with-lease 变体（规则按前缀匹配，未放行该变体）
            "git push --force-with-lease origin main",
        ).forEach { assertTrue(it, looksDestructive(it)) }
        assertFalse("plain push", looksDestructive("git push origin main"))
    }

    /** 钉住 mv→/dev/null 规则的引号容忍与射程边界 */
    @Test
    fun `mv into dev null survives quoting, other paths do not match`() {
        listOf(
            "mv data /dev/null",
            "mv data \"/dev/null\"",
            "mv data '/dev/null'",
            "mv data /dev/null.bak",
        ).forEach { assertTrue(it, looksDestructive(it)) }
        listOf(
            // 普通重定向不在 mv 规则射程内（重定向规则只拦块设备）——已知边界，如实钉住
            "echo x > /dev/null",
            // mv 规则只认 /dev/null
            "mv data /dev/sda",
            "cp /dev/zero /dev/null",
        ).forEach { assertFalse(it, looksDestructive(it)) }
    }

    /** 钉住递归改权限规则：mode 在前或在后都要命中（参数后置是最常见的真实写法） */
    @Test
    fun `recursive chmod chown are caught with flag before or after the mode`() {
        listOf(
            "chmod 000 -R /workspace/data",
            "chmod -R 000 /workspace/data",
            "chmod --recursive 000 /opt",
            "chown deploy:deploy -R /srv/app",
            "chgrp staff -R /srv/shared",
        ).forEach { assertTrue(it, looksDestructive(it)) }
        listOf(
            "chmod 755 deploy.sh",
            "chmod +x run.sh && ./run.sh",
            "chown me file.txt",
        ).forEach { assertFalse(it, looksDestructive(it)) }
    }
}
