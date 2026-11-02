package ch.sonensei.cockpit.gtd.task

import ch.sonensei.cockpit.gtd.data.Creativity
import ch.sonensei.cockpit.gtd.data.Energy
import ch.sonensei.cockpit.gtd.data.GtdTask
import ch.sonensei.cockpit.gtd.data.TaskList
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale
import java.util.UUID

/** The edit form's checks and PATCH body, the Problem Details reader and the small helpers of the detail screen. */
class TaskDraftTest {
    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val task =
        GtdTask(
            id(1),
            "Buy seeds",
            TaskList.NEXT,
            0,
            version = 3,
            projectId = id(20),
            contextIds = listOf(id(80), id(81)),
            dueDate = LocalDate.of(2026, 10, 24),
            dueTime = "14:30:00",
            timeEstimateMin = 15,
            energy = Energy.HIGH,
            creativity = Creativity.ROUTINE,
            notesMd = "Tomatoes",
        )
    private val bare = GtdTask(id(2), "Call the plumber", TaskList.INBOX, 0, version = 1)

    @After
    fun tearDown() {
        TaskSnapshots.clear()
    }

    // --- TaskDraft.patch ---

    @Test
    fun `an unchanged form sends nothing, also with the contexts in another order`() {
        assertEquals(JsonObject(emptyMap()), TaskDraft.of(task).patch(task))
        assertEquals(JsonObject(emptyMap()), TaskDraft.of(bare).patch(bare))
        assertEquals(JsonObject(emptyMap()), TaskDraft.of(task).copy(contextIds = setOf(id(81), id(80))).patch(task))
    }

    @Test
    fun `spaces around an unchanged title are no change`() {
        assertEquals(JsonObject(emptyMap()), TaskDraft.of(task).copy(title = "  Buy seeds ").patch(task))
    }

    @Test
    fun `blank notes clear them with null, notes on a task without any are sent`() {
        assertEquals(JsonObject(mapOf("notesMd" to JsonNull)), TaskDraft.of(task).copy(notes = "  ").patch(task))
        assertEquals(
            JsonObject(mapOf("notesMd" to JsonPrimitive("Ask about **Tuesday**"))),
            TaskDraft.of(bare).copy(notes = "Ask about **Tuesday**").patch(bare),
        )
    }

    @Test
    fun `a new date keeps the time and sends it along`() {
        val patch = TaskDraft.of(task).copy(dueDate = LocalDate.of(2026, 10, 25)).patch(task)
        assertEquals(
            JsonObject(mapOf("dueDate" to JsonPrimitive("2026-10-25"), "dueTime" to JsonPrimitive("14:30:00"))),
            patch,
        )
    }

    @Test
    fun `clearing only the time sends null for it and keeps the date`() {
        assertEquals(JsonObject(mapOf("dueTime" to JsonNull)), TaskDraft.of(task).copy(dueTime = null).patch(task))
    }

    @Test
    fun `clearing the date of a task without a time sends only the date`() {
        val dateOnly = task.copy(dueTime = null)
        assertEquals(JsonObject(mapOf("dueDate" to JsonNull)), TaskDraft.of(dateOnly).copy(dueDate = null).patch(dateOnly))
    }

    @Test
    fun `a time without a date is never sent`() {
        assertEquals(JsonObject(emptyMap()), TaskDraft.of(bare).copy(dueTime = LocalTime.of(9, 0)).patch(bare))
    }

    @Test
    fun `a first due date and time are both sent`() {
        val patch = TaskDraft.of(bare).copy(dueDate = LocalDate.of(2026, 11, 3), dueTime = LocalTime.of(8, 15)).patch(bare)
        assertEquals(
            JsonObject(mapOf("dueDate" to JsonPrimitive("2026-11-03"), "dueTime" to JsonPrimitive("08:15:00"))),
            patch,
        )
    }

