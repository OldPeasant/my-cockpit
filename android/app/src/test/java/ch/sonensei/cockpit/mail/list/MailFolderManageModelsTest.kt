package ch.sonensei.cockpit.mail.list

import ch.sonensei.cockpit.core.network.generated.models.MailFolderResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID
import javax.xml.parsers.DocumentBuilderFactory

/** The folder helpers behind the folder actions: reading the server's answer, nesting, which actions a folder allows, the strings. */
class MailFolderManageModelsTest {
    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val work = MailAccount(id(80), "Work", "me@example.org")
    private val home = MailAccount(id(81), "Home", "me@home.example")

    private fun folder(
        n: Int,
        path: String,
        account: UUID = work.id,
        role: FolderRole? = null,
        delimiter: String? = "/",
    ) = MailFolder(
        id(n),
        account,
        path.substringAfterLast(delimiter ?: "\u0000"),
        path,
        role,
        delimiter?.let { path.split(it).size - 1 } ?: 0,
        1,
        delimiter,
    )

    private fun response(
        path: String,
        name: String,
        delimiter: String? = "/",
        role: MailFolderResponse.Role? = null,
        version: Long = 5,
    ) = MailFolderResponse(
        accountId = work.id,
        id = id(93),
        name = name,
        path = path,
        roleOverridden = false,
        selectable = true,
        version = version,
        delimiter = delimiter,
        role = role,
    )

    @Test
    fun `the generated folder answer becomes a folder with its depth, version, delimiter and role`() {
        assertEquals(
            MailFolder(id(93), work.id, "2026", "Projects/Alpha/2026", null, 2, version = 5, delimiter = "/"),
            response("Projects/Alpha/2026", "2026").toFolder(),
        )
        FolderRole.entries.forEach { role ->
            val wire = MailFolderResponse.Role.entries.single { it.value == role.name }
            assertEquals(role, response("X", "X", role = wire).toFolder().role)
        }
    }

    @Test
    fun `a generated answer of a flat namespace has no delimiter and depth 0`() {
        val flat = response("Projects.Alpha", "Projects.Alpha", delimiter = null, version = 0).toFolder()
        assertNull(flat.delimiter)
        assertEquals(0, flat.depth)
        assertEquals(0L, flat.version)
        assertNull(response("X", "X", delimiter = "").toFolder().delimiter)
    }

    @Test
    fun `a folder contains its subfolders at any depth, not siblings that share the start of its name`() {
        val projects = folder(1, "Projects")
        assertTrue(projects.contains(folder(2, "Projects/Alpha")))
        assertTrue(projects.contains(folder(3, "Projects/Alpha/2026")))
        assertFalse(projects.contains(folder(4, "ProjectsOld")))
        assertFalse(projects.contains(folder(5, "ProjectsOld/Alpha")))
        assertFalse(projects.contains(projects))
        assertFalse(folder(6, "Projects/Alpha").contains(projects))
    }

    @Test
    fun `a folder with a dot delimiter contains its subfolders by that delimiter only`() {
        val projects = folder(1, "Projects", delimiter = ".")
        assertTrue(projects.contains(folder(2, "Projects.Alpha", delimiter = ".")))
        assertFalse(projects.contains(folder(3, "Projects/Alpha", delimiter = ".")))
    }

    @Test
    fun `in a flat namespace or another account a folder contains nothing`() {
        val flat = folder(1, "Projects", delimiter = null)
        assertFalse(flat.contains(folder(2, "Projects/Alpha", delimiter = null)))
        assertFalse(folder(3, "Projects").contains(folder(4, "Projects/Alpha", account = home.id)))
    }

    @Test
    fun `only INBOX cannot be renamed, only folders without a role can be deleted`() {
        FolderRole.entries.forEach { role ->
            val special = folder(1, role.name, role = role)
            assertEquals(role != FolderRole.INBOX, special.canRename)
            assertFalse(special.canDelete)
        }
        assertTrue(folder(2, "Projects").canRename)
        assertTrue(folder(2, "Projects").canDelete)
    }

