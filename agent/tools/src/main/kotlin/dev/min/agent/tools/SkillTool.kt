package dev.min.agent.tools

import dev.min.agent.spi.HarnessProfile
import dev.min.agent.spi.PermissionAsk
import dev.min.agent.spi.ResumePolicy
import dev.min.agent.spi.SkillInvocation
import dev.min.agent.spi.Tool
import dev.min.agent.spi.ToolContext
import dev.min.agent.spi.ToolInput
import dev.min.agent.spi.ToolProgress
import dev.min.agent.spi.ToolTraits
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject

/** Skill 工具的入参：与 Claude Code 同名同形（`skill` 必填，`args` 可选） */
data class SkillInput(val skill: String, val args: String?)

/**
 * 调用 skill（与 Claude Code 同名同入参）：模型说要用哪个 skill，工具把它的正文（参数已替换）作为结果交回，模型照着做。
 * 找 skill、读正文、渲染都是内核经 [dev.min.agent.spi.SkillPort] 做的（清单是会话创建时冻结的）；这里只管入参与结果的形状。
 *
 * traits：只读、可并发、恢复策略 Replay——加载一段文字没有外部副作用。**默认不弹审批**：Claude Code 因为 skill 的
 * `allowed-tools` 能预批准工具才要审批，第一期没做那个，skill 正文只是文字，它让模型做的事仍逐个过各自的权限判定。
 * 用户的 `Skill(名字)` / `Skill(名字 *)` 允许、询问、拒绝规则照常生效：权限声明里带着 skill 名（specifiers）。
 */
object SkillTool : Tool<SkillInput> {
    override val name = "Skill"

    override val inputSchema: JsonObject = schemaObject(
        listOf("skill"),
        "skill" to prop("string", "系统提示清单里的 skill 名字，原样写，不带斜杠"),
        "args" to prop("string", "可选：传给 skill 的参数，原样文字"),
    )

    override val traits = ToolTraits(readOnly = true, concurrencySafe = true, resumePolicy = ResumePolicy.Replay)

    override fun description(profile: HarnessProfile) =
        "调用 skill：用户或项目预先写好的、针对某类任务的一套操作指引。系统提示里列了可用的 skill（名字 + 说明）；" +
            "手头的任务正好属于某个 skill 时，先调用它，再按加载出的指引去做，不要凭习惯另起炉灶。" +
            "用户说「用 X skill」或敲了 /X，也是要你调用。skill 填清单里的准确名字（不带斜杠），不要猜名字；args 可选，传给 skill 的参数。" +
            "指引已经加载过就直接照着做，不必重复调用。"

    override fun parse(raw: JsonObject): ToolInput<SkillInput> = parseArgs {
        // 模型常把 /name 原样抄进来：去掉开头的斜杠比让它再来一轮便宜（CC 的说明要求不带斜杠，但没有拒绝）
        val name = raw.reqString("skill").trim().removePrefix("/").trim()
        if (name.isEmpty()) throw BadArg("字段 `skill` 不能为空。")
        SkillInput(name, raw.optString("args"))
    }

    override fun permissionAsk(input: SkillInput, ctx: ToolContext) =
        PermissionAsk(tool = name, summary = "加载 skill ${input.skill}", specifiers = listOf(input.skill))

    override fun run(input: SkillInput, ctx: ToolContext): Flow<ToolProgress> = doneFlow {
        when (val r = ctx.skills.invoke(input.skill, input.args)) {
            is SkillInvocation.Loaded -> okResult(r.content)
            is SkillInvocation.Rejected -> failResult(r.messageForModel)
        }
    }
}
