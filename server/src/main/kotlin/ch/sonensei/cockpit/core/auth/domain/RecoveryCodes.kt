package ch.sonensei.cockpit.core.auth.domain

import java.security.SecureRandom

/**
 * Recovery codes for a lost TOTP device (SPEC.md §4.1): [COUNT] codes shown once at setup, formatted
 * `xxxxx-xxxxx` from an alphabet without look-alike characters (about 49 bits each). Only hashes of the
 * [normalize]d form are stored, so input is accepted regardless of case, dashes and spaces.
 */
object RecoveryCodes {
	const val COUNT = 10
	private const val GROUP_LENGTH = 5
	private const val ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"
	private val random = SecureRandom()

	fun generate(): List<String> = List(COUNT) { newCode() }

	fun normalize(code: String): String = code.lowercase().filter { it.isLetterOrDigit() }

	private fun newCode(): String =
		(1..2).joinToString("-") {
			String(CharArray(GROUP_LENGTH) { ALPHABET[random.nextInt(ALPHABET.length)] })
		}
}
