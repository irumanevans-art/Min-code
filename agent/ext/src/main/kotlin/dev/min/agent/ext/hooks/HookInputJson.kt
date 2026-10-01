package dev.min.agent.ext.hooks

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

/**
 * hooks JSON 的编解码配置。stdin 由 Min 构造；stdout 来自用户脚本，必须宽容——未知字段忽略
 * （用户脚本可能多写字段），缺省值见 [HookOutputCommon] 各字段注释。
 */
val HookJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
}

/**
 * 把通用字段与事件专属字段平铺合并成一份 stdin JSON。
 *
 * hooks 的 stdin 是一层平对象，通用字段与事件专属字段同级——所以不能写成 `val common: HookInputCommon`
 * 的嵌套。任务单给了两种做法：每个事件类把通用字段平铺重抄一遍，或者专属字段单独成类 + 这个合并辅助函数。
 * 这里选后者：10 个通用字段只写一处，33 个事件类只描述各自的专属字段。
 */
inline fun <reified T : Any> hookInputJson(common: HookInputCommon, specific: T): JsonObject =
    JsonObject(HookJson.encodeToJsonElement(common).jsonObject + HookJson.encodeToJsonElement(specific).jsonObject)

/** 从完整 stdin JSON 里反解事件专属字段（通用键与未知键被忽略；通用字段另用 [HookInputCommon] 解） */
inline fun <reified T : Any> JsonObject.decodeHookInput(): T = HookJson.decodeFromJsonElement(this)
