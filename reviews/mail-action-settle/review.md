# Code Review

Date: 2026-11-14

Task: mail-action-settle - Moved, archived or deleted mail must not come back in the source list until the next 5-min sync

Scope: committed task changes `git diff 6e3d4c64a..HEAD` (commits 18a292d8b, d9da02d08); uncommitted test-writer files ignored

Requirements:
- tasks/mail-action-settle.md acceptance criteria and recorded assumption
- AGENTS.md project notes (conventions, lint, tests)
- Existing code conventions in server `mail` module and `web/e2e`

Git range:
- Branch: main
- HEAD: d9da02d08
- Base/start: 6e3d4c64a813c25fd8849684b819166894a71941
- Commits reviewed: 2

Summary: 0 P0, 2 P1, 1 P2, 2 P3

Note: Bash use was partly restricted, so this review checked the code by reading it. I did not run the test suite. CR-1 comes from following each test's mocks through the new code.

## P0 Critical

None.

## P1 High

- [ ] **CR-1 | Existing MailActionServiceTests still expect `resync.request` where the code now calls `requestVerified`**
    - File: `server/src/test/kotlin/ch/sonensei/cockpit/mail/api/MailActionServiceTests.kt:521`, `:556`, `:619`, `:676` (behavior from `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailActionService.kt:227-240`)
    - Category: Tests
    - Problem: `settle()` now calls `resync.requestVerified(...)` for every `Operation.Move` (MOVE/ARCHIVE/SPAM/DELETE outside Trash) and `Operation.Expunge`. It calls `resync.request(...)` only for flag changes with stale rows. Four unchanged tests still verify `resync.request(...)` on those paths. They are: the DELETE from Inbox with every message gone (line 521, `exactly = 1`), the MOVE with one gone message (line 556, `exactly = 1`), the mixed Trash/Inbox delete (line 619, `exactly = 1`) and the Trash delete without a cached UIDVALIDITY (line 676). `resync` is a `mockk(relaxUnitFun = true)`, so `requestVerified` never calls `request`. These verifications cannot pass. Lines 557 and 620 (`exactly = 0` for the target) still pass, but they no longer show what they were meant to show, because the target is now resynced through `requestVerified`.
    - Why it matters: `cd server && ./gradlew check` and `.taskcheck` should fail on the committed code, so criterion 5 is not met. `tasks.py done` would reject the task.
    - Suggested fix: Change these tests to verify `requestVerified` for the source, and for the target of a move. Check that `request` is not called on those paths. Then run `./gradlew check` and record the result.

- [ ] **CR-2 | No test covers the main new behavior: resync after move, archive, spam or delete**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailActionService.kt:216`, `:227-240`
    - Category: Tests
    - Problem: Criterion 2 requires a background resync by UID set of the source after every move, archive, spam or delete, and of the target too for a move. No test in the diff checks that a successful MOVE, ARCHIVE, SPAM, DELETE to Trash or permanent delete in Trash calls `requestVerified` for those folders. No test checks that READ/FLAG without stale rows calls neither method. There is also no test for the cache-conflict path (candidate 1): two `OptimisticLockingFailureException`s in `updateCache`, after which the verified resync of the source must still be requested. The new tests only cover `MailFolderResync` merging and `MailHeaderSyncService.verifyFolder` on its own.
    - Why it matters: Nothing checks how the action service is wired to the resync, and that wiring is the actual fix. If it is removed or the operations are mixed up, all tests still pass.
    - Suggested fix: Add `MailActionServiceTests` cases for each action type. Each should assert `requestVerified(source)` and, for moves, `requestVerified(target)`. Add one test where `transactions.executeWithoutResult` throws `OptimisticLockingFailureException` on both attempts and the verified resync is still requested.

## P2 Medium

- [ ] **CR-3 | Criteria 1 and 3: no reproduction was tried, and the new "race" test locks in the stale behavior**
    - File: `server/src/test/kotlin/ch/sonensei/cockpit/mail/sync/MailHeaderSyncServiceTests.kt:305-317`; assumption in `tasks/mail-action-settle.md:56`
    - Category: Tests
    - Problem: Criterion 1 asks for a test that reproduces the stale source row and fails before the fix. It also allows a recorded assumption, but only if none of the candidates reproduces. The recorded assumption refers only to the earlier interactive GreenMail probe. The diff contains no attempt at an interleaved action and sync (latch-controlled GreenMail) and no test for the cache-conflict give-up. Yet candidate 1 can be reproduced with a unit test: before this change, the row stayed in the source and no resync was requested. The new test `a plain QRESYNC sync keeps a row whose expunge a racing sync already covered...` asserts that a plain `syncFolder` keeps the stale row. That is a characterization test of the bug, not a regression test for criterion 3 ("a sync of a folder never brings back a row the action removed"). Criterion 3 holds only end to end, through the follow-up `verifyFolder`, and nothing tests that end to end.
    - Why it matters: A human cannot tell whether a cause was found or only guessed. The test suite also locks in the stale-row behavior of a plain sync.
    - Suggested fix: Add a test that fails before the fix. Either use the cache-conflict path (CR-2) or run an action and a `syncFolder` in an order controlled by latches against GreenMail or mocks, and assert that the row is gone after the verified resync. Reword or drop the characterization test, or name it clearly as documenting a known limitation that `verifyFolder` covers. Update the assumption to say which candidates were tried.

## P3 Low

- [ ] **CR-4 | Verify mode runs a second UID SEARCH on the CONDSTORE and plain-sync paths**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/mail/sync/MailHeaderSyncService.kt:342`, `:346`, `:349`
    - Category: Quality
    - Problem: When `verifyUids` is true, line 349 calls `remote.uidsFrom(lowest)` again. On the CONDSTORE path (line 342), `vanished` is already a UID-set comparison. On the plain path (line 346), the UID set comes from `flagsFrom(lowest)`. Only the QRESYNC path needs the extra search.
    - Why it matters: Every move or delete triggers one or two verified resyncs, so a large folder gets an extra `UID SEARCH` round-trip each time.
    - Suggested fix: Add the UID-set comparison only on the QRESYNC branch, for example `qresync != null && verifyUids`.

- [ ] **CR-5 | Logs and KDoc still describe the resync as happening only after a stale action**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailFolderResync.kt:70`, `:72`, `:75`; `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailActionService.kt:114-117`
    - Category: Quality
    - Problem: The log messages still say "after a stale action", but most resyncs now follow normal moves and deletes. The class KDoc of `MailActionService` still says that a cache update which gives up after a conflict is "left to the next sync". The verified resync now repairs it right away.
    - Why it matters: Operators reading INFO/WARN logs, and maintainers reading the KDoc, get a wrong picture of when and why resyncs run.
    - Suggested fix: Make the log text neutral (for example "Resync of mail folder {} failed"). Update the KDoc paragraph to mention the verified background resync after moves and deletes.

## Review conclusion

Material issues found; fix before completing the task.
