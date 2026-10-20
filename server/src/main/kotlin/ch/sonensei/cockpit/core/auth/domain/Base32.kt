package ch.sonensei.cockpit.core.auth.domain

/**
 * RFC 4648 base32 without padding, the encoding authenticator apps expect for TOTP secrets (otpauth URIs).
 * [decode] ignores case, spaces and trailing `=` padding.
 */
object Base32 {
	private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

	fun encode(bytes: ByteArray): String {
		val out = StringBuilder((bytes.size * 8 + 4) / 5)
		var buffer = 0
		var bits = 0
		for (byte in bytes) {
			buffer = (buffer shl 8) or (byte.toInt() and 0xff)
			bits += 8
			while (bits >= 5) {
				out.append(ALPHABET[(buffer shr (bits - 5)) and 0x1f])
				bits -= 5
			}
		}
		if (bits > 0) out.append(ALPHABET[(buffer shl (5 - bits)) and 0x1f])
		return out.toString()
	}

	fun decode(encoded: String): ByteArray {
		val chars = encoded.uppercase().filterNot { it.isWhitespace() }.trimEnd('=')
		val out = ByteArray(chars.length * 5 / 8)
		var buffer = 0
		var bits = 0
		var index = 0
		for (char in chars) {
			val value = ALPHABET.indexOf(char)
			require(value >= 0) { "Not a base32 character" }
			buffer = (buffer shl 5) or value
			bits += 5
			if (bits >= 8) {
				out[index++] = (buffer shr (bits - 8)).toByte()
				bits -= 8
			}
		}
		return out
	}
}
