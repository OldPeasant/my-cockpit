# Fix Report

Date: 2026-11-14

Task: mail-action-settle - Moved, archived or deleted mail must not come back in the source list until the next 5-min sync

Scope: current task, findings CR-1 to CR-5 of `reviews/mail-action-settle/review.md` (based on HEAD b73e7b2b8)

## Fixed findings

### CR-1 — Existing MailActionServiceTests still expect `resync.request` where the code now calls `requestVerified`

Files changed:
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/api/MailActionServiceTests.kt`

Problem:
`MailActionService.settle` calls `resync.requestVerified` for every move (MOVE/ARCHIVE/SPAM/DELETE outside Trash) and for an expunge (delete in Trash). Four tests still verified `resync.request`, and the parent's Gradle run showed them failing.

Fix:
The four tests now expect what `settle` intends to do. `requestVerified` is called for the source, and for the target of a move or delete outside Trash. `request` is never called on these paths. The mixed Trash/Inbox delete expects `requestVerified(trash)` twice: once as the target of the Inbox delete and once as the expunge source. `MailFolderResync` merges the two requests. Test names that no longer fit were changed: "...the cache is left to the next sync" became "...the folders are resynced by UID set", and the other two names now say "UID-set resync" or "both resynced by UID set". No assertion was weakened.

Validation:
- `./gradlew test --tests '*MailActionServiceTests' ...` — Pass (48 tests, 0 failures)

### CR-2 — No test covers resync after move, archive, spam or delete

Files changed:
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/api/MailActionServiceTests.kt`

Problem:
Before this fix, the suite already covered MOVE, DELETE outside Trash, DELETE in Trash, flag changes with and without stale rows, and the cache-conflict path. The conflict test is `a move whose cache update gives up after conflicts still resyncs the source by UID set so the row cannot stay`. It fails the flush on every attempt, and it would fail without the fix because no resync was requested on that path. ARCHIVE and SPAM were not covered.

Fix:
Added `archive and spam resync the source and their role folder by UID set in the background`. For each of the two actions, the test makes the `projects` folder the ARCHIVE or SPAM role folder. It then asserts `requestVerified` on the source and on the role folder, and asserts that `request` is never called.

Validation:
- `./gradlew test --tests '*MailActionServiceTests'` — Pass

### CR-3 — The "race" test locked in the stale plain-sync behavior

Files changed:
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/sync/MailHeaderSyncServiceTests.kt`

Problem:
`a plain QRESYNC sync keeps a row whose expunge a racing sync already covered...` only asserted that the stale row is kept.

Fix:
Replaced it with the regression test `after a racing sync covered an expunge by the cached HIGHESTMODSEQ, the verified resync after the action removes the row`. The setup is the same. The plain `syncFolder` assertion is still there, but a comment now names it as the known limitation that the verified resync covers. The test then runs `verifyFolder` and asserts that UID 5 is removed.

Validation:
- `./gradlew test --tests '*MailHeaderSyncServiceTests'` — Pass (51 tests, 0 failures)

### CR-4 — Verify mode ran a second UID SEARCH on the CONDSTORE and plain paths

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/mail/sync/MailHeaderSyncService.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/sync/MailHeaderSyncServiceTests.kt`

Problem:
`incremental` called `remote.uidsFrom(lowest)` again in verify mode, even when the CONDSTORE or plain branch had already compared UID sets.

Fix:
The extra comparison now runs only when `verifyUids && qresync != null && lowest != null`. Added the test `verifyFolder with CONDSTORE but no QRESYNC reads the UID set only once`, which asserts that the remote calls are exactly `uidsFrom 3`. Before the fix there were two calls. `ktlintFormat` had wrapped the over-long `full`/`incremental` dispatch line nearby into an awkward shape, so I rewrote that line by hand as a short `sameValidity` local. The behavior is unchanged.

Validation:
- `./gradlew test --tests '*MailHeaderSyncServiceTests' ktlintCheck` — Pass

