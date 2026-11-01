package ch.sonensei.cockpit.search

import ch.sonensei.cockpit.R
import ch.sonensei.cockpit.ui.navigation.CockpitDestination
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID
import javax.xml.parsers.DocumentBuilderFactory

/** Where a tapped hit opens, how types are shown, the search routes, and the translations. */
class SearchModelsTest {
    private val id: UUID = UUID.fromString("00000000-0000-7000-8000-000000000042")
    private val server = "https://cockpit.example.org/".toHttpUrl()

    private fun hit(
        type: String,
        deepLink: String? = "/${type.lowercase()}s/$id",
    ) = SearchHit(id, type, "Fictitious item", null, deepLink)

    @Test
    fun `notes, emails and contacts open in their Android detail view`() {
        for (type in listOf("NOTE", "EMAIL", "CONTACT")) {
            assertEquals(SearchTarget.InApp(type, id), searchTarget(hit(type), server))
            assertEquals(SearchTarget.InApp(type, id), searchTarget(hit(type, deepLink = null), null))
        }
    }

    @Test
    fun `other items open their web deep link below the server`() {
        assertEquals(
            SearchTarget.Web("https://cockpit.example.org/tasks/$id"),
            searchTarget(hit("TASK", "/tasks/$id"), server),
        )
        assertEquals(
            SearchTarget.Web("https://cockpit.example.org/calendar/events/$id"),
            searchTarget(hit("EVENT", "/calendar/events/$id"), server),
        )
        assertEquals(
            SearchTarget.Web("https://cockpit.example.org/journal/2026-10-01"),
            searchTarget(hit("JOURNAL_ENTRY", "/journal/2026-10-01"), server),
        )
    }

    @Test
    fun `a server below a path keeps its path in the web link`() {
        val prefixed = "https://example.org/cockpit/".toHttpUrl()

        assertEquals(
            SearchTarget.Web("https://example.org/cockpit/files/folders/$id"),
            searchTarget(hit("FOLDER", "/files/folders/$id"), prefixed),
        )
        assertEquals(
            SearchTarget.Web("https://example.org/cockpit/bookmarks/$id"),
            searchTarget(hit("BOOKMARK", "bookmarks/$id"), prefixed),
        )
    }

    @Test
    fun `without a deep link or a server the item's list screen opens`() {
        assertEquals(SearchTarget.Screen(CockpitDestination.Next), searchTarget(hit("TASK", deepLink = null), server))
        assertEquals(SearchTarget.Screen(CockpitDestination.Next), searchTarget(hit("TASK"), null))
        assertEquals(SearchTarget.Screen(CockpitDestination.Projects), searchTarget(hit("PROJECT"), null))
        assertEquals(SearchTarget.Screen(CockpitDestination.Calendar), searchTarget(hit("EVENT"), null))
        assertEquals(SearchTarget.Screen(CockpitDestination.Files), searchTarget(hit("FILE"), null))
        assertEquals(SearchTarget.Screen(CockpitDestination.Files), searchTarget(hit("FOLDER"), null))
        assertEquals(SearchTarget.Screen(CockpitDestination.Bookmarks), searchTarget(hit("BOOKMARK"), null))
        assertEquals(SearchTarget.Screen(CockpitDestination.Journal), searchTarget(hit("JOURNAL_ENTRY"), null))
        assertEquals(SearchTarget.Screen(CockpitDestination.Habits), searchTarget(hit("HABIT"), null))
    }

    @Test
    fun `every server type has its own heading and an unknown one falls back to other`() {
        val types = listOf("TASK", "PROJECT", "NOTE", "EVENT", "EMAIL", "FILE", "FOLDER", "CONTACT", "BOOKMARK", "JOURNAL_ENTRY", "HABIT")
        val labels = types.map { searchTypeStyle(it).labelRes }
        assertEquals(types.size, labels.toSet().size)
        assertFalse(R.string.search_type_other in labels)
        assertEquals(R.string.search_type_other, searchTypeStyle("SOMETHING_NEW").labelRes)
    }

    @Test
    fun `search routes build item paths and recognize both search screens`() {
        assertEquals("search/item/NOTE/$id", SearchRoutes.item("NOTE", id))
        assertTrue(SearchRoutes.isSearch("search"))
        assertTrue(SearchRoutes.isSearch("search/item/{type}/{id}"))
        assertFalse(SearchRoutes.isSearch("today"))
        assertFalse(SearchRoutes.isSearch(null))
    }

    @Test
    fun `the search UI state knows when it is idle and when nothing was found`() {
        val idle = SearchUiState(query = "   ")
        assertTrue(idle.idle)
        assertFalse(idle.nothingFound)

        val found = SearchUiState(query = " plan ", shownFor = SearchKey("plan", emptySet()))
        assertTrue(found.nothingFound)
        assertFalse(found.copy(filters = setOf(SearchFilter.Notes)).nothingFound)
        assertFalse(found.copy(message = SearchMessage.OFFLINE).nothingFound)
    }

    private fun strings(file: File): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length).associate { i ->
            val node = nodes.item(i)
            node.attributes.getNamedItem("name").nodeValue to node.textContent
        }
    }

    @Test
    fun `every search string exists in German and is not blank`() {
        val res = File("src/main/res")
        val english = strings(File(res, "values/strings_search.xml"))
        val german = strings(File(res, "values-de/strings_search.xml"))

        assertTrue(english.isNotEmpty())
        assertEquals(english.keys, german.keys)
        german.forEach { (key, text) -> assertTrue("$key is blank in German", text.isNotBlank()) }
    }
}
