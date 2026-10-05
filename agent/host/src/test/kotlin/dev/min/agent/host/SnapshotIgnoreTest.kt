package dev.min.agent.host

import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Test

class SnapshotIgnoreTest {
    @Test fun git_generated_samples_match_including_parent_negation_and_nested_precedence() {
        val json = javaClass.getResource("/snapshot-gitignore/samples.json")!!.readText()
        val samples = Json.parseToJsonElement(json).jsonObject.getValue("samples").jsonArray
        assertEquals(95, samples.size)
        for (sample in samples) {
            val item = sample.jsonObject
            var ignore = SnapshotIgnore().withFile("", item.getValue("exclude").jsonPrimitive.content)
            for (rule in item.getValue("rules").jsonArray) {
                val fields = rule.jsonObject
                ignore = ignore.withFile(fields.getValue("base").jsonPrimitive.content, fields.getValue("text").jsonPrimitive.content)
            }
            val path = item.getValue("path").jsonPrimitive.content
            assertEquals("case ${item["case"]}, path $path, git rule ${item["pattern"]}", item.getValue("ignored").jsonPrimitive.boolean, ignore.ignored(path, item.getValue("directory").jsonPrimitive.boolean))
        }
    }
}
