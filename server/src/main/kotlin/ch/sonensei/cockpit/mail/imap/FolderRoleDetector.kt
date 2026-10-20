package ch.sonensei.cockpit.mail.imap

import ch.sonensei.cockpit.mail.model.MailFolderRole

/** A folder as the IMAP server lists it: full [path], last segment [name], LIST attributes (e.g. `\Sent`). */
data class RemoteFolder(
	val path: String,
	val name: String,
	val delimiter: Char?,
	val attributes: Set<String>,
) {
	/** `\Noselect` and `\NonExistent` folders only group children and hold no messages. */
	val selectable: Boolean
		get() = attributes.none { it.equals(NOSELECT, ignoreCase = true) || it.equals(NONEXISTENT, ignoreCase = true) }

	/** Nesting level: 0 for top-level folders. */
	val depth: Int
		get() = delimiter?.let { d -> path.count { it == d } } ?: 0

	fun hasAttribute(attribute: String): Boolean = attributes.any { it.equals(attribute, ignoreCase = true) }

	private companion object {
		const val NOSELECT = "\\Noselect"
		const val NONEXISTENT = "\\NonExistent"
	}
}

/**
 * Detects folder roles (SPEC.md §7.1): INBOX by name, the others from SPECIAL-USE attributes (RFC 6154), and for
 * servers without SPECIAL-USE from well-known folder names (English and German). Each role goes to at most one folder
 * and each folder gets at most one role. Roles the owner set by hand win: a folder with an override keeps its role
 * (also "no role"), and detection never gives an overridden role to another folder.
 */
object FolderRoleDetector {
	/** SPECIAL-USE attributes per role, strongest first. `\All` (Gmail's "All Mail") archives when there is no `\Archive`. */
	private val specialUse: Map<MailFolderRole, List<String>> =
		mapOf(
			MailFolderRole.SENT to listOf("\\Sent"),
			MailFolderRole.DRAFTS to listOf("\\Drafts"),
			MailFolderRole.TRASH to listOf("\\Trash"),
			MailFolderRole.SPAM to listOf("\\Junk"),
			MailFolderRole.ARCHIVE to listOf("\\Archive", "\\All"),
		)

	/** Folder names (compared case-insensitively) of servers without SPECIAL-USE. */
	private val wellKnownNames: Map<MailFolderRole, List<String>> =
		mapOf(
			MailFolderRole.SENT to
				listOf("Sent", "Sent Items", "Sent Messages", "Sent Mail", "Gesendet", "Gesendete Elemente", "Gesendete Objekte"),
			MailFolderRole.DRAFTS to listOf("Drafts", "Draft", "Entwürfe"),
			MailFolderRole.TRASH to
				listOf("Trash", "Deleted Items", "Deleted Messages", "Deleted", "Bin", "Papierkorb", "Gelöschte Elemente"),
			MailFolderRole.SPAM to listOf("Junk", "Spam", "Junk E-mail", "Junk Email", "Bulk Mail", "Junk-E-Mail"),
			MailFolderRole.ARCHIVE to listOf("Archive", "Archives", "Archiv"),
		)

	/**
	 * The role of every folder in [folders] (keyed by path; folders without a role are absent). [overrides] are the
	 * owner's choices by path; a `null` value means "this folder has no role". Overrides of paths not in [folders] are
	 * ignored.
	 */
	fun assign(
		folders: List<RemoteFolder>,
		overrides: Map<String, MailFolderRole?> = emptyMap(),
	): Map<String, MailFolderRole> {
		val paths = folders.map { it.path }.toSet()
		val activeOverrides = overrides.filterKeys { it in paths }
		val result = linkedMapOf<String, MailFolderRole>()
		// One role per account even if two overrides name the same role: the first (by path) keeps it.
		activeOverrides.entries.sortedBy { it.key }.forEach { (path, role) ->
			if (role != null && role !in result.values) result[path] = role
		}
		val candidates = folders.filter { it.path !in activeOverrides && it.selectable }.sortedWith(PREFERENCE)

		fun take(
			role: MailFolderRole,
			matches: (RemoteFolder) -> Boolean,
		) {
			if (role in result.values) return
			candidates.firstOrNull { it.path !in result && matches(it) }?.let { result[it.path] = role }
		}

		take(MailFolderRole.INBOX) { it.path.equals(INBOX, ignoreCase = true) }
		specialUse.forEach { (role, attributes) ->
			attributes.forEach { attribute -> take(role) { it.hasAttribute(attribute) } }
		}
		wellKnownNames.forEach { (role, names) ->
			take(role) { folder -> names.any { folder.name.equals(it, ignoreCase = true) } }
		}
		return result
	}

	/** Top-level folders before nested ones (e.g. `Sent` before `Projects/Sent`), then by path for a stable result. */
	private val PREFERENCE = compareBy<RemoteFolder>({ it.depth }, { it.path })

	private const val INBOX = "INBOX"
}
