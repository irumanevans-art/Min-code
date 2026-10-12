package dev.min.agent.cli

import com.dokar.quickjs.QuickJs
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.Executors

/** JVM Regex 的反向引用、字符类和 Unicode 规则不同；直接用已有 JS 引擎执行，保留作者的 JS 语义。 */
internal class JsEvalRegex(private val pattern: String, private val flags: String = "") {
    init { evaluate("new RegExp(${JsonPrimitive(pattern)}, ${JsonPrimitive(flags)}); 0") }
    fun containsMatchIn(text: String): Boolean = count(text) > 0
    fun findAll(text: String): Sequence<Int> = (0 until count(text)).asSequence()
    private fun count(text: String): Int = evaluate("""
        (() => {
            const flags = ${JsonPrimitive(flags)};
            const re = new RegExp(${JsonPrimitive(pattern)}, flags.includes('g') ? flags : flags + 'g');
            const text = ${JsonPrimitive(text)};
            let count = 0, match;
            while ((match = re.exec(text)) !== null) {
                count++;
                if (match[0].length === 0) {
                    const i = re.lastIndex, c = text.charCodeAt(i);
                    re.lastIndex += (re.unicode || re.unicodeSets) && c >= 0xD800 && c <= 0xDBFF && text.charCodeAt(i+1) >= 0xDC00 && text.charCodeAt(i+1) <= 0xDFFF ? 2 : 1;
                }
            }
            return count;
        })()
    """)
    private fun evaluate(source: String): Int = runBlocking {
        withContext(dispatcher) {
            val js = QuickJs.create(dispatcher)
            js.memoryLimit = 16L * 1024 * 1024
            js.maxStackSize = 256L * 1024
            val watchdog = launch(Dispatchers.IO) { delay(2000); js.interruptEvaluation() }
            try { js.evaluate<Long>(source).toInt() }
            finally { watchdog.cancelAndJoin(); js.close() }
        }
    }
    companion object {
        private val dispatcher = Executors.newSingleThreadExecutor { Thread(it, "eval-js-regex").apply { isDaemon = true } }.asCoroutineDispatcher()
    }
}
