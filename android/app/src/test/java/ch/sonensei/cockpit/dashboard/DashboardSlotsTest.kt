package ch.sonensei.cockpit.dashboard

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** The editable layout: every widget with its visibility, written back in the web's setting format. */
class DashboardSlotsTest {
    private fun parse(json: String) = parseSlots(Json.parseToJsonElement(json))

    private fun shown(id: WidgetId) = WidgetSlot(id, visible = true)

    private fun hidden(id: WidgetId) = WidgetSlot(id, visible = false)

    @Test
    fun `hidden widgets keep their place in the slots, missing ones follow shown`() {
        assertEquals(
            listOf(hidden(WidgetId.PINNED_NOTES), shown(WidgetId.INBOX), hidden(WidgetId.TODAY)) +
                listOf(
                    WidgetId.UPCOMING,
                    WidgetId.WAITING_OVERDUE,
                    WidgetId.STALLED_PROJECTS,
                    WidgetId.WEEKLY_REVIEW,
                    WidgetId.READ_LATER,
                ).map(::shown),
            parse("""["pinnedNotes:hidden","inbox","today:hidden"]"""),
        )
    }

    @Test
    fun `the first entry of a repeated widget wins, also when it is the hidden one`() {
        val slots = parse("""["today:hidden","today","inbox","inbox:hidden"]""")
        assertEquals(hidden(WidgetId.TODAY), slots[0])
        assertEquals(shown(WidgetId.INBOX), slots[1])
        assertEquals(WidgetId.entries.size, slots.size)
    }

    @Test
    fun `an unknown hidden id is skipped`() {
        assertEquals(DEFAULT_LAYOUT, parse("""["heatmap:hidden",":hidden","hidden"]"""))
    }

    @Test
    fun `the default layout is every widget shown in the SPEC order`() {
        assertEquals(WidgetId.entries, DEFAULT_LAYOUT.map { it.id })
        assertEquals(true, DEFAULT_LAYOUT.all { it.visible })
        assertEquals(DEFAULT_LAYOUT, parseSlots(null))
    }

    @Test
    fun `the setting is written as the web writes it, hidden ones with the hidden suffix`() {
        val layout = listOf(shown(WidgetId.INBOX), hidden(WidgetId.TODAY)) + DEFAULT_LAYOUT.drop(2)
        val setting = layout.toSetting()
        assertEquals(JsonPrimitive("inbox"), setting[0])
        assertEquals(JsonPrimitive("today:hidden"), setting[1])
        assertEquals(
            JsonArray(
                listOf("inbox", "today:hidden", "upcoming", "waitingOverdue", "stalledProjects", "weeklyReview", "readLater", "pinnedNotes")
                    .map(::JsonPrimitive),
            ),
            setting,
        )
    }

    @Test
    fun `a layout survives the round trip through the setting`() {
        val layouts =
            listOf(
                DEFAULT_LAYOUT,
                layoutOf(WidgetId.PINNED_NOTES, WidgetId.WEEKLY_REVIEW),
                layoutOf(),
                DEFAULT_LAYOUT.reversed().mapIndexed { i, slot -> slot.copy(visible = i % 2 == 0) },
            )
        layouts.forEach { assertEquals(it, parseSlots(it.toSetting())) }
    }

    @Test
    fun `shown lists the visible widgets in their order`() {
        assertEquals(listOf(WidgetId.READ_LATER, WidgetId.TODAY), layoutOf(WidgetId.READ_LATER, WidgetId.TODAY).shown())
        assertEquals(emptyList<WidgetId>(), layoutOf().shown())
    }

    @Test
    fun `moved puts the widget at the index, hidden widgets counting too`() {
        val layout = layoutOf(WidgetId.INBOX, WidgetId.TODAY)
        // INBOX, TODAY, then the hidden ones; move the hidden PINNED_NOTES (last) to the front.
        val moved = layout.moved(WidgetId.PINNED_NOTES, 0)
        assertEquals(hidden(WidgetId.PINNED_NOTES), moved.first())
        assertEquals(listOf(WidgetId.INBOX, WidgetId.TODAY), moved.drop(1).take(2).map { it.id })
        assertEquals(layout.size, moved.size)

        val down = DEFAULT_LAYOUT.moved(WidgetId.TODAY, 2)
        assertEquals(listOf(WidgetId.INBOX, WidgetId.UPCOMING, WidgetId.TODAY, WidgetId.WAITING_OVERDUE), down.take(4).map { it.id })
    }

    @Test
    fun `moved clamps an index beyond either end`() {
        assertEquals(WidgetId.TODAY, DEFAULT_LAYOUT.moved(WidgetId.TODAY, 99).last().id)
        assertEquals(WidgetId.PINNED_NOTES, DEFAULT_LAYOUT.moved(WidgetId.PINNED_NOTES, -5).first().id)
        assertEquals(DEFAULT_LAYOUT, DEFAULT_LAYOUT.moved(WidgetId.INBOX, 1))
    }

    @Test
    fun `moving a widget the layout does not have changes nothing`() {
        val partial = DEFAULT_LAYOUT.take(2)
        assertSame(partial, partial.moved(WidgetId.PINNED_NOTES, 0))
    }

    @Test
    fun `withVisible shows or hides only that widget and keeps the order`() {
        val hiddenInbox = DEFAULT_LAYOUT.withVisible(WidgetId.INBOX, false)
        assertEquals(DEFAULT_LAYOUT.map { it.id }, hiddenInbox.map { it.id })
        assertEquals(listOf(WidgetId.INBOX), hiddenInbox.filter { !it.visible }.map { it.id })
        assertEquals(DEFAULT_LAYOUT, hiddenInbox.withVisible(WidgetId.INBOX, true))
        assertEquals(DEFAULT_LAYOUT, DEFAULT_LAYOUT.withVisible(WidgetId.INBOX, true))
    }
}
