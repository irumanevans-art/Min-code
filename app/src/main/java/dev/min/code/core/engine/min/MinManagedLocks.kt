package dev.min.code.core.engine.min

import dev.min.agent.ext.config.ConfigSource
import dev.min.agent.ext.config.SettingsInputs
import dev.min.agent.ext.config.SettingsLoader
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.WorkspaceFs
import dev.min.code.core.settings.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import dev.min.agent.kernel.cronDisabledBy
import dev.min.agent.kernel.cronMaxBudgetUsdFrom
import dev.min.agent.kernel.teamMaxConcurrentFrom
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 托管层（`/etc/claude-code/managed-settings.json` 与同目录 `managed-settings.d` 里的 json 文件，rootfs 里）锁住的 Min 设置项
 * （05「managed / server-managed settings」行）。托管层在 Claude Code 里优先级最高、用户改不了；手机上这几项原本只读 DataStore，
 * 托管层写了也不生效。现在生效值取托管层，设置页那一行写「已由管理员锁定」、点不动。
 *
 * 只认设置页上有对应控件、且桌面 CLI 也从设置里读的键（同一套解析，见内核的 CronSettings / TeamSettings）：
 * - 定时任务开关 ← `env.CLAUDE_CODE_DISABLE_CRON`（写了就锁：真值锁成关，其余锁成开）
 * - 单次花费上限 ← `cronMaxBudgetUsd`（写错的值和桌面一样当没设，也就不锁）
 * - 团队同时运行的成员数 ← `teamMaxConcurrent`
 * - 遥测开关 / 端点 / 头部 ← `env.CLAUDE_CODE_ENABLE_TELEMETRY`（`DISABLE_TELEMETRY`、`DO_NOT_TRACK=1` 锁成关）、
 *   `env.OTEL_EXPORTER_OTLP_ENDPOINT`、`env.OTEL_EXPORTER_OTLP_HEADERS`
 * 网络沙箱的锁另有一套（NetworkSettingsSection，早于本文件）。WebSearch 后端、工作流开关、Min 引擎开关不是 CC 的设置键，没有托管层可言。
 *
 * 字段为 null = 没锁。
 */
data class ManagedLocks(
    val cronEnabled: Boolean? = null,
    val cronMaxBudgetUsd: Double? = null,
    val teamMaxConcurrent: Int? = null,
    val telemetryEnabled: Boolean? = null,
    val telemetryEndpoint: String? = null,
    /** 原文（`Authorization=Bearer …`）。界面只说「已由管理员设置」，从不显示它 */
    val telemetryHeaders: String? = null,
    /** 托管文件存在却读不了或不是 JSON 对象：CC 此时拒绝启动。这里锁不住具体的项，但设置页要明说「托管设置读不了」 */
    val broken: Boolean = false,
)

/** 托管层的原始 JSON -> 锁。纯函数 */
fun managedLocksOf(raw: JsonObject?, broken: Boolean = false): ManagedLocks {
    raw ?: return ManagedLocks(broken = broken)
    // env 的值按 CC 的约定是字符串；写成数字的也收（`"DO_NOT_TRACK": 1`），只取原样文字
    val env = (raw["env"] as? JsonObject).orEmpty().mapNotNull { (k, v) -> (v as? JsonPrimitive)?.content?.let { k to it } }.toMap()
    // 与 OtelConfig.fromEnv 同一个口径：DISABLE_TELEMETRY 非空、DO_NOT_TRACK=1 都压过开关
    val optedOut = !env["DISABLE_TELEMETRY"].isNullOrBlank() || env["DO_NOT_TRACK"]?.trim() == "1"
    return ManagedLocks(
        cronEnabled = if ("CLAUDE_CODE_DISABLE_CRON" in env) !cronDisabledBy(env::get) else null,
        cronMaxBudgetUsd = raw["cronMaxBudgetUsd"]?.let { cronMaxBudgetUsdFrom(it) {} },
        teamMaxConcurrent = raw["teamMaxConcurrent"]?.let { teamMaxConcurrentFrom(it) {} },
        telemetryEnabled = when {
            optedOut -> false
            "CLAUDE_CODE_ENABLE_TELEMETRY" in env -> env["CLAUDE_CODE_ENABLE_TELEMETRY"]?.trim() == "1"
            else -> null
        },
        telemetryEndpoint = env["OTEL_EXPORTER_OTLP_ENDPOINT"]?.trim()?.takeIf { it.isNotEmpty() },
        telemetryHeaders = env["OTEL_EXPORTER_OTLP_HEADERS"]?.takeIf { it.isNotBlank() },
        broken = broken,
    )
}

/** 设置的生效值：锁住的项换成托管层的值。引擎（Hub 的上限、定时任务开关）和「立即导出」都读这一份 */
fun AppSettings.withManaged(locks: ManagedLocks): AppSettings = copy(
    cronEnabled = locks.cronEnabled ?: cronEnabled,
    cronMaxBudgetUsd = locks.cronMaxBudgetUsd ?: cronMaxBudgetUsd,
    teamMaxConcurrent = locks.teamMaxConcurrent ?: teamMaxConcurrent,
    telemetry = telemetry.copy(
        enabled = locks.telemetryEnabled ?: telemetry.enabled,
        endpoint = locks.telemetryEndpoint ?: telemetry.endpoint,
        headersConfigured = locks.telemetryHeaders != null || telemetry.headersConfigured,
    ),
)

/**
 * 读 rootfs 里的托管层。用 `--restricted` 的读法（[SettingsInputs.restricted]）：只读托管层，不碰用户 / 项目层——
 * 这里只想知道「管理员锁了什么」。读不出来（rootfs 还没装好）按没锁处理，不让设置页因此报错
 */
class MinManagedSettings(private val fs: () -> WorkspaceFs) {
    private val _locks = MutableStateFlow(ManagedLocks())
    val locks: StateFlow<ManagedLocks> = _locks.asStateFlow()

    suspend fun refresh() {
        val snapshot = try {
            SettingsLoader.load(fs(), GuestPath("/workspace"), GuestPath("/root"), inputs = SettingsInputs(restricted = true))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("MinManagedSettings", "managed settings not readable: ${e.javaClass.simpleName}")
            return
        }
        val managed = snapshot.layers.lastOrNull { it.source == ConfigSource.Managed }
        _locks.value = managedLocksOf(managed?.raw, snapshot.managedPolicyBroken)
    }
}
