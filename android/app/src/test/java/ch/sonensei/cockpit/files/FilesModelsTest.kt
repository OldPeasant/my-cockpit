package ch.sonensei.cockpit.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The name check the dialog runs before it asks the server: what the server would refuse for its characters. */
class FilesModelsTest {
    @Test
    fun `ordinary names are fine, also with spaces around them`() {
        assertNull(nameProblem("Taxes 2025"))
        assertNull(nameProblem("  notes.txt "))
        assertNull(nameProblem(".hidden"))
        assertNull(nameProblem("..."))
        assertNull(nameProblem("Übersicht – März"))
    }

    @Test
    fun `an empty or blank name is empty`() {
        assertEquals(NameProblem.EMPTY, nameProblem(""))
        assertEquals(NameProblem.EMPTY, nameProblem("   "))
        assertEquals(NameProblem.EMPTY, nameProblem("\t\n"))
    }

    @Test
    fun `at most 255 characters after trimming`() {
        assertNull(nameProblem("a".repeat(MAX_NAME_LENGTH)))
        assertNull(nameProblem("  " + "a".repeat(MAX_NAME_LENGTH) + "  "))
        assertEquals(NameProblem.TOO_LONG, nameProblem("a".repeat(MAX_NAME_LENGTH + 1)))
    }

    @Test
    fun `dot names and path separators are invalid`() {
        assertEquals(NameProblem.INVALID, nameProblem("."))
        assertEquals(NameProblem.INVALID, nameProblem(" .. "))
        assertEquals(NameProblem.INVALID, nameProblem("a/b"))
        assertEquals(NameProblem.INVALID, nameProblem("a\\b"))
        assertEquals(NameProblem.INVALID, nameProblem("/"))
    }

    /** CR-4: the server refuses ISO control characters, so the dialog does too. */
    @Test
    fun `control characters inside a name are invalid`() {
        assertEquals(NameProblem.INVALID, nameProblem("a\tb"))
        assertEquals(NameProblem.INVALID, nameProblem("line\nbreak"))
        assertEquals(NameProblem.INVALID, nameProblem("bell\u0007"))
        assertEquals(NameProblem.INVALID, nameProblem("del\u007F"))
    }
}
