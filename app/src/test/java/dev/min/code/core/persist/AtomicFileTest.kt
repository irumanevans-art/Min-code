package dev.min.code.core.persist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AtomicFileTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun writes_a_new_file() {
        val file = File(tmp.root, "state.json")
        file.atomicWriteText("{\"a\":1}")
        assertEquals("{\"a\":1}", file.readText())
    }

    @Test
    fun overwrites_an_existing_file() {
        val file = File(tmp.root, "state.json")
        file.writeText("old")
        file.atomicWriteText("new")
        assertEquals("new", file.readText())
    }

    @Test
    fun leaves_no_tmp_file_behind() {
        val file = File(tmp.root, "state.json")
        file.atomicWriteText("content")
        assertTrue(tmp.root.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test
    fun creates_missing_parent_directories() {
        val file = File(File(tmp.root, "nested/deeper"), "state.json")
        file.atomicWriteText("content")
        assertEquals("content", file.readText())
    }
}
