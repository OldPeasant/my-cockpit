package ch.sonensei.cockpit.gtd.task

import ch.sonensei.cockpit.gtd.data.GtdTask
import ch.sonensei.cockpit.gtd.data.TaskList
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.UUID

/** The edit form's list, scheduled/tickler dates, waiting-for and repeat: the checks, the move and the PATCH. */
class TaskDraftListTest {
    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val today = LocalDate.of(2026, 10, 20)
    private val next = GtdTask(id(1), "Buy seeds", TaskList.NEXT, 0, version = 3, projectId = id(20))
    private val scheduled = next.copy(list = TaskList.SCHEDULED, scheduledDate = LocalDate.of(2026, 11, 2))
    private val waiting = next.copy(list = TaskList.WAITING, waitingForText = "Anna Example", waitingSince = LocalDate.of(2026, 10, 17))
    private val someday = next.copy(list = TaskList.SOMEDAY)

    private fun draft(
        task: GtdTask,
        tickler: LocalDate? = null,
        rule: String? = null,
        mode: RecurrenceMode? = null,
    ) = TaskDraft.of(TaskDetail(task, ticklerDate = tickler, recurrenceRule = rule, recurrenceMode = mode))

    // --- TaskDraft.of(detail) ---

    @Test
    fun `the form starts with the task's list, dates, person and repeat`() {
        val d =
            draft(scheduled, tickler = LocalDate.of(2026, 12, 1), rule = "FREQ=WEEKLY;INTERVAL=2", mode = RecurrenceMode.AFTER_COMPLETION)
        assertEquals(TaskList.SCHEDULED, d.list)
        assertEquals(LocalDate.of(2026, 11, 2), d.scheduledDate)
        assertEquals(LocalDate.of(2026, 12, 1), d.ticklerDate)
        assertEquals(RepeatDraft(RepeatPreset.WEEKLY, "2", RecurrenceMode.AFTER_COMPLETION), d.repeat)
        val w = draft(waiting)
        assertEquals("Anna Example", w.waitingFor)
        assertEquals(LocalDate.of(2026, 10, 17), w.waitingSince)
        assertEquals(RepeatDraft(), w.repeat)
    }

    // --- onList / scheduledOn ---

    @Test
    fun `another list than Scheduled drops the scheduled date, Scheduled keeps it`() {
        val d = draft(scheduled)
        assertEquals(TaskDraft.of(scheduled).copy(list = TaskList.NEXT, scheduledDate = null), d.onList(TaskList.NEXT))
        assertNull(d.onList(TaskList.WAITING).scheduledDate)
        assertEquals(LocalDate.of(2026, 11, 2), d.onList(TaskList.SCHEDULED).scheduledDate)
        val fromNext = draft(next).onList(TaskList.SCHEDULED)
        assertEquals(TaskList.SCHEDULED, fromNext.list)
        assertNull(fromNext.scheduledDate)
    }

    @Test
    fun `picking a scheduled date puts the task on Scheduled`() {
        val d = draft(someday).scheduledOn(LocalDate.of(2026, 11, 5))
        assertEquals(TaskList.SCHEDULED, d.list)
        assertEquals(LocalDate.of(2026, 11, 5), d.scheduledDate)
    }

    @Test
    fun `clearing the scheduled date takes a scheduled task to Next, other lists stay`() {
        val cleared = draft(scheduled).scheduledOn(null)
        assertEquals(TaskList.NEXT, cleared.list)
        assertNull(cleared.scheduledDate)
        assertEquals(TaskList.SOMEDAY, draft(someday).scheduledOn(null).list)
    }

    // --- problems ---

    @Test
    fun `Scheduled without a date is required`() {
        val d = draft(next).onList(TaskList.SCHEDULED)
        assertEquals(mapOf(TaskField.SCHEDULED to FieldProblem.Required), d.problems(TaskDetail(next), today))
        assertEquals(mapOf(TaskField.SCHEDULED to FieldProblem.Required), d.problems(today = today))
    }

