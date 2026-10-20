package ch.sonensei.cockpit.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The platform enforces cleartext rules from `network_security_config.xml`, not from [ServerUrlValidator]; Robolectric
 * does not enforce them. So the two lists are checked against each other: a host the validator accepts for http must also
 * be allowed by the platform, and nothing else may be.
 */
class NetworkSecurityConfigTest {
    // Gradle runs unit tests with the module directory (android/app) as working directory.
    private val config = File("src/main/res/xml/network_security_config.xml")
    private val manifest = File("src/main/AndroidManifest.xml")

    @Test
    fun `cleartext is denied by default and allowed exactly for the validator's cleartext hosts`() {
        val root = parse(config)

        val base = root.elements("base-config").single()
        assertEquals("false", base.getAttribute("cleartextTrafficPermitted"))

        val permitted =
            root
                .elements("domain-config")
                .filter { it.getAttribute("cleartextTrafficPermitted") == "true" }
                .flatMap { it.elements("domain") }
        permitted.forEach { assertEquals(it.textContent, "false", it.getAttribute("includeSubdomains")) }
        assertEquals(ServerUrlValidator.CLEARTEXT_HOSTS, permitted.map { it.textContent.trim() }.toSet())
    }

    @Test
    fun `the manifest uses the network security config and may access the network`() {
        val root = parse(manifest)
        val application = root.elements("application").single()

        assertEquals("@xml/network_security_config", application.getAttribute("android:networkSecurityConfig"))
        assertTrue(root.elements("uses-permission").any { it.getAttribute("android:name") == "android.permission.INTERNET" })
    }

    private fun parse(file: File): Element =
        DocumentBuilderFactory
            .newInstance()
            .newDocumentBuilder()
            .parse(file)
            .documentElement

    private fun Element.elements(tag: String): List<Element> {
        val nodes = getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }
}
