package ch.sonensei.cockpit.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

/** The in-memory holder that keeps the open wizard and the reflection draft across a tab switch. */
class ReviewDraftsTest {
    private val drafts = ReviewDrafts()
    private val first = UUID.fromString("00000000-0000-7000-8000-000000000041")
    private val second = UUID.fromString("00000000-0000-7000-8000-000000000042")

    @Test
    fun `a review without a draft has an empty reflection`() {
        assertEquals("", drafts.reflection(first))
        assertNull(drafts.wizardFor)
    }

    @Test
    fun `drafts are kept per review, and emptying one forgets only that one`() {
        drafts.setReflection(first, "Fictitious first draft")
        drafts.setReflection(second, "Fictitious second draft")
        drafts.setReflection(first, "")
        assertEquals("", drafts.reflection(first))
        assertEquals("Fictitious second draft", drafts.reflection(second))
    }

    @Test
    fun `clearing the completed review forgets its draft and its open wizard`() {
        drafts.setReflection(first, "Fictitious draft")
        drafts.wizardFor = first
        drafts.clear(first)
        assertEquals("", drafts.reflection(first))
        assertNull(drafts.wizardFor)
    }

    @Test
    fun `clearing another review keeps the open wizard and the draft of this one`() {
        drafts.setReflection(first, "Fictitious draft")
        drafts.wizardFor = first
        drafts.clear(second)
        assertEquals(first, drafts.wizardFor)
        assertEquals("Fictitious draft", drafts.reflection(first))
    }
}
