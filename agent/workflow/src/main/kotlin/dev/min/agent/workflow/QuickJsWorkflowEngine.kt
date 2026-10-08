package dev.min.agent.workflow

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.function
import dev.min.agent.ext.workflow.WorkflowScript
import dev.min.agent.spi.WorkflowEngine
import dev.min.agent.spi.WorkflowHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

class QuickJsWorkflowEngine : WorkflowEngine {
    override suspend fun evaluate(source: String, args: JsonElement?, host: WorkflowHost): JsonElement = withContext(Dispatchers.Default) {
        val script = WorkflowScript.parse(source)
        val js = QuickJs.create(Dispatchers.Default)
        try {
            js.memoryLimit = 32L * 1024 * 1024
            // 只计 JS 执行时间，等待模型不计入；失控循环仍能被取消和超时打断。
            js.evaluationTimeoutMillis = 30_000
            js.asyncFunction("__workflowAgent") { values ->
                host.agent((values[0] as Number).toInt(), values[1] as String,
                    Json.parseToJsonElement(values[2] as String).jsonObject).toString()
            }
            js.asyncFunction("__workflowProgress") { values -> host.progress(values[0] as String, values[1] as String); Unit }
            var result: JsonElement = JsonNull
            js.function("__workflowResult") { values -> result = Json.parseToJsonElement(values[0] as String); Unit }
            js.evaluate<Unit>(SANDBOX)
            val input = args?.toString() ?: "undefined"
            val meta = script.meta.toString()
            // 闭包捕获桥接函数，项目脚本只能看见承诺的六个 API；所有原生参数均再次校验。
            js.evaluate<Any?>("""
                (async () => {
                    const call = __workflowAgent, progress = __workflowProgress, finish = __workflowResult;
                    delete globalThis.__workflowAgent; delete globalThis.__workflowProgress; delete globalThis.__workflowResult;
                    const args = $input, meta = $meta;
                    Object.defineProperty(globalThis, 'args', {value: args, writable: false, configurable: false});
                    let node = 0;
                    const results = [];
                    const pending = [];
                    const agent = (prompt, options = {}) => {
                        if (typeof prompt !== 'string' || !prompt.trim()) throw Error('agent prompt must be a nonempty string');
                        if (!options || typeof options !== 'object' || Array.isArray(options)) throw Error('agent options must be an object');
                        if (Object.keys(options).some(key => !['schema', 'label', 'model'].includes(key))) throw Error('unknown agent option');
                        if (options.schema !== undefined && (!options.schema || typeof options.schema !== 'object' || Array.isArray(options.schema))) throw Error('schema must be an object');
                        for (const key of ['label', 'model']) if (options[key] !== undefined && typeof options[key] !== 'string') throw Error(key + ' must be a string');
                        if (node >= 1000) throw Error('Workflow exceeds 1000 agents');
                        const serialized = JSON.stringify(options);
                        const index = node++;
                        const task = call(index, prompt, serialized).then(text => {
                            const value = JSON.parse(text); results[index] = value; return value;
                        });
                        pending.push(task);
                        return task;
                    };
                    const parallel = (...tasks) => {
                        if (tasks.length === 1 && Array.isArray(tasks[0])) tasks = tasks[0];
                        if (tasks.length > 4096) throw Error('parallel exceeds 4096 items');
                        return Promise.all(tasks.map(t => typeof t === 'function' ? t() : t));
                    };
                    const pipeline = (list, fn) => {
                        if (!Array.isArray(list) || list.length > 4096 || typeof fn !== 'function') throw Error('invalid pipeline');
                        return parallel(list.map((item, i) => () => fn(item, i)));
                    };
                    const phase = title => {
                        if (typeof title !== 'string' || (meta.phases && !meta.phases.includes(title))) throw Error('phase must match meta.phases');
                        return progress('phase', title);
                    };
                    const log = message => progress('log', typeof message === 'string' ? message : JSON.stringify(message));
                    // 独立函数只接收脚本 API，不能通过词法作用域读到桥接函数或内部序号。
                    const AsyncFunction = Object.getPrototypeOf(async function() {}).constructor;
                    const execute = new AsyncFunction('agent', 'pipeline', 'parallel', 'phase', 'log', 'args', 'meta', ${JsonPrimitive(script.body)});
                    const value = await execute(agent, pipeline, parallel, phase, log, args, meta);
                    // 等待已发起的节点落盘，避免脚本省略 await 时先保存空的最终结果。
                    await Promise.all(pending);
                    finish(JSON.stringify(value === undefined ? results : value));
                })()
            """.trimIndent())
            result
        } finally { js.close() }
    }

    companion object {
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
