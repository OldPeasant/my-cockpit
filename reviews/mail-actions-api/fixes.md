# Fix Report

Date: 2026-10-21

Task: mail-actions-api - Mail actions

Scope: current task; findings CR-1 to CR-4 of `reviews/mail-actions-api/review.md`, plus making the WIP test-writer tests of commit 351f9476 compile and pass. Base commit 8a341743; task commits a941997a, 99a528d8, 351f9476.

## Fixed findings

### CR-1 — A cache-update failure aborts the whole bulk request after the IMAP changes are already applied

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailActionService.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/api/MailActionServiceTests.kt`

Problem:
`updateCache` ran in one transaction without catching `OptimisticLockingFailureException` (concurrent action or sync on the same versioned rows) or `DataIntegrityViolationException` (a sync inserted the destination `(folder_id, uid)` meanwhile). Either one became a 409 or 500 for the whole request, although the server had already applied the action.

Fix:
The cache update of each folder group now runs in its own transaction (`applyToRows`) and is retried once on rows read again after a conflict. The rollback leaves the persistence context clean (`spring.jpa.open-in-view=false`), so the retry sees what is stored. On a retry, `relocate` checks the destination UID again and drops or detaches the row if a sync already cached it. If the retry fails too, the cache is left as it is for the next sync. The failure is logged at INFO without details, and the exception at DEBUG. The ids are reported as ok in both cases. The `MAIL_FOLDER` change events are now published after the transaction, so clients also hear about the change when the cache update was given up (`SseEventHub` listens with `fallbackExecution = true`). The behavior is documented in the class KDoc.

Tests added (`MailActionServiceTests`):
- `a version conflict in the cache update is retried on fresh rows and the ids stay ok`
- `a conflict that persists leaves the cache to the next sync and still reports the ids ok`
- `a row a sync cached at the new UID meanwhile makes the retry drop the moved row instead of duplicating it` (DataIntegrityViolationException path)

Validation:
- `cd server && ./gradlew ktlintFormat test --tests '*MailActionServiceTests'` — Pass

### CR-2 — The fallback and edge paths of moves and deletes are not tested

Files changed:
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/api/MailActionServiceTests.kt` (WIP tests from 351f9476 kept, new tests added)
- `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailActionService.kt` (production bug the WIP GreenMail test found, see below)

Problem:
The COPY + `\Deleted` + expunge path, the plain EXPUNGE without UIDPLUS, a missing or unusable COPYUID, a permanent delete of a linked message, a destination row already cached, a UIDVALIDITY mismatch and REJECTED had no tests.

Fix:
The WIP tests from the test-writer already compiled and cover most paths: the unit tests with a stubbed `IMAPStore`/`IMAPFolder` for no MOVE, no UIDPLUS, no COPYUID with a linked and an unlinked row, COPYUID with another UIDVALIDITY or a UID already cached, a partial COPYUID, a plain EXPUNGE in Trash, read-only, NO answers and connection errors. The GreenMail tests cover a linked message deleted for good, a source UIDVALIDITY mismatch, REJECTED and a source folder that is gone. I added unit tests for a permanent delete of a linked message (it is detached, not deleted), a source UIDVALIDITY mismatch (NOT_FOUND without fetching any message), and a target folder missing on the server (REJECTED, cache unchanged).

Production bug the WIP test found: the GreenMail test `a command the server rejects fails the ids with REJECTED and changes nothing` failed with `CONNECTION_FAILED`. A move to a folder that was deleted on the server makes Angus throw `FolderNotFoundException` (the server answers `NO [TRYCREATE]`) without a `CommandFailedException` cause. `remote()` therefore classified it as a connection failure and threw `MailConnectionException`. That also marked a working pooled connection as unhealthy. `remote()` now returns `Remote.Rejected` for a `FolderNotFoundException` during the action. After the fix, the test passes unchanged.

GreenMail's capability set cannot be configured from the test, so the GreenMail tests run only one of the two move paths. The unit tests cover the other one.

Validation:
- `cd server && ./gradlew test --tests '*MailAction*'` — Pass (ServiceTests, WebTests, ControllerTests)
- `cd server && ./gradlew integrationTest --tests '*MailActionGreenMail*'` — Pass (16 tests; it had 1 failure before the fix)

### CR-3 — A COPY fallback that fails after the copy reports a failure and leaves a duplicate on retry

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailActionService.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/api/MailActionServiceTests.kt`

Problem:
Without MOVE, a `setFlags(\Deleted)` or expunge failure after a successful `UID COPY` failed every id of the group and left the cache untouched, so a retry by the user copied the messages again.

Fix:
A `MessagingException` from the cleanup after the copy is now logged (INFO without details, DEBUG with the exception) and not rethrown. The `COPYUID` result is kept, the rows follow it, and the ids are reported as ok. The source copies stay on the server until a client expunges that folder; they are flagged `\Deleted` when that step worked. The behavior is documented in the class KDoc and in the KDoc of `move`. Dropping the error does not hurt the pool: `ImapConnectionPool.giveBack` checks `store.isConnected` before it reuses a connection.

Test added: `without MOVE a failing cleanup after a successful copy keeps the COPYUID and reports ok`.

Validation:
- `cd server && ./gradlew test --tests '*MailActionServiceTests'` — Pass

### CR-4 — The OpenAPI result schema does not match the actual response nullability and required fields

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailActionController.kt`
- `api/openapi.yaml` (generated)
- `web/src/app/api/generated/models/mail-action-response.ts` (generated)
- `web/src/app/api/generated/models/mail-action-result-response.ts` (generated)

Problem:
`errorCode` and `detail` were generated as required, non-null `string`, and `id`, `ok` and `results` were optional.

Fix:
`results`, `id` and `ok` are now `requiredMode = REQUIRED`. `errorCode` and `detail` stay required, because the server always sends them, and are now `types = ["string", "null"]`, as in `SettingsSchemas.kt`. `api/openapi.yaml` was regenerated with `./gradlew updateOpenApi`, which is the documented equivalent of `UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'`; the env-prefixed form was not permitted in this sandbox. The web client was regenerated with `npm run api:gen`. The generated TypeScript now has `results: Array<...>`, `id: string`, `ok: boolean`, and `errorCode`/`detail: (string | null)`.

Validation:
- `cd server && ./gradlew test --tests '*OpenApiContractTest'` (without the flag, part of `check`) — Pass
- `cd web && npm run check` (api:check, lint, tests, build) — Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `cd server && ./gradlew ktlintFormat test --tests '*MailAction*' --tests '*OpenApiContractTest'` — Pass (MailActionServiceTests 18, MailActionWebTests 15, MailActionControllerTests 11)
- `cd server && ./gradlew integrationTest --tests '*MailActionGreenMail*'` — Pass, 16/16. Before the fix, 1 failed: the REJECTED test, caused by the production bug described under CR-2.

Full test suite:
- `cd server && ./gradlew check` — 2691 tests, 1 failure in `ClockConfigurationTests > the application clock is the system clock in UTC` (unrelated to the mail module: `Instant` precision, microseconds compared with nanoseconds). Re-run `./gradlew test --tests '*ClockConfigurationTests'` — Pass. The full `.taskcheck` run below passed.

Lint/type-check/build:
- `cd server && ./gradlew ktlintFormat` — applied, no remaining lint failures in `check`
- `cd web && npm run check` — Pass (only the existing initial bundle budget warning)

`.taskcheck`:
- `sh scripts/check.sh` — Pass ("all checks passed")

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
