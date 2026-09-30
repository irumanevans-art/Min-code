package dev.min.agent.protocol

import kotlinx.serialization.json.Json

/**
 * 日志与协议共用的 JSON 配置。只有这一份，谁都不要自己 new 一个 Json。
 *
 * - `classDiscriminator = "type"`：sealed 变体靠 `type` 字段区分，和各家线协议的习惯一致，日志肉眼可读。
 * - `ignoreUnknownKeys = true`：同一主版本内，旧代码读到新代码加的字段直接忽略（字段只加不改，见 agent/store/FORMAT.md）。
 *   读到更新的**主版本**由存储层按文件头拒绝，不靠这里。
 * - `encodeDefaults = false`：默认值不落盘，日志小一点；因此默认值一旦发布就不能改（改了旧日志的含义就变了）。
 * - `explicitNulls = false`：null 字段不写出来，理由同上。
 */
val AgentJson: Json = Json {
    classDiscriminator = "type"
    ignoreUnknownKeys = true
    encodeDefaults = false
    explicitNulls = false
}
