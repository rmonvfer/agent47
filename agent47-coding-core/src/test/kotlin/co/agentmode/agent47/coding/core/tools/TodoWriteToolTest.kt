package co.agentmode.agent47.coding.core.tools

import co.agentmode.agent47.ai.types.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TodoWriteToolTest {

    private fun todoParams(vararg items: Triple<String, String, String>): kotlinx.serialization.json.JsonObject {
        return buildJsonObject {
            put("todos", buildJsonArray {
                items.forEach { (id, content, status) ->
                    add(buildJsonObject {
                        put("id", id)
                        put("content", content)
                        put("status", status)
                        put("priority", "medium")
                    })
                }
            })
        }
    }

    @Test
    fun `creates todo items and updates state`() = runTest {
        val state = TodoState()
        val tool = TodoWriteTool(state)

        val params = todoParams(
            Triple("1", "Write tests", "pending"),
            Triple("2", "Fix bug", "in_progress"),
        )

        tool.execute("t1", params)

        val items = state.getAll()
        assertEquals(2, items.size)
        assertEquals("Write tests", items[0].content)
        assertEquals("pending", items[0].status)
        assertEquals("Fix bug", items[1].content)
        assertEquals("in_progress", items[1].status)
    }

    @Test
    fun `returns summary with status counts`() = runTest {
        val state = TodoState()
        val tool = TodoWriteTool(state)

        val params = todoParams(
            Triple("1", "Done task", "completed"),
            Triple("2", "Active task", "in_progress"),
            Triple("3", "New task", "pending"),
        )

        val result = tool.execute("t1", params)
        val text = result.content.filterIsInstance<TextContent>().joinToString { it.text }

        assertTrue(text.contains("3 items"))
        assertTrue(text.contains("Completed: 1"))
        assertTrue(text.contains("In Progress: 1"))
        assertTrue(text.contains("Pending: 1"))
    }

    @Test
    fun `replaces entire list on each update`() = runTest {
        val state = TodoState()
        val tool = TodoWriteTool(state)

        tool.execute("t1", todoParams(Triple("1", "First", "pending")))
        assertEquals(1, state.getAll().size)

        tool.execute(
            "t2", todoParams(
                Triple("1", "First", "completed"),
                Triple("2", "Second", "pending"),
            )
        )
        assertEquals(2, state.getAll().size)
        assertEquals("completed", state.getAll()[0].status)
    }

    @Test
    fun `validates status values`() = runTest {
        val state = TodoState()
        val tool = TodoWriteTool(state)

        val params = buildJsonObject {
            put("todos", buildJsonArray {
                add(buildJsonObject {
                    put("id", "1")
                    put("content", "Bad status")
                    put("status", "invalid_status")
                    put("priority", "medium")
                })
            })
        }

        val threw = runCatching { tool.execute("t1", params) }.isFailure
        assertTrue(threw, "Should reject invalid status")
    }

    @Test
    fun `validates priority values`() = runTest {
        val state = TodoState()
        val tool = TodoWriteTool(state)

        val params = buildJsonObject {
            put("todos", buildJsonArray {
                add(buildJsonObject {
                    put("id", "1")
                    put("content", "Bad priority")
                    put("status", "pending")
                    put("priority", "critical")
                })
            })
        }

        val threw = runCatching { tool.execute("t1", params) }.isFailure
        assertTrue(threw, "Should reject invalid priority")
    }

    @Test
    fun `accepts all valid statuses`() = runTest {
        val state = TodoState()
        val tool = TodoWriteTool(state)

        for (status in listOf("pending", "in_progress", "completed", "cancelled")) {
            val params = todoParams(Triple("1", "Task", status))
            tool.execute("t1", params)
            assertEquals(status, state.getAll()[0].status)
        }
    }

    @Test
    fun `accepts all valid priorities`() = runTest {
        val state = TodoState()
        val tool = TodoWriteTool(state)

        for (priority in listOf("high", "medium", "low")) {
            val params = buildJsonObject {
                put("todos", buildJsonArray {
                    add(buildJsonObject {
                        put("id", "1")
                        put("content", "Task")
                        put("status", "pending")
                        put("priority", priority)
                    })
                })
            }
            tool.execute("t1", params)
            assertEquals(priority, state.getAll()[0].priority)
        }
    }

    @Test
    fun `notifies listeners on update`() = runTest {
        val state = TodoState()
        val notifications = mutableListOf<Int>()
        state.addListener { items -> notifications.add(items.size) }

        val tool = TodoWriteTool(state)
        tool.execute("t1", todoParams(Triple("1", "Task", "pending")))

        assertEquals(listOf(1), notifications)
    }
}
