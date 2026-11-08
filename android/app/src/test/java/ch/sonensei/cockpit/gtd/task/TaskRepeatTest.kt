package ch.sonensei.cockpit.gtd.task

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The repeat editor's model: reading a stored rule into presets, writing the rule back, and its checks. */
class TaskRepeatTest {
    // --- RepeatDraft.of ---

    @Test
    fun `no rule is no repeat with the given or the default mode`() {
        assertEquals(RepeatDraft(), RepeatDraft.of(null, null))
        assertEquals(RepeatDraft(), RepeatDraft.of("  ", null))
        assertEquals(RepeatDraft(), RepeatDraft.of(" ; ;", null))
        assertEquals(RepeatDraft(mode = RecurrenceMode.AFTER_COMPLETION), RepeatDraft.of(null, RecurrenceMode.AFTER_COMPLETION))
    }

    @Test
    fun `each preset rule is read as its preset`() {
        assertEquals(RepeatDraft(RepeatPreset.DAILY), RepeatDraft.of("FREQ=DAILY", null))
        assertEquals(RepeatDraft(RepeatPreset.WEEKLY), RepeatDraft.of("FREQ=WEEKLY", RecurrenceMode.FIXED))
        assertEquals(RepeatDraft(RepeatPreset.MONTHLY), RepeatDraft.of("FREQ=MONTHLY", null))
        assertEquals(RepeatDraft(RepeatPreset.YEARLY), RepeatDraft.of("FREQ=YEARLY", null))
        assertEquals(RepeatDraft(RepeatPreset.WEEKDAYS), RepeatDraft.of("FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR", null))
    }

    @Test
    fun `an interval and the mode are kept, an interval of one is the default`() {
        assertEquals(
            RepeatDraft(RepeatPreset.WEEKLY, "2", RecurrenceMode.AFTER_COMPLETION),
            RepeatDraft.of("FREQ=WEEKLY;INTERVAL=2", RecurrenceMode.AFTER_COMPLETION),
        )
        assertEquals(RepeatDraft(RepeatPreset.DAILY, "1"), RepeatDraft.of("FREQ=DAILY;INTERVAL=1", null))
        assertEquals(RepeatDraft(RepeatPreset.WEEKDAYS, "3"), RepeatDraft.of("INTERVAL=3;BYDAY=MO,TU,WE,TH,FR;FREQ=WEEKLY", null))
    }

    @Test
    fun `a rule typed in lower case with spaces and empty parts is still a preset`() {
        assertEquals(RepeatDraft(RepeatPreset.MONTHLY, "6"), RepeatDraft.of("  freq=monthly ; ;interval=6; ", null))
    }

    @Test
    fun `any other rule is kept as custom text exactly as stored`() {
        val rules =
            listOf(
                // Another BYDAY than the weekdays.
                "FREQ=WEEKLY;BYDAY=SA",
                // BYDAY with a frequency other than weekly.
                "FREQ=MONTHLY;BYDAY=MO,TU,WE,TH,FR",
                // Parts the presets do not have.
                "FREQ=MONTHLY;BYMONTHDAY=15",
                "FREQ=DAILY;COUNT=5",
                // An unknown frequency.
                "FREQ=HOURLY",
                // No frequency at all.
                "INTERVAL=2",
                // A broken or repeated part.
                "FREQ=WEEKLY;INTERVAL",
                "FREQ=WEEKLY;FREQ=DAILY",
                "FREQ=WEEKLY;INTERVAL=0",
                "FREQ=WEEKLY;INTERVAL=x",
            )
        rules.forEach { rule ->
            val draft = RepeatDraft.of(" $rule ", RecurrenceMode.AFTER_COMPLETION)
            assertEquals(rule, RepeatPreset.CUSTOM, draft.preset)
            assertEquals(rule, rule, draft.custom)
            assertEquals(rule, RecurrenceMode.AFTER_COMPLETION, draft.mode)
        }
    }

    // --- rule() ---

    @Test
    fun `presets write their rule, an interval above one is added`() {
        assertNull(RepeatDraft().rule())
        assertEquals("FREQ=DAILY", RepeatDraft(RepeatPreset.DAILY).rule())
        assertEquals("FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR", RepeatDraft(RepeatPreset.WEEKDAYS).rule())
        assertEquals("FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,TU,WE,TH,FR", RepeatDraft(RepeatPreset.WEEKDAYS, "2").rule())
        assertEquals("FREQ=WEEKLY;INTERVAL=3", RepeatDraft(RepeatPreset.WEEKLY, " 3 ").rule())
        assertEquals("FREQ=MONTHLY", RepeatDraft(RepeatPreset.MONTHLY, "1").rule())
        assertEquals("FREQ=YEARLY", RepeatDraft(RepeatPreset.YEARLY, "").rule())
    }

    @Test
    fun `a custom rule is sent normalized, an empty one is no rule`() {
        assertEquals("FREQ=WEEKLY;BYDAY=SA", RepeatDraft(RepeatPreset.CUSTOM, custom = " freq=weekly ; byday=sa ;").rule())
        assertNull(RepeatDraft(RepeatPreset.CUSTOM, custom = " ; ").rule())
    }

