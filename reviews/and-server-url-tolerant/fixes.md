# Fix Report

Date: 2026-11-01

Task: and-server-url-tolerant - Android login: server address tolerates keyboard-inserted spaces

Scope: current task, findings CR-1 and CR-2 from `reviews/and-server-url-tolerant/review.md` (both approved for fixing by the parent worker). Task commits 163a6de12 and 1c3aafcbe. A previous fixer run left uncommitted changes but no report. This run reviewed those changes, kept the parts that fix CR-1 and CR-2, and reverted one change that was not part of either finding.

## Fixed findings

### CR-1 — A missing colon after the scheme gives a "valid" address with host `https`

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/core/network/ServerUrl.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/core/network/ServerUrlTest.kt`

Problem:
`understood` adds `https://` when the compact input has no `letters://` prefix. So `https//my-cockpit.sonensei.ch` became `https://https//my-cockpit.sonensei.ch`. OkHttp parses that as host `https`, and `validate` returned `Valid`. The login then failed with the general network error instead of the quoted address.

Fix:
- New private helpers `compact(raw)` (whitespace and zero-width removal, same logic as before) and `isSchemeAdded(compact)` (non-empty and no scheme prefix). `understood` uses them and behaves as before.
- `validate` now rejects the parsed URL as `MALFORMED` (quoting the understood address) when the app added the scheme itself and the parsed host is `http` or `https` (`SCHEME_NAMES`, case-insensitive). A scheme the user typed (`https://https`) and hosts that only start with `http` (`httpserver.example.org`) stay valid.
- New test `a mistyped scheme is malformed, not a server named http or https` covers `https//...`, `http//...`, `http:/x`, `HTTPS//...`, the same typo with inserted spaces, and the two cases that must stay valid.

Validation:
- `android/gradlew -p android ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.core.network.ServerUrlTest' -q` — Pass (parent's run on the previous fixer's working tree; must be re-run after this run's revert, see below)

### CR-2 — Invisible characters written as raw literals in test source

Files changed:
- `android/app/src/test/java/ch/sonensei/cockpit/core/network/ServerUrlTest.kt`

Problem:
`removes the other invisible characters a keyboard may insert` listed raw U+200C, U+200D, U+2060, U+2028, U+2029, U+1680, U+2000, U+200A and U+205F characters. The `understood(...)` idempotence test had a raw NBSP plus U+200B literal. Neither could be read or reviewed in an editor or diff.

Fix:
- Both spots now use `\uXXXX` escapes for the same code points as before. A byte dump of the old line confirmed the `understood` literal was NBSP, U+200B, space, tab, and it is now `" ​ \t"`. The character list is split over two `listOf(...)` calls joined with `+` to stay within the line length.
- `grep -P '[^\x00-\x7F]'` finds no non-ASCII characters left in `ServerUrlTest.kt` or `ServerUrl.kt`.

Validation:
- `grep -cP '[^\x00-\x7F]'` on both files — 0 matches
- Same Gradle run as CR-1 — Pass (must be re-run, see below)

## Reverted change from the previous fixer run

The previous run also added U+0085 (NEXT LINE) to `INVISIBLE` in `ServerUrl.kt`, plus a matching test entry. Neither CR-1 nor CR-2 asked for this, so this run reverted it in both files. The `INVISIBLE` set and its KDoc are back to the committed version, and the test list no longer includes `'\u0085'`. If the parent wants U+0085 stripped (criterion 1 says "all whitespace" and U+0085 has the Unicode White_Space property, but Kotlin `Char.isWhitespace` does not treat it as whitespace), that is a separate decision and should be recorded as an assumption.

## Not fixed

None.

## Validation summary

Focused tests:
- `android/gradlew -p android ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.core.network.ServerUrlTest' -q` — Pass on the previous fixer's tree (run by the parent). This run then removed the U+0085 lines from `ServerUrl.kt` and `ServerUrlTest.kt`, so the parent must run it again on the final tree. The fixer cannot run Gradle.

Full test suite:
- Not run by the fixer (no Gradle access). The parent should run `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug`.

Lint/type-check/build:
- ktlint is part of the Gradle command above. Not run by the fixer.

`.taskcheck`:
- Not run (the parent runs it).

Remaining unresolved findings:
0

## Final status

All validated findings fixed. The parent must still run validation on the final working tree after this run's revert.
