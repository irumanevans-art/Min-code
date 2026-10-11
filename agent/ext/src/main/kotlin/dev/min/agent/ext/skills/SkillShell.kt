package dev.min.agent.ext.skills

/**
 * skill / command 正文里的 shell 预执行（CC：`` !`命令` `` 与 ```` ```! ```` 代码块）。纯文本处理，不执行任何东西；
 * 执行、权限、信任都在宿主的 [SkillRunPort] 里。
 *
 * 顺序（为什么这样排）：
 * 1. [tokenize]：把每个标记换成 NUL 包住的序号占位符，命令文本里的 `${CLAUDE_SKILL_DIR}` / `${CLAUDE_PROJECT_DIR}` 先代入；
 * 2. 渲染（参数替换等）：参数是用户打的字，此时它里面的 `!` 标记已经不会被识别，不会借参数执行命令；
 * 3. 宿主逐条执行命令；
 * 4. [splice]：按序号把输出回填。输出只在这一步进入正文、单趟替换，**不再被扫描**（文档：「a command cannot emit a
 *    placeholder for a later pass to expand」）。
 *
 * 识别规则照文档：行首或空白之后的 `!`…`` 才是标记（`KEY=!`cmd`` 原样保留）；多行命令用 ```` ```! ```` 代码块。
 */
object SkillShell {
    /** 占位符：NUL 包住的序号。正文与参数里本来就有的 NUL 先换掉，免得它们伪装成占位符 */
    private val TOKEN = Regex("\u0000(\\d+)\u0000")

    /** 代码块在前、行内在后：代码块里的 `!`…`` 不会被当成行内标记 */
    private val MARKER = Regex(
        "(?m)^```!\\s*\\n(?<fence>[\\s\\S]*?)\\n```[ \\t]*$" +
            "|(?:^|(?<=\\s))!`(?<inline>[^`\\n]+)`",
    )

    /** [text] 里标记已换成占位符，[commands] 按占位符序号排列（已代入路径变量） */
    class Tokenized(val text: String, val commands: List<String>)

    /**
     * [skillDir]：skill 的目录（command 为 null，`${CLAUDE_SKILL_DIR}` 原样留着）；[projectDir]：会话的主工作目录。
     * 命令取值为 null 的变量不代入，原样留在命令里（与 [SkillPrompt] 同一条「不猜值」的规矩）。
     */
    fun tokenize(body: String, skillDir: String?, projectDir: String?): Tokenized {
        val clean = body.replace('\u0000', '�')
        val commands = mutableListOf<String>()
        val text = StringBuilder()
        var last = 0
        for (m in MARKER.findAll(clean)) {
            text.append(clean, last, m.range.first)
            val raw = m.groups["fence"]?.value ?: m.groups["inline"]!!.value
            text.append('\u0000').append(commands.size).append('\u0000')
            commands += substituteVars(raw, skillDir, projectDir)
            last = m.range.last + 1
        }
        text.append(clean, last, clean.length)
        return Tokenized(text.toString(), commands)
    }

    /** 把执行结果按序号回填。[outputs] 与 [Tokenized.commands] 一一对应；单趟替换，输出原样插入 */
    fun splice(text: String, outputs: List<String>): String {
        if (outputs.isEmpty()) return text.replace(TOKEN, "")
        return TOKEN.replace(text) { m -> outputs.getOrElse(m.groupValues[1].toInt()) { "" } }
    }

    private fun substituteVars(command: String, skillDir: String?, projectDir: String?): String {
        var c = command
        if (skillDir != null) c = c.replace("\${CLAUDE_SKILL_DIR}", skillDir)
        if (projectDir != null) c = c.replace("\${CLAUDE_PROJECT_DIR}", projectDir)
        return c.trim()
    }
}