### CR-5 — Logs and KDoc still described the resync as only after a stale action

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailFolderResync.kt`
- `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailActionService.kt`

Problem:
The log messages said "after a stale action". The class KDoc said a cache update that gives up after a conflict is "left to the next sync".

Fix:
The log text is now neutral ("Resync of mail folder {} failed..."). The DEBUG success line also logs whether the resync was verified. No new data is logged, only the folder id, a boolean and a count. The KDoc now describes the verified background resync of the source, and of the target of a move, after every move or delete, and the plain resync of a flag change only when there are stale rows. `ktlintFormat` also added one blank line after the `log` property in `MailFolderResync.kt`, which ktlint requires.

Validation:
- `./gradlew ktlintCheck` — Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `cd server && ./gradlew test --tests '*MailActionServiceTests' --tests '*MailFolderResyncTests' --tests '*MailHeaderSyncServiceTests' ktlintCheck` — BUILD SUCCESSFUL (MailActionServiceTests 48/0 failures, MailFolderResyncTests 8/0, MailHeaderSyncServiceTests 51/0)

Full test suite:
- Not run (time budget; the parent worker runs `./gradlew check` and `.taskcheck`)

Lint/type-check/build:
- `./gradlew ktlintFormat ktlintCheck` — Pass

`.taskcheck`:
- Not run (left to the parent worker)

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.

---

# Fix Report (round 2)

Date: 2026-11-14

Task: mail-action-settle - Moved, archived or deleted mail must not come back in the source list until the next 5-min sync

Scope: the second review's findings CR-1 (P2) and CR-2..CR-4 (P3) in `reviews/mail-action-settle/review.md`, at HEAD 8ca8ddb0d.

## Fixed findings

### CR-1 — The e2e check for "archive from the read view and going back" reloads the page instead of going back

Files changed:
- `web/e2e/mail-list/list.spec.ts`

Problem:
After the read-view Archive, the spec opened the folder with `page.goto`, which is a full page load. It never used the in-app back navigation.

Fix:
After Archive, the read view goes on to the next message of the list and replaces the URL (`MailReadPage.leave`). It goes back to the list only when no message is left. The spec now checks that the URL left the archived message and is still a message URL. Then it clicks the read view's back control (`a.back`), checks that the URL is the folder list again, and checks that the note is gone right away, again after 3 s (change-event refetch) and after `page.reload()`. The API listing check is unchanged.

Running the spec found two other problems that stopped it before the CR-1 part. Both are fixed in the same file, test-only:
- Line 89, `getByRole('button', { name: 'Archive' })`, matched 3 buttons (the folder tree's "Actions for Archive" and the toolbar's "→ Next action & archive", both added by other tasks). This is a strict-mode violation, so it now uses `exact: true`, as the read-view Archive and Delete locators already do. Main already had this problem before this task.
- The list Delete added in commit 3aa5c98f1 never worked. The test mailbox had no Trash folder, so the server answered `NO_ROLE_FOLDER` (seen in the Playwright trace) and the row stayed. The setup now creates `Trash`, as `e2e/phase5/demo.spec.ts` does. The spec also asserts that the memo reached Trash on GreenMail. The class comment names the new folder and the delete/archive checks.

Validation:
- `cd web && npx eslint e2e/mail-list/list.spec.ts && npx prettier --check e2e/mail-list/list.spec.ts && npm run e2e:typecheck` — Pass
- `cd web && npm run e2e -- e2e/mail-list/ --project=chromium` (`deploy/docker-compose.e2e.yml` stack built and started by `run-e2e.mjs`, GreenMail 2.1.5; the `phase-demos` project runs as well) — Pass, 36 passed (1.6m), `[chromium] mail-list/list.spec.ts` passed in 13.8s
- Before the fixes, `npm run e2e -- mail-list` failed at line 89 (strict-mode violation). With only `exact: true` added, it failed at line 140 (memo row still shown, `NO_ROLE_FOLDER`).

### CR-2 — `an action that finds all its messages starts no resync` checks only half of its name

Files changed:
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/api/MailActionServiceTests.kt`

Problem:
The READ test and the first half of the flag test did not check that `requestVerified` was never called.

Fix:
Added `verify(exactly = 0) { resync.requestVerified(any()) }` after the READ action and after the first FLAG action.

Validation:
- `cd server && ./gradlew ktlintCheck test --tests '*MailActionServiceTests*' --tests '*MailFolderResyncTests*'` — Pass (MailActionServiceTests 51 tests, 0 failures; MailFolderResyncTests 8 tests, 0 failures)

### CR-3 — A conflict log line and a KDoc still say a move's cache update is "left to the next sync"

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailActionService.kt`

Problem:
The `updateCache` KDoc and the INFO log line said "left to the next sync". Moves and deletes now get an immediate verified resync.

Fix:
The INFO log now reads "Mail action applied on the server; its cache update gave up after a concurrent change, a resync repairs it". The KDoc says that a resync repairs the cache: right away for a move or delete, at the next sync for a flag change. The log line has no new data.

Validation:
- same Gradle command as CR-2 — Pass

### CR-4 — The class KDoc of `MailFolderResync` has an unwrapped 164-character line

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailFolderResync.kt`

Problem:
One KDoc line was 164 characters long.

Fix:
Rewrapped the paragraph to 120 columns. The text is unchanged.

Validation:
- same Gradle command as CR-2 (ktlintCheck) — Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `cd server && ./gradlew ktlintCheck test --tests '*MailActionServiceTests*' --tests '*MailFolderResyncTests*'` — BUILD SUCCESSFUL (51/0 and 8/0)
- `cd web && npx eslint e2e/mail-list/list.spec.ts`, `npx prettier --check e2e/mail-list/list.spec.ts`, `npm run e2e:typecheck` — Pass
- `cd web && npm run e2e -- e2e/mail-list/ --project=chromium` — 36 passed

Full test suite:
- Covered by `.taskcheck` below

Lint/type-check/build:
- Covered by `.taskcheck` below

`.taskcheck`:
- TASKCHECK_RESULT

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