    @Test
    fun `a tickler sent with a move must be after today`() {
        val before = TaskDetail(next)
        val onDay = draft(next).copy(ticklerDate = today)
        assertEquals(mapOf(TaskField.TICKLER to FieldProblem.NotFuture), onDay.problems(before, today))
        val past = draft(next).copy(ticklerDate = today.minusDays(1))
        assertEquals(mapOf(TaskField.TICKLER to FieldProblem.NotFuture), past.problems(before, today))
        assertTrue(draft(next).copy(ticklerDate = today.plusDays(1)).problems(before, today).isEmpty())
    }

    @Test
    fun `an old tickler is resent by a list change and must then be in the future`() {
        val old = today.minusDays(2)
        val before = TaskDetail(someday, ticklerDate = old)
        // Nothing moves: the old tickler stays as it is.
        assertTrue(draft(someday, tickler = old).copy(title = "Buy seeds now").problems(before, today).isEmpty())
        // The list changes: the move resends it.
        val moved = draft(someday, tickler = old).onList(TaskList.NEXT)
        assertEquals(mapOf(TaskField.TICKLER to FieldProblem.NotFuture), moved.problems(before, today))
        // Clearing it is fine.
        assertTrue(moved.copy(ticklerDate = null).problems(before, today).isEmpty())
    }

    @Test
    fun `moving to Waiting needs the person, a task already waiting does not`() {
        val toWaiting = draft(next).onList(TaskList.WAITING)
        assertEquals(mapOf(TaskField.WAITING_FOR to FieldProblem.Required), toWaiting.problems(TaskDetail(next), today))
        assertEquals(
            mapOf(TaskField.WAITING_FOR to FieldProblem.Required),
            toWaiting.copy(waitingFor = "   ").problems(TaskDetail(next), today),
        )
        assertTrue(toWaiting.copy(waitingFor = "Anna Example").problems(TaskDetail(next), today).isEmpty())
        assertTrue(draft(waiting).copy(waitingFor = "").problems(TaskDetail(waiting), today).isEmpty())
    }

    @Test
    fun `a repeat problem is shown at the recurrence field together with other problems`() {
        val d = draft(next).copy(title = " ", repeat = RepeatDraft(RepeatPreset.WEEKLY, "0"))
        assertEquals(
            mapOf(TaskField.TITLE to FieldProblem.Required, TaskField.RECURRENCE to FieldProblem.Invalid),
            d.problems(TaskDetail(next), today),
        )
        val custom = draft(next).copy(repeat = RepeatDraft(RepeatPreset.CUSTOM, custom = ""))
        assertEquals(mapOf(TaskField.RECURRENCE to FieldProblem.Required), custom.problems(TaskDetail(next), today))
    }

    // --- move ---

    @Test
    fun `no move while list and tickler are unchanged`() {
        assertNull(draft(next).copy(title = "Other").move(TaskDetail(next)))
        val s = draft(scheduled)
        assertNull(s.copy(scheduledDate = LocalDate.of(2026, 11, 9)).move(TaskDetail(scheduled)))
        assertNull(draft(someday, tickler = today).move(TaskDetail(someday, ticklerDate = today)))
    }

    @Test
    fun `a move to Scheduled carries the date and the tickler`() {
        val d = draft(next, tickler = LocalDate.of(2026, 12, 1)).scheduledOn(LocalDate.of(2026, 11, 5))
        assertEquals(
            TaskMove(TaskList.SCHEDULED, scheduledDate = LocalDate.of(2026, 11, 5), ticklerDate = LocalDate.of(2026, 12, 1)),
            d.move(TaskDetail(next, ticklerDate = LocalDate.of(2026, 12, 1))),
        )
    }

    @Test
    fun `a move to Waiting carries the trimmed person, other lists none`() {
        val d = draft(next).onList(TaskList.WAITING).copy(waitingFor = "  Anna Example ")
        assertEquals(TaskMove(TaskList.WAITING, waitingForText = "Anna Example"), d.move(TaskDetail(next)))
        // Typed for Waiting, then another list chosen: no person.
        assertEquals(TaskMove(TaskList.SOMEDAY), d.onList(TaskList.SOMEDAY).move(TaskDetail(next)))
    }

