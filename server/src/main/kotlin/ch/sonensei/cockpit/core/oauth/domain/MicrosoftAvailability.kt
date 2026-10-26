package ch.sonensei.cockpit.core.oauth.domain

import ch.sonensei.cockpit.core.web.FieldError
import ch.sonensei.cockpit.core.web.InvalidRequestException
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * Whether the Microsoft integration is switched on (`cockpit.microsoft.enabled`, env `MICROSOFT_ENABLED`, default
 * off; docs/adr/0003-microsoft-deferred.md). While it is off the API neither offers nor accepts Microsoft OAuth
 * connections, calendar sources or mail accounts, and their sync jobs don't run. The Microsoft code itself stays.
 */
@Component
class MicrosoftAvailability(
	@Value("\${cockpit.microsoft.enabled:false}") val enabled: Boolean,
) {
	/** Whether [provider] may be offered and used: always for Google, for Microsoft only when [enabled]. */
	fun allows(provider: OAuthProvider): Boolean = provider != OAuthProvider.MICROSOFT || enabled

	/**
	 * Refuses [provider] when it is Microsoft and Microsoft is off.
	 *
	 * @throws InvalidRequestException (400, detail [NOT_ENABLED]) with [field] as the field in error
	 */
	fun require(
		provider: OAuthProvider,
		field: String,
	) {
		if (!allows(provider)) throw InvalidRequestException(listOf(FieldError(field, NOT_ENABLED)), NOT_ENABLED)
	}

	companion object {
		const val NOT_ENABLED = "Microsoft is not enabled"
	}
}
