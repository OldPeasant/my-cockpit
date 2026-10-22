package ch.sonensei.cockpit.contacts

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * The intents that hand a phone number or an email address to another app. Dialing opens the dialer with the number
 * filled in (no call permission needed, the user still presses call); mail opens a mail app with the recipient set.
 */
object ContactIntents {
    /** `tel:` with the number as written, minus spaces and separators the dialer does not need. */
    fun dial(number: String): Intent = Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", dialable(number), null))

    /** `mailto:` that only mail apps answer (`ACTION_SENDTO`), so no chooser offers to "share" it elsewhere. */
    fun mail(address: String): Intent =
        Intent(Intent.ACTION_SENDTO, Uri.fromParts("mailto", address.trim(), null))
            .putExtra(Intent.EXTRA_EMAIL, arrayOf(address.trim()))

    /** Keeps digits, `+` (only first), `*`, `#` and `,`/`;` (pause/wait), so `+41 (0)44 123-45-67` dials. */
    internal fun dialable(number: String): String {
        val trimmed = number.trim().replace("(0)", "")
        return buildString {
            trimmed.forEach { c ->
                when {
                    c.isDigit() || c in "*#,;" -> append(c)
                    c == '+' && isEmpty() -> append(c)
                }
            }
        }
    }

    /** Starts [intent]; `false` when no app on the phone handles it. */
    fun launch(
        context: Context,
        intent: Intent,
    ): Boolean =
        try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
}
