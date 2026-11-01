package ch.sonensei.cockpit.search

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Mail
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.ui.graphics.vector.ImageVector
import ch.sonensei.cockpit.R
import ch.sonensei.cockpit.reminders.OpenItemRequest
import ch.sonensei.cockpit.ui.navigation.CockpitDestination
import okhttp3.HttpUrl
import java.util.UUID

/** The type filter chips of the search screen; none selected searches every type. */
enum class SearchFilter(
    @param:StringRes val labelRes: Int,
    val itemTypes: List<String>,
) {
    Tasks(R.string.search_filter_tasks, listOf("TASK")),
    Projects(R.string.search_filter_projects, listOf("PROJECT")),
    Notes(R.string.search_filter_notes, listOf("NOTE")),
    Events(R.string.search_filter_events, listOf("EVENT")),
    Emails(R.string.search_filter_emails, listOf("EMAIL")),
    Contacts(R.string.search_filter_contacts, listOf("CONTACT")),
    Files(R.string.search_filter_files, listOf("FILE", "FOLDER")),
    Bookmarks(R.string.search_filter_bookmarks, listOf("BOOKMARK")),
    Journal(R.string.search_filter_journal, listOf("JOURNAL_ENTRY")),
}

/** How an item type is shown in the results: the group heading and the icon of its rows. */
internal data class SearchTypeStyle(
    @param:StringRes val labelRes: Int,
    val icon: ImageVector,
)

internal fun searchTypeStyle(type: String): SearchTypeStyle =
    when (type) {
        "TASK" -> SearchTypeStyle(R.string.search_type_task, Icons.Outlined.TaskAlt)
        "PROJECT" -> SearchTypeStyle(R.string.search_type_project, Icons.Outlined.AccountTree)
        "NOTE" -> SearchTypeStyle(R.string.search_type_note, Icons.Outlined.Description)
        "EVENT" -> SearchTypeStyle(R.string.search_type_event, Icons.Outlined.Event)
        "EMAIL" -> SearchTypeStyle(R.string.search_type_email, Icons.Outlined.Mail)
        "FILE" -> SearchTypeStyle(R.string.search_type_file, Icons.AutoMirrored.Outlined.InsertDriveFile)
        "FOLDER" -> SearchTypeStyle(R.string.search_type_folder, Icons.Outlined.Folder)
        "CONTACT" -> SearchTypeStyle(R.string.search_type_contact, Icons.Outlined.Contacts)
        "BOOKMARK" -> SearchTypeStyle(R.string.search_type_bookmark, Icons.Outlined.Bookmark)
        "JOURNAL_ENTRY" -> SearchTypeStyle(R.string.search_type_journal, Icons.AutoMirrored.Outlined.MenuBook)
        "HABIT" -> SearchTypeStyle(R.string.search_type_habit, Icons.Outlined.Repeat)
        else -> SearchTypeStyle(R.string.search_type_other, Icons.Outlined.Search)
    }

/** Where tapping a hit leads. */
sealed interface SearchTarget {
    /** The item's own Android screen (see [SearchItemTypes]). */
    data class InApp(
        val type: String,
        val id: UUID,
    ) : SearchTarget

    /** The item in the web app, opened in the browser. */
    data class Web(
        val url: String,
    ) : SearchTarget

    /** No detail view anywhere (no deep link, or no server URL): the Android screen listing items of the type. */
    data class Screen(
        val destination: CockpitDestination,
    ) : SearchTarget
}

/** Item types the Android app has a detail view for, shown by [SearchItemScreen]. */
object SearchItemTypes {
    val inApp: Set<String> = setOf("TASK", "NOTE", "EMAIL", "CONTACT")
}

/**
 * Where [hit] opens: its Android detail view where the app has one, else its web deep link below [server] (the web app
 * is served by the same server), else the Android screen listing its type.
 */
fun searchTarget(
    hit: SearchHit,
    server: HttpUrl?,
): SearchTarget {
    if (hit.type in SearchItemTypes.inApp) return SearchTarget.InApp(hit.type, hit.id)
    val web = hit.deepLink?.let { link -> server?.resolve(link.removePrefix("/")) }
    return if (web != null) {
        SearchTarget.Web(web.toString())
    } else {
        SearchTarget.Screen(OpenItemRequest(hit.type, hit.id.toString()).destination)
    }
}
