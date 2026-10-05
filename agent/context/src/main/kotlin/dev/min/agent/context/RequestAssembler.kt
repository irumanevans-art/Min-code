package dev.min.agent.context

import dev.min.agent.llm.CacheHints
import dev.min.agent.llm.ModelProfile
import dev.min.agent.llm.ModelRequest
import dev.min.agent.llm.ToolSpec
import dev.min.agent.protocol.Effort
import dev.min.agent.spi.AutoMemoryInfo
import dev.min.agent.spi.LoadedMemoryFile
import dev.min.agent.spi.ModelMessage
import dev.min.agent.spi.SkillsLoaded

/**
 * 把「模型该看到的东西」组成一次规范请求。输入全部来自日志投影与会话开始时定下的环境，
 * 所以同一段日志组出的请求逐字节相同——恢复后重发、测试里比对快照都靠这一点。
 *
 * 记忆文件（[memory]）是会话创建时写进日志的快照，不在这里读磁盘。
 * skill 清单（[skills]）同样是创建时冻结的快照。世界状态增量、压缩在 M3 加进来（docs/agent/04-子系统设计.md §4.5–4.6）。
 */
object RequestAssembler {
    fun assemble(
        profile: ModelProfile,
        environment: Environment,
        messages: List<ModelMessage>,
        tools: List<ToolSpec>,
        effort: Effort?,
        cacheKey: String,
        memory: List<LoadedMemoryFile> = emptyList(),
        agentInstructions: String? = null,
        skills: SkillsLoaded? = null,
        autoMemory: AutoMemoryInfo? = null,
        /** 服务器名 → MCP 服务器给的说明（已截断、已按名字排序） */
        mcpInstructions: Map<String, String> = emptyMap(),
        outputStyle: dev.min.agent.spi.OutputStyleLoaded? = null,
    ): ModelRequest = ModelRequest(
        profile = profile,
        system = SystemPrompt.build(profile, environment, memory, agentInstructions, skills, autoMemory, mcpInstructions, outputStyle),
        messages = messages,
        tools = tools,
        effort = effort,
        // 手机上断断续续地用，5 分钟的缓存常常等不到下一句话
        cache = CacheHints(key = cacheKey, longTtl = true),
    )
}
