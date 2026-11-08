package ch.sonensei.cockpit.mail.compose

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** The strings of recipient suggestions and the Files picker exist in English and German, are not blank and translated. */
class MailComposeRecipientsFilesStringsTest {
    private val res = File("src/main/res")

    private fun strings(file: File): Map<String, String> {
        val document =
            DocumentBuilderFactory
                .newInstance()
                .newDocumentBuilder()
                .parse(file)
        val nodes = document.getElementsByTagName("string")
        return (0 until nodes.length)
            .map { nodes.item(it) as Element }
            .associate { it.getAttribute("name") to it.textContent }
    }

    private val english = strings(File(res, "values/strings_mail_compose.xml"))
    private val german = strings(File(res, "values-de/strings_mail_compose.xml"))

    private val added =
        listOf(
            "mail_compose_attach_device",
            "mail_compose_attach_files",
            "mail_compose_files_title",
            "mail_compose_files_root",
            "mail_compose_files_up",
            "mail_compose_files_cancel",
            "mail_compose_files_empty",
            "mail_compose_files_failed",
            "mail_compose_remove_recipient",
            "mail_compose_suggestion_contact",
            "mail_compose_suggestion_recent",
        )

    @Test
    fun `every new string is in English and German and not blank`() {
        added.forEach { name ->
            assertTrue("$name missing in English", english[name]?.isNotBlank() == true)
            assertTrue("$name missing in German", german[name]?.isNotBlank() == true)
        }
    }

    @Test
    fun `the German strings are translated, not copies of the English ones`() {
        added.forEach { name -> assertNotEquals(name, english[name], german[name]) }
    }

    @Test
    fun `every compose string in English has a German one`() {
        assertTrue((english.keys - german.keys).toString(), (english.keys - german.keys).isEmpty())
    }
}