    @Test
    fun `no repeat ignores the custom text that was typed before`() {
        assertNull(RepeatDraft(RepeatPreset.NONE, custom = "FREQ=DAILY").rule())
    }

    @Test
    fun `reading a rule and writing it back gives the same rule`() {
        listOf(
            "FREQ=DAILY",
            "FREQ=WEEKLY;INTERVAL=2",
            "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR",
            "FREQ=MONTHLY;INTERVAL=3",
            "FREQ=YEARLY",
            "FREQ=MONTHLY;BYMONTHDAY=1,15",
            "FREQ=WEEKLY;BYDAY=SA",
        ).forEach { rule -> assertEquals(rule, RepeatDraft.of(rule, RecurrenceMode.FIXED).rule()) }
    }

    @Test
    fun `an explicit interval of one reads back without it`() {
        assertEquals("FREQ=DAILY", RepeatDraft.of("FREQ=DAILY;INTERVAL=1", null).rule())
    }

    // --- modeOrNull / sameAs ---

    @Test
    fun `the mode is sent only with a rule`() {
        assertNull(RepeatDraft(mode = RecurrenceMode.AFTER_COMPLETION).modeOrNull())
        assertNull(RepeatDraft(RepeatPreset.CUSTOM, mode = RecurrenceMode.AFTER_COMPLETION, custom = "").modeOrNull())
        assertEquals(RecurrenceMode.AFTER_COMPLETION, RepeatDraft(RepeatPreset.DAILY, mode = RecurrenceMode.AFTER_COMPLETION).modeOrNull())
    }

    @Test
    fun `drafts typed differently that send the same rule and mode are the same`() {
        assertTrue(RepeatDraft(RepeatPreset.WEEKLY, "1").sameAs(RepeatDraft(RepeatPreset.WEEKLY, " ")))
        assertTrue(RepeatDraft(RepeatPreset.CUSTOM, custom = "freq=daily").sameAs(RepeatDraft(RepeatPreset.DAILY)))
        // Without a rule the mode does not matter.
        assertTrue(RepeatDraft(mode = RecurrenceMode.AFTER_COMPLETION).sameAs(RepeatDraft()))
        assertFalse(RepeatDraft(RepeatPreset.WEEKLY).sameAs(RepeatDraft(RepeatPreset.WEEKLY, "2")))
        assertFalse(RepeatDraft(RepeatPreset.WEEKLY).sameAs(RepeatDraft(RepeatPreset.WEEKLY, mode = RecurrenceMode.AFTER_COMPLETION)))
        assertFalse(RepeatDraft(RepeatPreset.WEEKLY).sameAs(RepeatDraft()))
    }

    // --- problem() ---

    @Test
    fun `no repeat and valid presets have no problem`() {
        assertNull(RepeatDraft().problem())
        assertNull(RepeatDraft(RepeatPreset.NONE, interval = "abc").problem())
        assertNull(RepeatDraft(RepeatPreset.DAILY, "1").problem())
        assertNull(RepeatDraft(RepeatPreset.WEEKLY, " 999 ").problem())
    }

    @Test
    fun `the interval must be a whole number from 1 to 999`() {
        listOf("", " ", "0", "-1", "1000", "2.5", "x").forEach { interval ->
            assertEquals(interval, FieldProblem.Invalid, RepeatDraft(RepeatPreset.MONTHLY, interval).problem())
        }
    }

    @Test
    fun `a custom rule is required and needs a FREQ part`() {
        assertEquals(FieldProblem.Required, RepeatDraft(RepeatPreset.CUSTOM, custom = "").problem())
        assertEquals(FieldProblem.Required, RepeatDraft(RepeatPreset.CUSTOM, custom = " ; ").problem())
        assertEquals(FieldProblem.Invalid, RepeatDraft(RepeatPreset.CUSTOM, custom = "BYDAY=MO").problem())
        assertEquals(FieldProblem.Invalid, RepeatDraft(RepeatPreset.CUSTOM, custom = "XFREQ=DAILY").problem())
        assertNull(RepeatDraft(RepeatPreset.CUSTOM, custom = "byday=mo; freq=weekly").problem())
        // The interval field is not used for a custom rule.
        assertNull(RepeatDraft(RepeatPreset.CUSTOM, interval = "", custom = "FREQ=DAILY").problem())
    }

    @Test
    fun `normalizing trims, upper-cases and drops empty parts`() {
        assertEquals("FREQ=WEEKLY;BYDAY=MO", RepeatDraft.normalizeRule("  freq=weekly;; byday=mo ; "))
        assertEquals("", RepeatDraft.normalizeRule(" ; "))
    }

    @Test
    fun `presets with a frequency take an interval`() {
        assertEquals(
            listOf(RepeatPreset.DAILY, RepeatPreset.WEEKDAYS, RepeatPreset.WEEKLY, RepeatPreset.MONTHLY, RepeatPreset.YEARLY),
            RepeatPreset.entries.filter { it.hasInterval },
        )
    }
}