    @Test
    fun `the due time is sent with ASCII digits whatever the device language`() {
        val before = Locale.getDefault()
        try {
            // Persian formats numbers with its own digits by default; the API needs ISO-8601 (HH:mm:ss).
            Locale.setDefault(Locale.forLanguageTag("fa-IR"))
            val patch = TaskDraft.of(task).copy(dueTime = LocalTime.of(9, 5)).patch(task)
            assertEquals(JsonPrimitive("09:05:00"), patch["dueTime"])
        } finally {
            Locale.setDefault(before)
        }
    }

    @Test
    fun `the estimate is sent as a number, trimmed, and an unchanged one is not sent`() {
        assertEquals(JsonObject(mapOf("timeEstimateMin" to JsonPrimitive(20))), TaskDraft.of(task).copy(estimate = " 20 ").patch(task))
        assertEquals(JsonObject(emptyMap()), TaskDraft.of(task).copy(estimate = "015").patch(task))
        assertEquals(JsonObject(mapOf("timeEstimateMin" to JsonPrimitive(0))), TaskDraft.of(bare).copy(estimate = "0").patch(bare))
        assertEquals(JsonObject(mapOf("timeEstimateMin" to JsonNull)), TaskDraft.of(task).copy(estimate = "  ").patch(task))
    }

    @Test
    fun `project, contexts, energy and creativity are sent as ids and names, cleared with null or an empty list`() {
        val patch =
            TaskDraft
                .of(task)
                .copy(projectId = id(21), contextIds = emptySet(), energy = null, creativity = Creativity.CREATIVE)
                .patch(task)
        assertEquals(
            JsonObject(
                mapOf(
                    "projectId" to JsonPrimitive(id(21).toString()),
                    "contextIds" to JsonArray(emptyList()),
                    "energy" to JsonNull,
                    "creativity" to JsonPrimitive("CREATIVE"),
                ),
            ),
            patch,
        )
    }

    @Test
    fun `a stored time with seconds or an unreadable one is read safely`() {
        assertEquals(LocalTime.of(7, 45, 30), TaskDraft.of(task.copy(dueTime = "07:45:30")).dueTime)
        assertNull(TaskDraft.of(task.copy(dueTime = "quarter past")).dueTime)
    }

    // --- TaskDraft.problems ---

    @Test
    fun `the title is required and at most 500 characters after trimming`() {
        val draft = TaskDraft.of(bare)
        assertEquals(mapOf(TaskField.TITLE to FieldProblem.Required), draft.copy(title = "").problems())
        assertEquals(mapOf(TaskField.TITLE to FieldProblem.Required), draft.copy(title = " \t ").problems())
        assertTrue(draft.copy(title = "x".repeat(TaskDraft.MAX_TITLE)).problems().isEmpty())
        assertTrue(draft.copy(title = "  " + "x".repeat(TaskDraft.MAX_TITLE) + "  ").problems().isEmpty())
        assertEquals(
            mapOf(TaskField.TITLE to FieldProblem.TooLong),
            draft.copy(title = "x".repeat(TaskDraft.MAX_TITLE + 1)).problems(),
        )
    }

    @Test
    fun `the estimate must be whole minutes from 0 to 10000 or empty`() {
        val draft = TaskDraft.of(bare)
        listOf("", " ", "0", "10000", " 30 ").forEach { text ->
            assertTrue(text, draft.copy(estimate = text).problems().isEmpty())
        }
        listOf("-1", "10001", "1.5", "ten", "99999999999").forEach { text ->
            assertEquals(text, mapOf(TaskField.ESTIMATE to FieldProblem.NotANumber), draft.copy(estimate = text).problems())
        }
    }

    // --- Problem Details ---

    @Test
    fun `problem details give the message of each field and the detail`() {
        val body =
            """{"type":"about:blank","title":"Bad Request","status":400,"detail":"Invalid task",
               "errors":[{"field":"title","message":"must not be blank"},{"field":"dueTime","message":"needs a due date"}]}"""
        assertEquals(
            TaskResult.Invalid(mapOf("title" to "must not be blank", "dueTime" to "needs a due date"), "Invalid task"),
            problem(body),
        )
    }

