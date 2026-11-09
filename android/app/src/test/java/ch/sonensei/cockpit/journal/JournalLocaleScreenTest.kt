package ch.sonensei.cockpit.journal

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale
import java.util.UUID

/**
 * The day heading and the calendar's day labels follow the app's configured language, not the process default
 * locale (which a per-app language change leaves alone, and which a formatter created once would keep). The app is
 * French here while the process default is US English.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "fr")
class JournalLocaleScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var before: Locale

    private val today = LocalDate.of(2026, 10, 22)
    private val shown = today.minusDays(2)
    private val entry =
        JournalEntry(UUID.fromString("00000000-0000-7000-8000-000000000201"), shown, "Rainy walk", 3, emptyList(), Instant.EPOCH, 1)

    @Before
    fun setUp() {
        before = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After
    fun tearDown() = Locale.setDefault(before)

    @Test
    fun `the heading and the calendar day labels use the app's language`() {
        val state =
            JournalUiState(
                date = shown,
                today = today,
                entry = entry,
                body = "Rainy walk",
                mood = 3,
                loading = false,
                unloaded = false,
                month = YearMonth.of(2026, 10),
                monthDays = mapOf(shown to 3),
            )
        composeRule.setContent { CockpitTheme { JournalContent(state, JournalCallbacks(), imageLoader = null) } }

        composeRule.onNodeWithTag(JournalTags.DATE).assertTextEquals("mardi 20 octobre 2026")
        composeRule.onNodeWithTag(JournalTags.CALENDAR_TOGGLE).performClick()
        composeRule.onNodeWithTag(JournalTags.MONTH_TITLE).assertTextEquals("octobre 2026")
        composeRule
            .onNodeWithTag(JournalTags.day(shown))
            .assert(hasContentDescription("mardi 20 octobre 2026", substring = true))
        composeRule
            .onNodeWithTag(JournalTags.day(today))
            .assert(hasContentDescription("jeudi 22 octobre 2026"))
    }
}
