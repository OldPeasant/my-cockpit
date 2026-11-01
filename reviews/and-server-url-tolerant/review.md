# Code Review

Date: 2026-11-01

Task: and-server-url-tolerant - Android login: server address tolerates keyboard-inserted spaces

Scope: `git diff 0948ebbf6..61d1d6877 -- android` (implementation commit 63820aa80, test commit 61d1d6877)

Requirements:
- `tasks/and-server-url-tolerant.md` acceptance criteria and recorded assumptions (zero-width characters removed; quoting used for both address errors with the general message when nothing is left; any `letters://` prefix counts as a scheme)
- AGENTS.md project notes (lint, i18n, no secrets in logs)
- Existing conventions in `android/app/src/main/java/ch/sonensei/cockpit/auth` and `core/network`

Git range:
- Branch: main
- HEAD: 61d1d68777515b51ff09b9ba70aa924c4224b2c2
- Base/start: 0948ebbf6a8cae1829f87343d096400149ceefc0
- Commits reviewed: 2

Summary: 0 P0, 0 P1, 0 P2, 2 P3

## Acceptance criteria check

1. Whitespace removal: `ServerUrlValidator.understood` drops every char where Kotlin `Char.isWhitespace()` is true (on the JVM that is `Character.isWhitespace || Character.isSpaceChar`, so NBSP, U+2007, U+202F, U+3000 are included), plus the zero-width set. `validate` parses the result, so `https://my-cockpit. sonensei. ch ` becomes `https://my-cockpit.sonensei.ch/`. Tested in `ServerUrlTest`, `AuthRepositoryTest`, `PasskeyRepositoryTest`. Met.
2. No scheme gets `https://`; `http://` keeps HTTPS_REQUIRED (cleartext hosts unchanged). Tested. Met.
3. `ServerUrlKeyboardOptions` sets `capitalization = None`, `autoCorrectEnabled = false`, `keyboardType = Uri`, `imeAction = Next` and is used by the field. `LoginScreenTest` checks both the options object and the `EditorInfo.inputType` flags of the focused field. Met.
4. `ServerUrlValidation.Invalid.address` goes through `LoginResult.InvalidServerUrl.address` and `LoginUiState.errorAddress` to the error text (`"{address}" is not a valid server address ...` / `"{address}" uses plain http ...`). Empty or null address falls back to the general message, as the assumption says. `errorAddress` is cleared on every edit and on every new submit (password and passkey paths), so a later non-address error never quotes an old address. Met.
5. Both new strings exist in `values/strings_auth.xml` and `values-de/strings_auth.xml` (German uses typographic quotes). A Robolectric test with the `de` qualifier covers them. Met.
6. ViewModel, repository and Compose UI tests cover the criteria. The parent reports `./gradlew ktlintCheck testDebugUnitTest lintDebug` -> BUILD SUCCESSFUL, 2404 tests, 0 failures. I could not re-run Gradle, so I rely on the parent's run. Met.

Other checks with no findings: `ServerUrlRepository` re-validates the stored address (`ServerUrl.kt:90`). Stored values are already normalized `HttpUrl` strings, so the new prefixing and stripping changes nothing for them. No other caller builds `ServerUrlValidation.Invalid` or `LoginResult.InvalidServerUrl`. `LoginUiState.toString` now includes `errorAddress`, which is only the cleaned form of `serverUrl` (already logged there), so it exposes no new data. The test change that drops `cockpit.example.org` from the malformed list matches criterion 2.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | A missing colon after the scheme now gives a "valid" address with host `https`**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/core/network/ServerUrl.kt:61`
    - Category: Correctness
    - Problem: `understood` adds `https://` whenever the compact text does not start with `letters://`. A typo like `https//my-cockpit.sonensei.ch` (colon left out) turns into `https://https//my-cockpit.sonensei.ch`. OkHttp parses that as host `https` with path `//my-cockpit.sonensei.ch`, so `validate` returns `Valid`. The app stores the address, and the login then fails with the general network error. Before this change the input was rejected as MALFORMED. The same happens with `http//...`.
    - Why it matters: the task is about showing the user what the app understood. In this case the user sees "server cannot be reached" instead of the quoted address, which is the opaque failure the task wants to avoid. This is an edge case and does not break any criterion, so it is low priority.
    - Suggested fix: when the scheme was added, report MALFORMED (with the quoted address) if the parsed host is `http` or `https`, or if the compact input starts with `http//`/`https//` (case-insensitive). Add a `ServerUrlTest` case for it.

- [ ] **CR-2 | Invisible characters written as raw literals in test source**
    - File: `android/app/src/test/java/ch/sonensei/cockpit/core/network/ServerUrlTest.kt:84` (also line 119, the `understood(" ​ \t")` case)
    - Category: Quality
    - Problem: the list in `removes the other invisible characters a keyboard may insert` holds raw U+200C, U+200D, U+2060, U+2028, U+2029, U+1680, U+2000, U+200A and U+205F characters. In an editor or diff they look like empty or plain-space literals. The production code and the neighbouring test use `\uXXXX` escapes.
    - Why it matters: a reader cannot tell which code points are tested. An editor or formatter that normalizes whitespace could silently swap them for plain spaces, and the test would still pass while covering less.
    - Suggested fix: write them as `'‌'`, `' '`, and so on, the same as the other tests in the file.

## Review conclusion

No material issues found. The two P3 findings are optional improvements.
