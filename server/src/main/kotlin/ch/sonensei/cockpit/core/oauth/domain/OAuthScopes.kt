package ch.sonensei.cockpit.core.oauth.domain

/**
 * The combined scopes each provider is asked for (calendar, mail and contacts at once, SPEC.md §7.1), and how tokens
 * for a single [OAuthResource] are requested.
 */
object OAuthScopes {
	private val GOOGLE =
		listOf(
			"openid",
			"email",
			"profile",
			"https://www.googleapis.com/auth/calendar",
			"https://www.googleapis.com/auth/contacts",
			"https://mail.google.com/",
		)

	private val MICROSOFT_GRAPH =
		listOf(
			"https://graph.microsoft.com/Calendars.ReadWrite",
			"https://graph.microsoft.com/Contacts.ReadWrite",
		)

	private val MICROSOFT_MAIL =
		listOf(
			"https://outlook.office.com/IMAP.AccessAsUser.All",
			"https://outlook.office.com/SMTP.Send",
		)

	private val MICROSOFT_OIDC = listOf("openid", "email", "profile", "offline_access")

	/** The scopes of the consent screen. */
	fun authorization(provider: OAuthProvider): String =
		when (provider) {
			OAuthProvider.GOOGLE -> GOOGLE
			OAuthProvider.MICROSOFT -> MICROSOFT_OIDC + MICROSOFT_GRAPH + MICROSOFT_MAIL
		}.joinToString(" ")

	/**
	 * The `scope` parameter of a token request for [resource], or `null` when the provider issues one token for all
	 * granted scopes (Google). Microsoft issues a token for one resource per request, so Graph and Outlook differ.
	 */
	fun tokenRequest(
		provider: OAuthProvider,
		resource: OAuthResource,
	): String? =
		when (provider) {
			OAuthProvider.GOOGLE -> {
				null
			}

			OAuthProvider.MICROSOFT -> {
				when (resource) {
					OAuthResource.API -> MICROSOFT_OIDC + MICROSOFT_GRAPH
					OAuthResource.MAIL -> listOf("offline_access") + MICROSOFT_MAIL
				}.joinToString(" ")
			}
		}

	/** Extra authorization parameters: Google only returns a refresh token for `access_type=offline` with consent. */
	fun extraAuthorizationParameters(provider: OAuthProvider): Map<String, String> =
		when (provider) {
			OAuthProvider.GOOGLE -> mapOf("access_type" to "offline", "prompt" to "consent", "include_granted_scopes" to "true")
			OAuthProvider.MICROSOFT -> mapOf("prompt" to "select_account")
		}
}
