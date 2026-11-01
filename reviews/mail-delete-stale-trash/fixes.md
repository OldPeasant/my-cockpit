# Fix Report

Date: 2026-11-01

Task: mail-delete-stale-trash - Mail actions: a message already gone from Trash counts as deleted, stale rows are cleaned up

Scope: Current task. Findings CR-1 and CR-2 (both P3) from `reviews/mail-delete-stale-trash/review.md`. Base 413d13a7e, task commits 6bb24cd56 and bae0c0c42.

## Fixed findings

### CR-1 — GreenMail tests start background resyncs they never wait for

Files changed:
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/api/MailActionGreenMailTests.kt`

Problem:
`partial failures are reported per id` (FLAG on a message expunged on the server) and `an action on messages all gone from the server fails each with NOT_FOUND` start a background resync of INBOX but did not wait for it. The resync could still run while the next test's `@BeforeEach` deletes the accounts. The all-gone test also never checked that the `selected.isEmpty()` path starts the resync (AC2).

Fix:
Both tests now end with `awaitUncached(...)` on the stale row (`second`, `first`). This waits for the background sync and checks that it removed the stale row. The two UIDVALIDITY GreenMail tests now start a resync too (see CR-2), so they wait for it with a new `awaitUidValidity(path, uidValidity)` helper.

Validation:
- `cd server && ./gradlew integrationTest --tests '*MailActionGreenMailTests*'` — Pass (20 tests, 0 failures)

### CR-2 — A changed UIDVALIDITY fails with NOT_FOUND but starts no resync

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailActionService.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/api/MailActionServiceTests.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/api/MailActionGreenMailTests.kt`

Problem:
When the source folder's UIDVALIDITY on the server differed from the cached one, `remote()` returned `Remote.Gone`, the same result as for a missing folder. Every id failed with NOT_FOUND and no resync followed, so actions in that folder kept failing until the next poll.

Fix:
Added the result `Remote.Invalidated`, which `remote()` now returns for a UIDVALIDITY mismatch. `applyInFolder` still fails every id with NOT_FOUND and keeps the cached rows (AC1 unchanged). It now also calls `resync.request(source.id)`. A folder missing on the server still returns `Remote.Gone` and starts no resync, because `syncFolder` would throw `RemoteFolderNotFoundException` and the folder-list sync handles that case. The class KDoc describes the new behavior.
Tests:
- MailActionServiceTests: the two UIDVALIDITY tests (READ in INBOX, DELETE in Trash) now check `resync.request` exactly once for that folder.
- MailActionServiceTests: the two missing-folder tests (FLAG in INBOX, DELETE in Trash) now check that no resync is requested.
- MailActionServiceTests: the names of these four tests now say what they check.
- GreenMail: `a source folder whose UIDVALIDITY changed fails the ids with NOT_FOUND and a background sync repairs it` waits until the background sync has stored the server's UIDVALIDITY again. It then checks that READ on the same id succeeds, because the sync found the message again by Message-ID.
- GreenMail: the Trash UIDVALIDITY test also waits for that repair.

Validation:
- `cd server && ./gradlew ktlintFormat check` — Pass (MailActionServiceTests: 38 tests, 0 failures)
- `cd server && ./gradlew integrationTest --tests '*MailActionGreenMailTests*'` — Pass (20 tests, 0 failures)

## Not fixed

None.

## Validation summary

Focused tests:
- `cd server && ./gradlew integrationTest --tests '*MailActionGreenMailTests*'` — Pass (20 tests, 0 failures, 0 skipped)

Full test suite:
- `cd server && ./gradlew ktlintFormat check` — Pass. This runs the full server unit suite and ktlint. ktlintFormat reformatted nothing.

Lint/type-check/build:
- ktlint and Kotlin compile, both part of `./gradlew check` — Pass

`.taskcheck`:
- Not run. Only server code changed, and `./gradlew check` is the server part of `scripts/check.sh`. The parent worker runs `.taskcheck` before `done`.

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
