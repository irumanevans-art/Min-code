package dev.min.code.core.claudecode

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用户消息的线格式。
 *
 * 图片必须走 Anthropic 标准的 image content block —— 把路径写进文本里只是让模型再去
 * Read 一次文件，多绕一轮工具调用，而截图这类临时图片根本不该先落盘。
 */
class ClaudeCodeUserMessageTest {

    private fun parse(line: String): JsonObject =
        Json.parseToJsonElement(line).jsonObject

    private fun content(line: String) =
        parse(line)["message"]!!.jsonObject["content"]!!.jsonArray

    @Test
    fun `plain text produces a single text block`() {
        val blocks = content(encodeClaudeCodeUserMessage("hello"))
        assertEquals(1, blocks.size)
        assertEquals("text", blocks[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("hello", blocks[0].jsonObject["text"]!!.jsonPrimitive.content)
    }

    /** 图排在文本前：Anthropic 的提示工程指南明确建议图在前、问题在后 */
    @Test
    fun `images come before the text block`() {
        val blocks = content(
            encodeClaudeCodeUserMessage(
                "这张图里是什么",
                listOf(ClaudeCodeImage("image/jpeg", "AAAA")),
            )
        )
        assertEquals(2, blocks.size)
        assertEquals("image", blocks[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("text", blocks[1].jsonObject["type"]!!.jsonPrimitive.content)

        val source = blocks[0].jsonObject["source"]!!.jsonObject
        assertEquals("base64", source["type"]!!.jsonPrimitive.content)
        assertEquals("image/jpeg", source["media_type"]!!.jsonPrimitive.content)
        assertEquals("AAAA", source["data"]!!.jsonPrimitive.content)
    }

    /** 只发图不打字是合法的 —— "看这张图"那句话往往是多余的，不该硬塞一个空 text 块 */
    @Test
    fun `an image only message has no empty text block`() {
        val blocks = content(
            encodeClaudeCodeUserMessage("", listOf(ClaudeCodeImage("image/png", "BBBB")))
        )
        assertEquals(1, blocks.size)
        assertEquals("image", blocks[0].jsonObject["type"]!!.jsonPrimitive.content)
    }

    /**
     * 信封的三个字段一个都不能少：`parent_tool_use_id` 是 CLI schema 里的可空必填项，
     * `origin.kind=human` 缺失会在严格 isHuman() 信任门处 fail closed。
     */
    @Test
    fun `the envelope keeps the fields the CLI checks`() {
        val root = parse(encodeClaudeCodeUserMessage("hi"))
        assertEquals("user", root["type"]!!.jsonPrimitive.content)
        assertTrue(root.containsKey("parent_tool_use_id"))
        assertEquals("human", root["origin"]!!.jsonObject["kind"]!!.jsonPrimitive.content)
        assertEquals("user", root["message"]!!.jsonObject["role"]!!.jsonPrimitive.content)
    }
}
