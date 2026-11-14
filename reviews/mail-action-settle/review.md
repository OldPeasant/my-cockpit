# Code Review

Date: 2026-11-14

Task: mail-action-settle - Moved, archived or deleted mail must not come back in the source list until the next 5-min sync

Scope: the full task diff `git diff 27e0adeb4..HEAD -- server web` (commits 279194eea, 9a8fc9e88, 9441010af, 33fdebc17, eeb3acb13, efb91651c, d1c6b807c). I checked the round-2 fixes in d1c6b807c against the previous review. Report-only files under `reviews/` were read but not reviewed as code.

Requirements:
- tasks/mail-action-settle.md: acceptance criteria and the recorded assumption
- AGENTS.md project notes (conventions, lint, tests, evidence)
- Previous review and reviews/mail-action-settle/fixes.md ("round 2")
- Existing code conventions in the server `mail` module, `web/src/app/features/mail/read`, `web/src/app/shared/navigation` and `web/e2e`

Git range:
- Branch: main
- HEAD: d1c6b807c
- Base/start: 27e0adeb471ce5293b9c420635eb3b807596d5b8
- Commits reviewed: 7
- Uncommitted changes: none at the start of the review

Summary: 0 P0, 0 P1, 0 P2, 1 P3

Note: As the parent worker asked, I did not run Gradle, because the test-writer uses the same build directory at the same time. I did not run npm or Playwright either. The test results quoted here come from fixes.md, not from my own runs. The parent worker must still record evidence for each criterion with `tasks.py verify`.

## Status of previous findings

- CR-1 (P2, the e2e test reached the list with `page.goto` instead of going back in the app): fixed. `web/e2e/mail-list/list.spec.ts:150-158` archives the note from the read view and checks that the page moved on to another message (`not.toHaveURL(noteUrl)`). This matches `MailReadPage.leave`, which goes to the next message with `replaceUrl`. The test then clicks `a.back`, the anchor rendered by `app-back-button` (`shared/navigation/back-button.ts:21`), and checks the folder URL. It checks the list right away, again after 3 s, after `page.reload()`, and through the API. The in-session path the owner reported is now covered.
- CR-2 (P3, the tests did not check that `requestVerified` is never called): fixed. The fix adds `verify(exactly = 0) { resync.requestVerified(any()) }` at `MailActionServiceTests.kt:572` and `:636`.
- CR-3 (P3, a stale "left to the next sync" text): fixed. The `updateCache` KDoc (`MailActionService.kt:366-370`) and the INFO log (`:427`) now say that a resync repairs the cache. The log line adds no message data.
- CR-4 (P3, an unwrapped KDoc line): fixed. The class KDoc of `MailFolderResync.kt:15-21` is now wrapped at 120 columns.

## Re-check of the full diff

- `MailActionService.settle` (`:224-242`) requests a verified resync of the source after every Move (archive, spam and move are all `Operation.Move`) and of the destination. It does the same for the source of an Expunge. A flag change resyncs only when the cache was stale, as before. The request does not wait for the resync, as criterion 2 requires.
- `MailFolderResync.enqueue` merges requests with `ConcurrentHashMap.compute`. A merged request verifies when either request asked for it. `run` removes the entry before it syncs, so a request made during a running sync is not lost. A rejected execution is handled as before.
- `MailHeaderSyncService.verifyFolder` runs under the folder's sync lock, so it starts after any sync that raced the action. On the QRESYNC path it also compares UID sets (`:350-353`). The CONDSTORE and plain paths already compared UID sets, so they skip the extra SEARCH. Rows of UIDs that are gone are removed even when a racing sync advanced HIGHESTMODSEQ, which covers criterion 3. `announce` publishes `MAIL_FOLDER` only when rows changed. The action has already announced both folders.
- Criterion 4: moved (`v` and the picker), archived (toolbar and read view) and deleted (toolbar) mails are all absent from the final list. The test checks this in the UI after back navigation, after a wait, after a reload, and in `GET /api/v1/mail/messages`.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | The "after the change-event refetch" check uses a fixed 3-second wait without confirming that a refetch happened**
    - File: `web/e2e/mail-list/list.spec.ts:163-165`
    - Category: Tests
    - Problem: The test checks the list, waits `page.waitForTimeout(3_000)`, and checks it again. Nothing confirms that a `MAIL_FOLDER` event arrived or that the list was fetched again in those 3 s. The archive's change events were published while the read view was open. The verified resync announces only when it removed rows, which does not happen on GreenMail. So the second check may just repeat the first one.
    - Why it matters: The test is weaker than its comment claims. The reload and API checks that follow still cover the server-side behaviour, which is the actual fix, so this does not block the task.
    - Suggested fix: Optional. Wait for a list response, for example with `page.waitForResponse` on `/api/v1/mail/messages?folderId=…` around a step that triggers it, before the second check. Otherwise, reword the comment so it does not claim a refetch was observed.

## Review conclusion

No material issues found. The round-2 fixes are correct. The only new finding is an optional P3 about how strong one test step is. The parent worker must still run the required test suites and record evidence for each criterion (GreenMail suites, `npm run e2e` for mail-list, `./gradlew check`, `npm run check`, `scripts/check.sh`).
