package ch.sonensei.cockpit.review

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the user has in progress in the review wizard, kept for the app's lifetime rather than the screen's: a bottom-tab
 * switch drops the review screen and its view model, and coming back must still show the open wizard and the typed
 * reflection. Kept per review id, so a draft never leaks into another review; lost on app death (the web keeps it in
 * localStorage).
 */
@Singleton
class ReviewDrafts
    @Inject
    constructor() {
        private val reflections = ConcurrentHashMap<UUID, String>()

        /** The review whose wizard is open, `null` when the overview is shown. */
        @Volatile
        var wizardFor: UUID? = null

        fun reflection(review: UUID): String = reflections[review].orEmpty()

        fun setReflection(
            review: UUID,
            text: String,
        ) {
            if (text.isEmpty()) reflections.remove(review) else reflections[review] = text
        }

        /** Forgets everything about [review]: it was completed. */
        fun clear(review: UUID) {
            reflections.remove(review)
            if (wizardFor == review) wizardFor = null
        }
    }
