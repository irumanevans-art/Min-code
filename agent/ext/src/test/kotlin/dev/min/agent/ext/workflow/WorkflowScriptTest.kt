package dev.min.agent.ext.workflow

import org.junit.Assert.*
import org.junit.Test

class WorkflowScriptTest {
    @Test fun metadataIsParsedWithoutExecutingExpressions() {
        val script = WorkflowScript.parse("/* comment */ export const meta = {name:'review', description:'x', phases:['Review',],}; await phase('Review');")
        assertEquals("review", script.name); assertEquals(listOf("Review"), script.phases)
        assertTrue(script.body.contains("await phase"))
        for (source in listOf("log('x'); export const meta = {}", "export const meta = makeMeta();", "export const meta = {name:'x', description: agent('oops')};", "export const meta = {get name() {return 'x'}};", "export const meta = {...other};")) {
            try { WorkflowScript.parse(source); fail("Must reject metadata expressions") } catch (_: IllegalArgumentException) { }
            catch (_: IllegalStateException) { }
        }
    }
}