    @Test
    fun `only a changed tickler moves the task to its own list, resending it`() {
        val d = draft(someday).copy(ticklerDate = LocalDate.of(2026, 12, 24))
        assertEquals(TaskMove(TaskList.SOMEDAY, ticklerDate = LocalDate.of(2026, 12, 24)), d.move(TaskDetail(someday)))
        val cleared = draft(someday, tickler = LocalDate.of(2026, 12, 24)).copy(ticklerDate = null)
        assertEquals(TaskMove(TaskList.SOMEDAY), cleared.move(TaskDetail(someday, ticklerDate = LocalDate.of(2026, 12, 24))))
    }

    @Test
    fun `a move of a scheduled task keeps its date when only the tickler changes`() {
        val d = draft(scheduled).copy(ticklerDate = LocalDate.of(2026, 12, 1))
        assertEquals(
            TaskMove(TaskList.SCHEDULED, scheduledDate = LocalDate.of(2026, 11, 2), ticklerDate = LocalDate.of(2026, 12, 1)),
            d.move(TaskDetail(scheduled)),
        )
    }

    @Test
    fun `every list can be chosen`() {
        TaskList.entries.filter { it != TaskList.NEXT }.forEach { list ->
            val d = draft(next).onList(list).let { if (list == TaskList.SCHEDULED) it.scheduledOn(today.plusDays(1)) else it }
            assertEquals(list, d.move(TaskDetail(next))?.list)
        }
    }

    // --- patch(task, recurrence) ---

    @Test
    fun `a new scheduled date on a task staying on Scheduled is patched`() {
        val d = draft(scheduled).scheduledOn(LocalDate.of(2026, 11, 9))
        assertEquals(JsonObject(mapOf("scheduledDate" to JsonPrimitive("2026-11-09"))), d.patch(scheduled, RepeatDraft()))
    }

    @Test
    fun `list-bound fields are not patched after a move put them on the task`() {
        // After the move the task is on Scheduled with the date: nothing left to patch.
        val toScheduled = draft(next).scheduledOn(LocalDate.of(2026, 11, 5))
        val moved = next.copy(list = TaskList.SCHEDULED, scheduledDate = LocalDate.of(2026, 11, 5))
        assertEquals(JsonObject(emptyMap()), toScheduled.patch(moved, RepeatDraft()))
        // Before the move (the task still on Next) the date is not patched either: the move carries it.
        assertEquals(JsonObject(emptyMap()), toScheduled.patch(next, RepeatDraft()))
        val toWaiting = draft(next).onList(TaskList.WAITING).copy(waitingFor = "Anna Example")
        assertEquals(JsonObject(emptyMap()), toWaiting.patch(next, RepeatDraft()))
        assertEquals(
            JsonObject(emptyMap()),
            toWaiting.patch(next.copy(list = TaskList.WAITING, waitingForText = "Anna Example"), RepeatDraft()),
        )
    }

    @Test
    fun `the person and the day of a waiting task are patched, a cleared person with null`() {
        val d = draft(waiting).copy(waitingFor = " Ben Example ", waitingSince = LocalDate.of(2026, 10, 10))
        assertEquals(
            JsonObject(mapOf("waitingForText" to JsonPrimitive("Ben Example"), "waitingSince" to JsonPrimitive("2026-10-10"))),
            d.patch(waiting, RepeatDraft()),
        )
        assertEquals(JsonObject(mapOf("waitingForText" to JsonNull)), draft(waiting).copy(waitingFor = "  ").patch(waiting, RepeatDraft()))
        // The same person typed with spaces is no change.
        assertEquals(JsonObject(emptyMap()), draft(waiting).copy(waitingFor = " Anna Example").patch(waiting, RepeatDraft()))
    }

    @Test
    fun `a waiting task without a person and the field left empty patches nothing`() {
        val nobody = waiting.copy(waitingForText = "")
        assertEquals(JsonObject(emptyMap()), draft(nobody).patch(nobody, RepeatDraft()))
    }

