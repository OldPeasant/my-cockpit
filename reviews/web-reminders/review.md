# Code Review

Date: 2026-10-20

Task: web-reminders - Browser notifications for reminders

Scope: the final revision of the task, `git diff a401c4eb470b20cd5ca2cf0309aec679e0bb8078..44eb8e8fd2c17c1b9d7335df00b492f8a30da2b2` (task base .. HEAD), committed changes only. Since my last full review, the branch was rebased onto a newer main. The base moved from 8984a6af to a401c4eb, and the earlier reviewed commit 54bc380a is now 151980fe. This pass reviews the one new commit in detail, 44eb8e8f (rebased form of 8f70d8bb). It also confirms that nothing else in the task diff changed since the reviewed revision: `git diff --stat 151980fe 44eb8e8f` lists only the two test files below.

Requirements:
- tasks/web-reminders.md acceptance criteria 1-2 and its recorded assumptions (switch stored in localStorage, `ChangeEventsService.messages$`, notification-or-toast policy, wiring outside the touches, a new reminder toast replaces an app message only when no reminder is queued)
- SPEC.md §4.6 (reminders fired by the server, SSE event to the web, browser Notification API with permission)
- docs/ARCHITECTURE.md and the AGENTS.md project notes (Angular conventions, tests, lint, e2e evidence)
- The earlier full review of the same production code (151980fe, formerly 54bc380a): no findings

Git range:
- Branch: main
- HEAD: 44eb8e8fd2c17c1b9d7335df00b492f8a30da2b2
- Base/start: a401c4eb470b20cd5ca2cf0309aec679e0bb8078 (`git merge-base HEAD origin/main`)
- Commits reviewed: 10 task commits in total (302c4484 .. 44eb8e8f). The production code was reviewed in full before. This pass covers 44eb8e8f in detail.
- Uncommitted changes: only `reviews/web-reminders/review.md` and `reviews/web-reminders/tests.md`. No spec or source file was modified in the working tree when I ran the tests.

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Notes (verified, no finding)

- Commit 44eb8e8f changes tests only: 4 lines in `web/e2e/reminders/notifications.spec.ts` and 57 added lines in `reminder-notifications.service.spec.ts`. No production code changed, so the earlier verdict on the implementation still holds. That verdict covered the queue ordering against Material's `_openedSnackBarRef` and `afterDismissed`, the permission request inside the click, SSE listening, the toast fallback, stored-state handling and destroy.
- The e2e change: the toast test now waits for the "Show reminders as browser notifications" switch before `remindNow` reads the XSRF cookie. In `general-settings.html` the toggle renders only in the `@else` branch after `loading()` ends without an error. A visible switch therefore means the settings GET has finished, so the app's first API round trip is done and the cookie has been renewed. This is a real wait condition, not a fixed sleep. It also matches the first test, which already waits on the switch before it calls `remindNow`. The parent's run `npm run e2e -- e2e/reminders/notifications.spec.ts --repeat-each=3 --workers=1` -> 11 passed is recorded on criterion 1.
- The three new real-`MatSnackBar` specs (`reminder-notifications.service.spec.ts:557`, `:578`, `:597`) test what they say they test. (a) An app message replaced by a second app message keeps its own 4 s before the queued reminder shows. (b) A reminder that fires while an app message holds the queue goes in behind the reminders already waiting. I checked the timings: the error closes at about t=9 s, "Water the plants" runs t≈9-24 s, and "Pay the rent" is checked at t=26 s. (c) Destroying the app while an app message holds the queue opens no further snackbar. They use fake timers and dismiss everything in `afterEach`, so no snackbar or timer leaks into the next spec.
- I ran the tests myself: `npx ng test --watch=false --include src/app/core/notifications/reminder-notifications.service.spec.ts` (in `web/`) -> 1 file, 41 tests passed. I did not run the full `npm run check` or the Playwright spec.
- Evidence: criterion 1 is verified with the e2e run against the e2e compose stack. The Notification API is stubbed there, and the evidence says so. Criterion 2 (`npm run check` passes) has no evidence yet. The parent worker must record it on the final revision before `done`.

## Review conclusion

No material issues found.