    @Test
    fun `problem details without errors, with entries without a field or message, are read as far as possible`() {
        assertEquals(TaskResult.Invalid(emptyMap(), "Invalid task"), problem("""{"status":422,"detail":"Invalid task"}"""))
        assertEquals(
            TaskResult.Invalid(mapOf("energy" to ""), null),
            problem("""{"status":400,"errors":[{"message":"no field"},{"field":"energy"}]}"""),
        )
    }

    @Test
    fun `a body that is not problem details is none`() {
        assertNull(problem(null))
        assertNull(problem(""))
        assertNull(problem("<html>Bad gateway</html>"))
        assertNull(problem("""["not","an","object"]"""))
    }

    @Test
    fun `api field names map to the form's fields`() {
        TaskField.entries.forEach { assertEquals(it, TaskField.ofApi(it.apiName)) }
        assertEquals(TaskField.NOTES, TaskField.ofApi("notesMd"))
        assertNull(TaskField.ofApi("position"))
    }

    // --- helpers ---

    @Test
    fun `a task can be moved to every list but its own`() {
        assertFalse(canMove(task, MoveTarget.NEXT))
        assertTrue(canMove(task, MoveTarget.SOMEDAY))
        assertTrue(canMove(task, MoveTarget.WAITING))
        MoveTarget.entries.forEach { assertTrue(canMove(bare, it)) }
        assertFalse(canMove(bare.copy(list = TaskList.WAITING), MoveTarget.WAITING))
        assertFalse(canMove(bare.copy(list = TaskList.SOMEDAY), MoveTarget.SOMEDAY))
    }

    @Test
    fun `json values for a patch`() {
        assertEquals(JsonNull, jsonOf(null))
        assertEquals(JsonPrimitive("a"), jsonOf("a"))
        assertEquals(JsonPrimitive(5), jsonOf(5))
        assertEquals(JsonPrimitive(true), jsonOf(true))
        assertEquals(JsonArray(listOf(JsonPrimitive("a"), JsonNull)), jsonOf(listOf("a", null)))
        assertEquals(JsonPrimitive("2026-10-24"), jsonOf(LocalDate.of(2026, 10, 24)))
    }

    @Test
    fun `the detail route carries the task id`() {
        assertEquals("task/${id(1)}", TaskRoutes.detail(id(1)))
        assertTrue(TaskRoutes.isDetail(TaskRoutes.DETAIL))
        assertFalse(TaskRoutes.isDetail("task/${id(1)}"))
        assertFalse(TaskRoutes.isDetail(null))
    }

    @Test
    fun `snapshots keep the newest copy of the last 50 tasks and are cleared on logout`() {
        TaskSnapshots.put(task)
        TaskSnapshots.put(task.copy(title = "Buy seeds and soil"))
        assertEquals("Buy seeds and soil", TaskSnapshots.get(task.id)?.title)
        (100 until 149).forEach { TaskSnapshots.put(bare.copy(id = id(it))) }
        // 50 tasks: all kept.
        assertEquals("Buy seeds and soil", TaskSnapshots.get(task.id)?.title)
        TaskSnapshots.put(bare.copy(id = id(149)))
        // The 51st drops the one put longest ago.
        assertNull(TaskSnapshots.get(task.id))
        assertEquals(id(100), TaskSnapshots.get(id(100))?.id)
        // Putting a task again makes it the newest: the next overflow drops the one after it.
        TaskSnapshots.put(bare.copy(id = id(100)))
        TaskSnapshots.put(bare.copy(id = id(150)))
        assertEquals(id(100), TaskSnapshots.get(id(100))?.id)
        assertNull(TaskSnapshots.get(id(101)))
        TaskSnapshots.clear()
        assertNull(TaskSnapshots.get(task.id))
    }
}
