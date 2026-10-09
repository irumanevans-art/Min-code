package dev.min.agent.workflow

import dev.min.agent.spi.WorkflowHost
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class WorkflowContractsTest {
    private fun source(body: String, phases: String = "[]") = "export const meta={name:'contracts', description:'Contracts', phases:$phases};\n$body"
    private val host = object : WorkflowHost {
        override suspend fun agent(index: Int, prompt: String, options: JsonObject): JsonElement = JsonPrimitive(prompt)
        override suspend fun progress(kind: String, message: String) = Unit
    }
    @Test fun slowHostThenCpuWorkDoesNotConsumeIdleBudget() = runBlocking {
        val slow = object : WorkflowHost by host {
            override suspend fun agent(index: Int, prompt: String, options: JsonObject): JsonElement { delay(35_000); return JsonPrimitive(prompt) }
        }
        val result = QuickJsWorkflowEngine().evaluate(source("await agent('one'); let n=0; for(let i=0;i<2000000;i++) n++; return n;"), null, slow)
        assertEquals(JsonPrimitive(2000000), result)
    }
    @Test fun multiStagePipelinePreservesOriginalItemAndNullSlots() = runBlocking {
        val result = QuickJsWorkflowEngine().evaluate(source("return await pipeline([1,2,3], (p,item,i)=>item===2?null:item*10, (p,item,i)=>[p,item,i]);"), null, host)
        assertEquals(Json.parseToJsonElement("[[10,1,0],null,[30,3,2]]"), result)
    }
    @Test fun parallelFailureKeepsItsNullSlot() = runBlocking {
        assertEquals(Json.parseToJsonElement("[1,null,3]"), QuickJsWorkflowEngine().evaluate(source("return await parallel(()=>1, ()=>{throw Error('bad')}, ()=>3);"), null, host))
    }
    @Test fun phasesAcceptObjectsAndUnlistedTitles() = runBlocking {
        assertEquals(JsonPrimitive("ok"), QuickJsWorkflowEngine().evaluate(source("await phase('Other'); return 'ok';", "[{title:'Known', detail:'Detail'}]"), null, host))
    }
    @Test fun protoArgumentIsAnOwnProperty() = runBlocking {
        assertEquals(JsonPrimitive(true), QuickJsWorkflowEngine().evaluate(source("return Object.prototype.hasOwnProperty.call(args,'__proto__');"), Json.parseToJsonElement("{\"__proto__\":{\"x\":1}}"), host))
    }
    @Test fun oversizedPromptsAndProgressNeverReachHost() = runBlocking {
        var calls=0
        val counted = object : WorkflowHost by host {
            override suspend fun agent(index:Int,prompt:String,options:JsonObject):JsonElement { calls++; return JsonNull }
            override suspend fun progress(kind:String,message:String) { calls++ }
        }
        val result = QuickJsWorkflowEngine().evaluate(source("let rejected=0; for(const f of [()=>log('x'.repeat(30001)),()=>phase('x'.repeat(30001)),()=>agent('x'.repeat(300001))]) {try {await f()} catch(e){rejected++}} return rejected;"), null, counted)
        assertEquals(JsonPrimitive(3), result); assertEquals(0,calls)
    }
    @Test fun logFloodHasBoundedHostQueueAndCanBeCancelled() = runBlocking {
        val messages = java.util.concurrent.atomic.AtomicInteger()
        val bytes = java.util.concurrent.atomic.AtomicInteger()
        val counted = object : WorkflowHost by host {
            override suspend fun progress(kind:String,message:String) { messages.incrementAndGet(); bytes.addAndGet(message.toByteArray().size); delay(3) }
        }
        val job = launch { QuickJsWorkflowEngine().evaluate(source("for(;;){log('x'.repeat(30000))}"), null, counted) }
        delay(1000); withTimeout(5000) { job.cancelAndJoin() }
        assertTrue(messages.get() <= 4097); assertTrue(bytes.get() <= 1048700)
    }
    @Test fun inclusiveItemAndAgentBoundariesAreAccepted() = runBlocking {
        assertEquals(JsonPrimitive(4096),QuickJsWorkflowEngine().evaluate(source("return (await pipeline(Array(4096).fill(1),x=>x)).length;"),null,host))
        assertEquals(JsonPrimitive(4096),QuickJsWorkflowEngine().evaluate(source("return (await parallel(Array(4096).fill(1))).length;"),null,host))
        assertEquals(JsonPrimitive(1000),QuickJsWorkflowEngine().evaluate(source("for(let i=0;i<1000;i++) await agent('x'); return 1000;"),null,host))
    }
    @Test fun supportedOptionsReachTheHostAndUnknownOptionsAreIgnored() = runBlocking {
        var options=JsonObject(emptyMap())
        val recording=object:WorkflowHost by host {
            override suspend fun agent(index:Int,prompt:String,opts:JsonObject):JsonElement { options=opts; return JsonNull }
        }
        assertEquals(JsonNull,QuickJsWorkflowEngine().evaluate(source("return await agent('x',{phase:'Review',effort:'high',isolation:'worktree',agentType:'Explore',futureOption:1});"),null,recording))
        assertEquals(setOf("phase","effort","isolation","agentType"),options.keys)
    }

    @Test fun slowCachedHostResultsAreExcludedFromIdleBudget() = runBlocking {
        val replay=object:WorkflowHost by host {
            override suspend fun agent(index:Int,prompt:String,options:JsonObject):JsonElement { delay(100); return JsonPrimitive("cached") }
        }
        assertEquals(JsonPrimitive(10),QuickJsWorkflowEngine(50).evaluate(source("for(let i=0;i<10;i++) await agent('old'); return 10;"),null,replay))
    }

    @Test fun progressByteBudgetBoundsFiniteFloodBeforeBridge() = runBlocking {
        var bytes=0; var count=0
        val limited=object:WorkflowHost by host {
            override suspend fun progress(kind:String,message:String) { bytes+=message.toByteArray().size; count++ }
        }
        QuickJsWorkflowEngine().evaluate(source("for(let i=0;i<200;i++) await log('x'.repeat(30000)); return 1;"),null,limited)
        assertTrue("bytes=$bytes",bytes<=1048700); assertTrue(count<200)
    }
    @Test fun runtimeUsesDedicatedDispatcher() = runBlocking {
        var thread=""
        val recorded=object:WorkflowHost by host {
            override suspend fun progress(kind:String,message:String) { thread=Thread.currentThread().name }
        }
        QuickJsWorkflowEngine().evaluate(source("await log('thread');"),null,recorded)
        assertTrue(thread,thread.startsWith("workflow-js"))
    }
}