    @Test
    fun `a new repeat sends the rule and the mode`() {
        val d = draft(next).copy(repeat = RepeatDraft(RepeatPreset.WEEKLY, "2", RecurrenceMode.AFTER_COMPLETION))
        assertEquals(
            JsonObject(
                mapOf(
                    "recurrenceRule" to JsonPrimitive("FREQ=WEEKLY;INTERVAL=2"),
                    "recurrenceMode" to JsonPrimitive("AFTER_COMPLETION"),
                ),
            ),
            d.patch(next, RepeatDraft()),
        )
    }

    @Test
    fun `removing a repeat clears the rule and the mode with null`() {
        val before = RepeatDraft.of("FREQ=DAILY", RecurrenceMode.FIXED)
        val d = draft(next, rule = "FREQ=DAILY", mode = RecurrenceMode.FIXED).copy(repeat = RepeatDraft())
        assertEquals(JsonObject(mapOf("recurrenceRule" to JsonNull, "recurrenceMode" to JsonNull)), d.patch(next, before))
    }

    @Test
    fun `changing only the mode or only the rule sends only that`() {
        val before = RepeatDraft.of("FREQ=DAILY", RecurrenceMode.FIXED)
        val base = draft(next, rule = "FREQ=DAILY", mode = RecurrenceMode.FIXED)
        assertEquals(
            JsonObject(mapOf("recurrenceMode" to JsonPrimitive("AFTER_COMPLETION"))),
            base.copy(repeat = base.repeat.copy(mode = RecurrenceMode.AFTER_COMPLETION)).patch(next, before),
        )
        assertEquals(
            JsonObject(mapOf("recurrenceRule" to JsonPrimitive("FREQ=DAILY;INTERVAL=3"))),
            base.copy(repeat = base.repeat.copy(interval = "3")).patch(next, before),
        )
    }

    @Test
    fun `an unchanged custom rule is no change, also when switched to its preset`() {
        val before = RepeatDraft.of("FREQ=WEEKLY;BYDAY=SA", RecurrenceMode.FIXED)
        val d = draft(next, rule = "FREQ=WEEKLY;BYDAY=SA", mode = RecurrenceMode.FIXED)
        assertEquals(JsonObject(emptyMap()), d.patch(next, before))
        assertEquals(JsonObject(emptyMap()), d.copy(repeat = d.repeat.copy(custom = "freq=weekly; byday=sa")).patch(next, before))
        val daily = RepeatDraft.of("FREQ=DAILY", null)
        val asCustom = draft(next, rule = "FREQ=DAILY").copy(repeat = RepeatDraft(RepeatPreset.CUSTOM, custom = "FREQ=DAILY"))
        assertEquals(JsonObject(emptyMap()), asCustom.patch(next, daily))
    }

    @Test
    fun `the other fields are patched as before alongside the new ones`() {
        val d = draft(scheduled).copy(title = "Buy seeds and soil").scheduledOn(LocalDate.of(2026, 11, 3))
        val patch = d.patch(scheduled, RepeatDraft())
        assertEquals(JsonPrimitive("Buy seeds and soil"), patch["title"])
        assertEquals(JsonPrimitive("2026-11-03"), patch["scheduledDate"])
        assertEquals(2, patch.size)
        assertFalse(patch.containsKey("list"))
        assertFalse(patch.containsKey("ticklerDate"))
    }

    @Test
    fun `the new fields map from their API names`() {
        assertEquals(TaskField.SCHEDULED, TaskField.ofApi("scheduledDate"))
        assertEquals(TaskField.TICKLER, TaskField.ofApi("ticklerDate"))
        assertEquals(TaskField.WAITING_FOR, TaskField.ofApi("waitingForText"))
        assertEquals(TaskField.WAITING_SINCE, TaskField.ofApi("waitingSince"))
        assertEquals(TaskField.RECURRENCE, TaskField.ofApi("recurrenceRule"))
        assertEquals(TaskField.RECURRENCE_MODE, TaskField.ofApi("recurrenceMode"))
        assertEquals(TaskField.LIST, TaskField.ofApi("list"))
    }
}