    @Test
    fun `withFolder adds a new folder to its account in list order and leaves other accounts alone`() {
        val inbox = folder(1, "INBOX", role = FolderRole.INBOX)
        val zeta = folder(2, "zeta")
        val homeFolders = MailAccountFolders(home, listOf(folder(3, "Alpha", account = home.id)))
        val lists = listOf(MailAccountFolders(work, listOf(inbox, zeta)), homeFolders)
        val alpha = folder(4, "alpha")

        val after = lists.withFolder(alpha)

        assertEquals(listOf(inbox, alpha, zeta), after[0].folders)
        assertSame(homeFolders, after[1])
    }

    @Test
    fun `withFolder replaces a renamed folder by id and moves it to its new place`() {
        val inbox = folder(1, "INBOX", role = FolderRole.INBOX)
        val alpha = folder(2, "Alpha")
        val beta = folder(3, "Beta")
        val renamed = alpha.copy(name = "Gamma", path = "Gamma", version = 2)

        val after = listOf(MailAccountFolders(work, listOf(inbox, alpha, beta))).withFolder(renamed)

        assertEquals(listOf(inbox, beta, renamed), after.single().folders)
    }

    @Test
    fun `withFolder for an account that is not listed changes nothing`() {
        val lists = listOf(MailAccountFolders(work, listOf(folder(1, "Alpha"))))
        assertEquals(lists, lists.withFolder(folder(2, "Beta", account = home.id)))
    }

    @Test
    fun `withoutFolder drops the folder and its subfolders, keeps siblings and the same path in other accounts`() {
        val projects = folder(1, "Projects")
        val alpha = folder(2, "Projects/Alpha")
        val deep = folder(3, "Projects/Alpha/2026")
        val old = folder(4, "ProjectsOld")
        val homeProjects = folder(5, "Projects", account = home.id)
        val homeAlpha = folder(6, "Projects/Alpha", account = home.id)
        val lists =
            listOf(
                MailAccountFolders(work, listOf(projects, alpha, deep, old)),
                MailAccountFolders(home, listOf(homeProjects, homeAlpha)),
            )

        val after = lists.withoutFolder(projects)

        assertEquals(listOf(old), after[0].folders)
        assertEquals(listOf(homeProjects, homeAlpha), after[1].folders)
    }

    @Test
    fun `withoutFolder in a flat namespace drops only that folder`() {
        val projects = folder(1, "Projects", delimiter = null)
        val other = folder(2, "Projects/Alpha", delimiter = null)
        val after = listOf(MailAccountFolders(work, listOf(projects, other))).withoutFolder(projects)
        assertEquals(listOf(other), after.single().folders)
    }

    private fun strings(file: File): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length).associate { i ->
            val node = nodes.item(i)
            node.attributes.getNamedItem("name").nodeValue to node.textContent
        }
    }

    private fun placeholders(text: String) = Regex("""%\d+\$[sd]""").findAll(text).map { it.value }.toSet()

    @Test
    fun `every folder string exists in every locale of the app, with the same placeholders`() {
        val res = File("src/main/res")
        val english = strings(File(res, "values/strings_mail_folders.xml"))
        assertTrue(english.isNotEmpty())

        // Every locale the app has (a values-xx folder with strings) must have the folder strings too.
        fun hasStrings(dir: File) = dir.listFiles().orEmpty().any { it.name.startsWith("strings") }

        val locales = res.listFiles().orEmpty().filter { it.isDirectory && it.name.startsWith("values-") && hasStrings(it) }
        assertTrue("the app has a German locale", locales.any { it.name == "values-de" })
        for (locale in locales) {
            val translated = strings(File(locale, "strings_mail_folders.xml"))
            assertEquals("keys in ${locale.name}", english.keys, translated.keys)
            for ((key, text) in english) {
                val other = translated.getValue(key)
                assertTrue("$key in ${locale.name} is blank", other.isNotBlank())
                assertEquals("placeholders of $key in ${locale.name}", placeholders(text), placeholders(other))
            }
        }
    }
}
