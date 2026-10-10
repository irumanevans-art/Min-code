package dev.min.agent.cli

import java.io.BufferedInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlinx.serialization.json.*

/** 真正的独立 JVM stdio 程序；测试不用安装语言服务器，模拟器也可复用同一协议夹具。 */
object FakeLspServer {
    @JvmStatic fun main(args: Array<String>) {
        val input = BufferedInputStream(System.`in`)
        val log = args.firstOrNull()?.let(Path::of)
        val docs = mutableMapOf<String, String>()
        fun send(value: JsonObject) {
            val body = value.toString().encodeToByteArray()
            System.out.write("Content-Length: ${body.size}\r\n\r\n".encodeToByteArray())
            System.out.write(body); System.out.flush()
        }
        fun diagnostics(uri: String, version: JsonElement, text: String) {
            val ds = listOf("OLD_ERROR", "NEW_ERROR", "WARNING").mapNotNull { marker ->
                val at = text.indexOf(marker)
                if (at < 0) return@mapNotNull null
                val start = text.take(at)
                val line = start.count { it == '\n' }
                val col = start.substringAfterLast('\n').length
                buildJsonObject {
                    put("range", buildJsonObject {
                        put("start", buildJsonObject { put("line", line); put("character", col) })
                        put("end", buildJsonObject { put("line", line); put("character", col + marker.length) })
                    })
                    put("severity", if (marker == "WARNING") 2 else 1); put("code", marker); put("message", "问题 $marker")
                }
            }
            send(buildJsonObject { put("jsonrpc", "2.0"); put("method", "textDocument/publishDiagnostics")
                put("params", buildJsonObject { put("uri", uri); put("version", version); put("diagnostics", JsonArray(ds)) }) })
        }
        while (true) {
            val header = StringBuilder()
            while (!header.endsWith("\r\n\r\n")) {
                val b = input.read(); if (b < 0) return
                header.append(b.toChar()); require(header.length < 8192)
            }
            val length = header.toString().lineSequence().first { it.startsWith("Content-Length:") }.substringAfter(':').trim().toInt()
            val body = input.readNBytes(length); if (body.size != length) return
            val msg = Json.parseToJsonElement(body.decodeToString()).jsonObject
            val method = msg["method"]!!.jsonPrimitive.content
            log?.let { Files.writeString(it, "$method\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND) }
            val p = msg["params"] as? JsonObject ?: JsonObject(emptyMap())
            val id = msg["id"]
            if (method == "exit") return
            if (id != null) {
                val result: JsonElement = when(method) {
                    "initialize" -> buildJsonObject { put("capabilities", buildJsonObject { put("textDocumentSync", 1) }) }
                    "shutdown" -> JsonNull
                    "textDocument/prepareCallHierarchy" -> JsonArray(listOf(buildJsonObject { put("name", "f"); put("uri", docs.keys.first()) }))
                    else -> buildJsonObject { put("method", method); put("params", p); put("text", "导航成功") }
                }
                send(buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("result", result) })
            }
            // 真实管道背压用例：先完成导航握手，再停止读 stdin，直到宿主回收进程。
            if (method == "textDocument/hover" && "stall-reader" in args) Thread.sleep(Long.MAX_VALUE)
            if (method == "textDocument/didOpen" || method == "textDocument/didChange") {
                val doc = p["textDocument"]!!.jsonObject
                val uri = doc["uri"]!!.jsonPrimitive.content
                val text = if (method.endsWith("didOpen")) doc["text"]!!.jsonPrimitive.content
                    else p["contentChanges"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
                docs[uri] = text; diagnostics(uri, doc["version"]!!, text)
            }
            if (method == "textDocument/didClose") docs.remove(p["textDocument"]!!.jsonObject["uri"]!!.jsonPrimitive.content)
        }
    }
}
