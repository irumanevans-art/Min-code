package dev.min.code.core.engine.min

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 守卫：`SessionStore.open` 是**写者附着**专用（扫描后做修复性截断——见 `JsonlSessionLog.open` 的注释）。
 * 任何「只是想读」的调用方（抽屉索引、目录、轮询）在活日志上走它，都会把并发写者刚落盘的完整记录
 * 截掉，之后 seq 断档、整份日志报废——App 的会话列表在回合进行中打开抽屉时就会踩中（H2）。
 * 读一律走 `SessionStore.peek`（只读视图）。
 *
 * 扫描 `app/src/main` 与 agent 各模块的 `src/main` 里接收者以 store 结尾的 `.open(` 调用（本代码库的约定叫法），
 * 白名单只有 [ALLOWED] 里写明理由的那几处；注释行不算。按接收者名字匹配挡不住改名绕行，
 * 但挡得住「顺手拿 store.open 去读」这一类回归——正是它害死过会话。
 */
class SessionStoreOpenGuardTest {
    /** 相对路径（正斜杠）-> 为什么允许在这里附着写者 */
    private val ALLOWED = mapOf(
        // 唯一合法的写者附着点：重开恢复 / 内核建会话，此后这个进程就是该日志的写者
        "agent/kernel/src/main/kotlin/dev/min/agent/kernel/SessionRuntime.kt" to "写者附着（重开恢复）的唯一入口",
    )

    private val CALL = Regex("""\w*[Ss]tore\.open\s*\(""")

    @Test fun `main 源码里只有写者附着可以调 SessionStore 的 open`() {
        val root = repoRoot()
        val scanned = mutableListOf<File>()
        root.resolve("app/src/main").takeIf { it.isDirectory }?.let(scanned::add)
        root.resolve("agent").listFiles { f: File -> f.isDirectory }!!
            .map { it.resolve("src/main") }.filter { it.isDirectory }.forEach(scanned::add)
        assertTrue("没扫到任何源码根，守卫自身失效：${scanned.map { it.path }}", scanned.isNotEmpty())

        val violations = scanned.flatMap { dir ->
            dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { f ->
                f.readLines().withIndex()
                    .filter { (_, line) -> CALL.containsMatchIn(line) }
                    // KDoc / 行注释里提到它不算调用
                    .filter { (_, line) -> line.trimStart().startsWith("*").not() && line.trimStart().startsWith("//").not() }
                    .map { (n, line) -> "${f.toRelativeString(root).replace('\\', '/')}:$n: ${line.trim()}" }
            }
        }.filter { hit -> ALLOWED.keys.none { allowed -> hit.startsWith("$allowed:") } }
        assertTrue(
            "这些调用方在用写者附着的 open 读日志——读必须走只读的 peek（H2 的根因）：\n${violations.joinToString("\n")}",
            violations.isEmpty(),
        )
    }

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (dir.listFiles()?.any { it.name == "settings.gradle.kts" || it.name == "settings.gradle" } != true) {
            dir = dir.parentFile ?: error("从 user.dir 向上找不到 settings.gradle(.kts)，定位不了仓库根")
        }
        return dir
    }
}
