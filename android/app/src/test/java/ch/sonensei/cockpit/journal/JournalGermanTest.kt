package ch.sonensei.cockpit.journal

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.w3c.dom.Element
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID
import javax.xml.parsers.DocumentBuilderFactory

/** The journal's new strings exist in German, and the screen uses them on a German device. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "de")
class JournalGermanTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val today = LocalDate.of(2026, 10, 22)
    private val entry =
        JournalEntry(UUID.fromString("00000000-0000-7000-8000-000000000003"), today, "Wanderung", 5, emptyList(), Instant.EPOCH, 1)

    /** The `name`s of the strings and plurals in [file] (relative to the app module), with their plural quantities. */
    private fun names(file: String): Set<String> {
        val path = listOf(File(file), File("app", file)).first { it.exists() }
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(path)
        val root = doc.documentElement
        val strings = (0 until root.getElementsByTagName("string").length).map { root.getElementsByTagName("string").item(it) as Element }
        val plurals = (0 until root.getElementsByTagName("plurals").length).map { root.getElementsByTagName("plurals").item(it) as Element }
        return strings.map { it.getAttribute("name") }.toSet() +
            plurals.flatMap { plural ->
                val items = plural.getElementsByTagName("item")
                (0 until items.length).map { "${plural.getAttribute("name")}#${(items.item(it) as Element).getAttribute("quantity")}" }
            }
    }

    @Test
    fun `every English journal string and plural has a German one, and no German one is left over`() {
        val english = names("src/main/res/values/strings_journal.xml")
        val german = names("src/main/res/values-de/strings_journal.xml")

        assertTrue(english.isNotEmpty())
        assertEquals(emptySet<String>(), english - german)
        assertEquals(emptySet<String>(), german - english)
    }

    @Test
    fun `the day bar, mood, calendar and earlier years speak German`() {
        val state =
            JournalUiState(
                date = today.minusDays(1),
                today = today,
                entry = entry.copy(date = today.minusDays(1)),
                body = "Wanderung",
                mood = 5,
                loading = false,
                unloaded = false,
                month = YearMonth.of(2026, 10),
                monthDays = mapOf(today.minusDays(1) to 5),
                onThisDay = listOf(entry.copy(date = today.minusDays(1).minusYears(3))),
                message = JournalMessage.DAY_NOT_SAVED,
            )
        composeRule.setContent { CockpitTheme { JournalContent(state, JournalCallbacks(), imageLoader = null) } }

        composeRule.onNodeWithContentDescription("Vorheriger Tag").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Nächster Tag").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Tag wählen").assertIsDisplayed()
        composeRule.onNodeWithText("Heute").assertIsDisplayed()
        composeRule.onNodeWithText("Gestern").assertIsDisplayed()
        composeRule.onNodeWithText("Dieser Tag ist noch nicht gespeichert", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Großartig").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Tags").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("An diesem Tag").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("2023, vor 3 Jahren").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithContentDescription("Kalender anzeigen").performClick()
        // The calendar opens above the earlier years scrolled to before.
        composeRule.onNodeWithText("Oktober 2026").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithContentDescription("mit Eintrag", substring = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Vorheriger Monat").assertIsDisplayed()
        composeRule.onNodeWithTag(JournalTags.CALENDAR_TOGGLE).performClick()
        composeRule.onNodeWithTag(JournalTags.CALENDAR).assertDoesNotExist()
    }
}
