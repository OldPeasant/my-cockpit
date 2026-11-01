package ch.sonensei.cockpit.dashboard

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import org.junit.Assert.assertEquals
import org.junit.Test

/** The web's layout setting read on the phone (same rules as `parseLayout` in the web dashboard). */
class DashboardLayoutTest {
    private fun parse(json: String) = parseLayout(Json.parseToJsonElement(json))

    @Test
    fun `no layout is every widget in the SPEC order`() {
        val order =
            listOf(
                WidgetId.TODAY,
                WidgetId.INBOX,
                WidgetId.UPCOMING,
                WidgetId.WAITING_OVERDUE,
                WidgetId.STALLED_PROJECTS,
                WidgetId.WEEKLY_REVIEW,
                WidgetId.READ_LATER,
                WidgetId.PINNED_NOTES,
            )
        assertEquals(order, parseLayout(null))
        assertEquals(order, parseLayout(JsonNull))
        assertEquals(order, parse("[]"))
    }

    @Test
    fun `listed widgets keep their order, hidden ones are left out, missing ones follow shown`() {
        assertEquals(
            listOf(
                WidgetId.PINNED_NOTES,
                WidgetId.INBOX,
                WidgetId.UPCOMING,
                WidgetId.WAITING_OVERDUE,
                WidgetId.STALLED_PROJECTS,
                WidgetId.READ_LATER,
            ),
            parse("""["pinnedNotes","today:hidden","inbox","weeklyReview:hidden"]"""),
        )
    }

    @Test
    fun `unknown, repeated and non-string entries are skipped`() {
        assertEquals(
            listOf(WidgetId.INBOX) + WidgetId.entries.filter { it != WidgetId.INBOX && it != WidgetId.TODAY },
            parse("""["calendarHeatmap","inbox",42,null,{"id":"x"},"inbox:hidden","today:hidden","today"]"""),
        )
    }

    @Test
    fun `a value that is no list is the default`() {
        assertEquals(WidgetId.entries, parse(""""today""""))
        assertEquals(WidgetId.entries, parse("""{"today":true}"""))
    }
}
