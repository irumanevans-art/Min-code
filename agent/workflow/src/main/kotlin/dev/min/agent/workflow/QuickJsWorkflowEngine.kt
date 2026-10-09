package dev.min.agent.workflow

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.function
import dev.min.agent.ext.workflow.WorkflowScript
import dev.min.agent.spi.WorkflowEngine
import dev.min.agent.spi.WorkflowHost
import kotlinx.coroutines.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.*

class QuickJsWorkflowEngine(private val idleTimeoutMillis: Long = 30_000) : WorkflowEngine {
    init { require(idleTimeoutMillis > 0) }
    override suspend fun evaluate(source: String, args: JsonElement?, host: WorkflowHost): JsonElement = withContext(engineDispatcher) {
        coroutineScope {
        val script = WorkflowScript.parse(source)
        val js = QuickJs.create(engineDispatcher)
        val inFlight = AtomicInteger()
        val lastProgress = AtomicLong(System.nanoTime())
        val watchdog = launch(Dispatchers.IO) {
            while (isActive) {
                delay(minOf(100, idleTimeoutMillis))
                if (inFlight.get() == 0 && (System.nanoTime() - lastProgress.get()) / 1_000_000 >= idleTimeoutMillis) {
                    js.interruptEvaluation(); break
                }
            }
        }
        fun progressed() { lastProgress.set(System.nanoTime()) }
        try {
            js.memoryLimit = 32L * 1024 * 1024
            // 默认值未必传到 JNI；显式限制原生栈，递归错误才不会杀掉宿主进程。
            js.maxStackSize = 256L * 1024
            // JNI 的 timeout 按墙钟累计；在途宿主调用（含落盘回放）必须从无进展预算排除。
            js.function("__workflowBegin") { inFlight.incrementAndGet(); progressed(); Unit }
            js.asyncFunction("__workflowAgent") { values ->
                try { host.agent((values[0] as Number).toInt(), values[1] as String,
                    Json.parseToJsonElement(values[2] as String).jsonObject).toString() }
                finally { progressed(); inFlight.decrementAndGet() }
            }
            js.asyncFunction("__workflowProgress") { values ->
                try { host.progress(values[0] as String, values[1] as String); Unit }
                finally { progressed(); inFlight.decrementAndGet() }
            }
            var result: JsonElement = JsonNull
            js.function("__workflowResult") { values -> result = Json.parseToJsonElement(values[0] as String); Unit }
            js.evaluate<Unit>(SANDBOX)
            val input = args?.let { "JSON.parse(${JsonPrimitive(it.toString())})" } ?: "undefined"
            val meta = script.meta.toString()
            // 闭包捕获桥接函数，项目脚本只能看见承诺的六个 API；所有原生参数均再次校验。
            js.evaluate<Any?>("""
                (async () => {
                    const call = __workflowAgent, progress = __workflowProgress, finish = __workflowResult, begin = __workflowBegin;
                    delete globalThis.__workflowAgent; delete globalThis.__workflowProgress; delete globalThis.__workflowResult; delete globalThis.__workflowBegin;
                    const args = $input, meta = $meta;
                    Object.defineProperty(globalThis, 'args', {value: args, writable: false, configurable: false});
                    let node = 0; let agentLimitError;
                    const results = [];
                    const pending = [];
                    const agent = (prompt, options = {}) => {
                        if (typeof prompt !== 'string' || !prompt.trim()) throw Error('agent prompt must be a nonempty string');
                        if (!options || typeof options !== 'object' || Array.isArray(options)) throw Error('agent options must be an object');
                        options = Object.fromEntries(Object.entries(options).filter(([key]) => ['schema','label','model','phase','effort','isolation','agentType'].includes(key)));
                        if (options.schema !== undefined && (!options.schema || typeof options.schema !== 'object' || Array.isArray(options.schema))) throw Error('schema must be an object');
                        for (const key of ['label', 'model', 'phase', 'effort', 'isolation', 'agentType']) if (options[key] !== undefined && typeof options[key] !== 'string') throw Error(key + ' must be a string');
                        if (node >= 1000) { agentLimitError = Error('Workflow exceeds 1000 agents'); throw agentLimitError; }
                        const serialized = JSON.stringify(options);
                        const index = node++;
                        begin();
                        const task = call(index, prompt, serialized).then(text => {
                            const value = JSON.parse(text); results[index] = value; return value;
                        });
                        pending.push(task);
                        return task;
                    };
                    const parallel = (...tasks) => {
                        if (tasks.length === 1 && Array.isArray(tasks[0])) tasks = tasks[0];
                        if (tasks.length > 4096) throw Error('parallel exceeds 4096 items');
                        return Promise.all(tasks.map(t => Promise.resolve().then(() => typeof t === 'function' ? t() : t).catch(() => null)));
                    };
                    const pipeline = (list, ...stages) => {
                        if (!Array.isArray(list) || list.length > 4096 || !stages.length || stages.some(fn => typeof fn !== 'function')) throw Error('invalid pipeline');
                        return parallel(list.map((item, i) => async () => {
                            let value = item;
                            for (const stage of stages) { if (value === null) break; value = await stage(value, item, i); }
                            return value;
                        }));
                    };
                    const phase = title => {
                        if (typeof title !== 'string') throw Error('phase title must be a string');
                        begin(); return progress('phase', title);
                    };
                    const log = message => { begin(); return progress('log', typeof message === 'string' ? message : JSON.stringify(message)); };
                    // 独立函数只接收脚本 API，不能通过词法作用域读到桥接函数或内部序号。
                    const AsyncFunction = Object.getPrototypeOf(async function() {}).constructor;
                    const execute = new AsyncFunction('agent', 'pipeline', 'parallel', 'phase', 'log', 'args', 'meta', ${JsonPrimitive(script.body)});
                    const value = await execute(agent, pipeline, parallel, phase, log, args, meta);
                    // 等待已发起的节点落盘，避免脚本省略 await 时先保存空的最终结果。
                    await Promise.all(pending);
                    if (agentLimitError) throw agentLimitError;
                    finish(JSON.stringify(value === undefined ? results : value));
                })()
            """.trimIndent())
            result
        } finally { withContext(NonCancellable) { watchdog.cancelAndJoin() }; js.close() }
        }
    }

    companion object {
        // 忙脚本不能占用共享 Default 池；固定数量守护线程也限制同时运行的原生解释器。
        private val engineDispatcher = Executors.newFixedThreadPool(4) { task ->
            Thread(task, "workflow-js").apply { isDaemon = true }
        }.asCoroutineDispatcher()
        internal val SANDBOX = """
            (() => {
                const NativeDate = Date;
                const forbidden = () => { throw Error('Non-deterministic API is disabled in workflows'); };
                function SafeDate(...values) {
                    if (!new.target || values.length === 0) return forbidden();
                    return Reflect.construct(NativeDate, values, new.target);
                }
                SafeDate.prototype = NativeDate.prototype;
                Object.defineProperty(SafeDate.prototype, 'constructor', {value: SafeDate, writable: false, configurable: false});
                Object.defineProperties(SafeDate, {
                    now: {value: forbidden}, parse: {value: NativeDate.parse}, UTC: {value: NativeDate.UTC}
                });
                Object.freeze(SafeDate.prototype); Object.freeze(SafeDate);
                Object.defineProperty(globalThis, 'Date', {value: SafeDate, writable: false, configurable: false});
                Object.defineProperty(Math, 'random', {value: forbidden, writable: false, configurable: false});
                Object.freeze(Math);
            })();
        """.trimIndent()
    }
}
