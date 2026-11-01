# Code Review

Date: 2026-11-01

Task: and-server-url-tolerant - Android login: server address tolerates keyboard-inserted spaces

Scope: `git diff ca888fe28..8d67a1209 -- android` (implementation commit 163a6de12, test commit 1c3aafcbe, fixer commit 8d67a1209). Final code at HEAD; re-review after the fixer run for CR-1 and CR-2 of the previous review.

Requirements:
- `tasks/and-server-url-tolerant.md` acceptance criteria and recorded assumptions (zero-width characters removed; quoting used for both address errors with the general message when nothing is left; any `letters://` prefix counts as a scheme)
- AGENTS.md project notes (lint, i18n, no secrets in logs)
- `reviews/and-server-url-tolerant/fixes.md`
- Existing conventions in `android/app/src/main/java/ch/sonensei/cockpit/auth` and `core/network`

Git range:
- Branch: main
- HEAD: 8d67a120943fe607e57fcc81d84cd140ba3bd672
- Base/start: ca888fe28b3da21ae90f30723231a23efca13471
- Commits reviewed: 3
- Uncommitted changes: none at review start

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## Previous findings

- CR-1 (mistyped scheme `https//host` became a valid address with host `https`): resolved. `ServerUrlValidator.validate` (`ServerUrl.kt:70-78`) now rejects the parsed URL as MALFORMED, quoting the understood address, when the app added the scheme itself (`isSchemeAdded`) and the parsed host is `http` or `https` (case-insensitive, since OkHttp lowercases hosts and the check lowercases again). A scheme the user typed (`https://https`) and hosts that only start with `http` (`httpserver.example.org`) stay valid. Other typo shapes (`https:host`, `http:/x`) already fail OkHttp parsing on the empty or non-numeric port and are MALFORMED either way. New test `a mistyped scheme is malformed, not a server named http or https` covers `https//`, `http//`, `http:/x`, uppercase, the typo with inserted spaces, and both positive cases. `understood` behaves as before (refactored into `compact` and `isSchemeAdded` with identical logic).
- CR-2 (raw invisible characters in test source): resolved. The invisible-character list and the `understood` empty-result case in `ServerUrlTest.kt` now use `\uXXXX` escapes for the same code points (U+200C, U+200D, U+2060, U+2028, U+2029, U+1680, U+2000, U+200A, U+205F; and NBSP, U+200B, space, tab).

## Acceptance criteria check

1. Whitespace removal: `compact` drops every char where Kotlin `Char.isWhitespace()` is true (covers NBSP and other Unicode space separators) plus the zero-width set from the assumption; `validate` parses the result, so `https://my-cockpit. sonensei. ch ` becomes `https://my-cockpit.sonensei.ch/`. Tested in `ServerUrlTest`, `AuthRepositoryTest`, `PasskeyRepositoryTest`. Met.
2. Without a scheme `https://` is added; `http://` keeps HTTPS_REQUIRED (cleartext hosts unchanged). Tested. Met.
3. `ServerUrlKeyboardOptions` (`LoginScreen.kt:53`) sets `capitalization = None`, `autoCorrectEnabled = false`, `keyboardType = Uri`, `imeAction = Next` and is used by the field; `LoginScreenTest` checks the options and the focused field's input type flags. Met.
4. `ServerUrlValidation.Invalid.address` flows through `LoginResult.InvalidServerUrl.address` and `LoginUiState.errorAddress` into the quoted error text for both address errors; an empty address falls back to the general message. `errorAddress` is cleared on every edit and on every submit (password and passkey), so a later unrelated error never quotes an old address. Met.
5. Both new strings exist in `values/strings_auth.xml` and `values-de/strings_auth.xml`; a Robolectric test with the `de` qualifier covers them. Met.
6. ViewModel, repository and Compose UI tests cover the criteria. The parent ran `android/gradlew -p android ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport -q --no-daemon` on HEAD 8d67a1209 -> exit 0, 2405 tests, 0 failures. I could not run Gradle myself and rely on that run. Met.

Other checks with no findings: `ServerUrlRepository.serverUrl` re-validates stored addresses; stored values are normalized `HttpUrl` strings with a scheme, so the new stripping, prefixing and scheme-name check do not change them (a stored `https://https/` would have been typed with an explicit scheme and stays valid). The only callers that build `Invalid` or `InvalidServerUrl` are the validator and the two repositories, all updated. `LoginUiState.toString` includes `errorAddress`, which is only the cleaned form of `serverUrl` (already in the string), so no new data is exposed. The address shown back to the user is what the user typed, so it reveals nothing new on screen. The fixer reverted its unrequested U+0085 change, so no scope creep remains.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Review conclusion

No material issues found. CR-1 and CR-2 from the previous review are resolved.
