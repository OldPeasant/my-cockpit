# Code Review

Date: 2026-11-09

Task: review-reminder - Weekly review reminder on a configurable weekday (integration of review-reminder-server, review-reminder-web, review-reminder-android)

Scope: integration review of the subtasks' code as merged on main, `git log 502809565^..8a340b6b0 -- server/src web/src android/app/src api/` (review-reminder-related files only), plus the follow-up test commit e6ab74a81 (`web/src/app/core/notifications/push-sw.spec.ts`, +43 lines). The final reviewed revision is e6ab74a8171ffa13aea96f285b82a28d1ac61bc0. The parent task has no production code change of its own. The subtasks' own reviews are in `reviews/review-reminder-{server,web,android}/`. After a rebase, the local HEAD carries the same change as 2abe6d094. `git patch-id --stable` is identical for both (632f7849...), and `git diff e6ab74a81 2abe6d094 -- server web android api` is empty. Only a `tasks/review-reminder.md` change is uncommitted, and it is out of scope.

Requirements:
- `tasks/review-reminder.md` acceptance criteria 1-5 and recorded assumption (setting `weeklyReviewReminder` = `off` | `<DAY>@HH:mm`, ItemType WEEKLY_REVIEW, deepLink `/review`)
- SPEC.md §8.6 (weekly review reminder), AGENTS.md project notes, docs/QUALITY.md (report contract)

Git range:
- Branch: main
- HEAD: e6ab74a8171ffa13aea96f285b82a28d1ac61bc0 (final reviewed revision; rebased locally as 2abe6d0947ff87277ee54ec511191b8023b08361 with the same patch-id)
- Base/start: 502809565^
- Commits reviewed: 15. These are the review-reminder-* commits in the range: 69c89585e, 5b315a193, e8ac084d6, f373899f8, f41a6ebc6, 3bb2b343c, adb06dc84, 422a989b2, d2cc62ddb, 8ad8c11a5, 1c05ce794, a43c9f505, 0b72c53d7, d2c7a4ad1, and the test commit e6ab74a81. The other commits in the range belong to unrelated tasks, or touch only `tasks/`/`reviews/`, and were skipped.

Commands used:
- Read-only: `git log`/`git diff --stat` over the range; `git show e6ab74a81`; `git patch-id --stable` comparison of e6ab74a81 and 2abe6d094; reads of `review/domain/WeeklyReviewReminders.kt`, `review/domain/WeeklyReviewSchedule.kt`, `review/infra/ReviewSettings.kt`, `review/infra/WeeklyReviewItemResolver.kt`, `core/reminders/domain/ReminderService.kt`, `web/src/app/features/settings/general/general-settings.ts`, `web/src/app/app.routes.ts`, `web/src/app/core/notifications/reminder-notifications.service.ts`, `web/public/push-sw.js`, `web/src/app/core/notifications/push-sw.spec.ts`, `web/src/locale/messages{,.de}.xlf`, `android/.../reminders/ReminderOpenRequests.kt`, `ReminderNotifier.kt`, `push/PushReminderDelivery.kt`, `res/values{,-de}/strings_review.xml`, `api/openapi.yaml`, `tasks/review-reminder-title-l10n.md`; `python tasks.py verify review-reminder` (list only).
- Tests: `npm --prefix web test -- --watch=false --include src/app/core/notifications/push-sw.spec.ts` -> 1 file, 21 tests passed (1.37 s).
- I did not run `sh scripts/check.sh`, because the parent runs it separately.

Integration checks that passed:
- Setting format: the server's `WeeklyReviewSchedule` regex `([A-Z]+)@([01][0-9]|2[0-3]):([0-5][0-9])` with `DayOfWeek` names, plus `off`, matches the web `REMINDER_PATTERN` / `WEEK_DAYS` (`MONDAY`..`SUNDAY`) and `formatReminder`. Unknown values count as off on both sides.
- Delivery: `WeeklyReviewReminders.sync()` creates rows through `ReminderService.create` on the WEEKLY_REVIEW item. This means the existing SSE, web push, UnifiedPush and Android alarm paths carry it unchanged. It re-arms on `ReminderFiredEvent`, re-syncs on setting and time-zone changes and at startup, and uses an advisory lock against duplicate syncs. `ItemType.WEEKLY_REVIEW` is in `api/openapi.yaml` and in the regenerated web client models.
- Tap target: the resolver's deepLink `/review` matches the web route `{ path: 'review' }` (`app.routes.ts:59`). The in-page notification (`reminder-notifications.service.ts:90`) and the service worker (`push-sw.js`, `pushNotification` -> `data.route`, `onClick` -> `appUrl`) both use it. On Android, `OpenItemRequest.destination` maps `WEEKLY_REVIEW` to `CockpitDestination.Review`.
- Strings: the web IDs `settings.reviewReminder{,.hint,.off,.time,.timeInvalid}` exist in `messages.xlf` and have translated targets in `messages.de.xlf`. Android `review_reminder_title` exists in `values/` and `values-de/`.
- Test commit e6ab74a81: the two new tests load the real `public/push-sw.js` into the existing fake worker scope through `new Function('self', source)`, and drive its real `push` and `notificationclick` handlers. They check the following against the actual code:
  - the title is taken from `item.title`;
  - the tag is `reminder-<id>`;
  - `data.route` is the deepLink when `available`;
  - `appUrl` strips the leading `/` and resolves against the registration scope (`${SCOPE}review`; for the German build, `${DE_SCOPE}review`);
  - an existing in-scope tab is focused and navigated, and no new window opens.

  The tests are deterministic. Every fake returns a resolved promise, `dispatch` awaits the `waitUntil` promise, and the tests use no timers or shared mutable state, because each test builds its own `worker()`. The tests passed when I ran them. The test data is fictitious.

Summary: 0 P0, 0 P1, 1 P2, 0 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | Weekly review notification title is always English. The German client strings are never shown in the notification. (Deferred to follow-up task `review-reminder-title-l10n`.)**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/review/infra/WeeklyReviewItemResolver.kt:16`, `android/app/src/main/java/ch/sonensei/cockpit/reminders/ReminderNotifier.kt:40`, `web/src/app/core/notifications/reminder-notifications.service.ts:88`, `web/public/push-sw.js:56`
    - Category: Integration
    - Problem: The resolver always returns the hard-coded English title `"Weekly review"`, and the clients prefer the server title. Android uses `reminder.title ?: getString(...)`. The web in-page notification uses `reminder.item.title || REMINDER`, and the service worker uses `item.title || T.reminder`. A German user therefore sees "Weekly review" in English, while the German strings (`review_reminder_title`, `itemType.weeklyReview`) are never reached in this path. The new test in e6ab74a81 (`push-sw.spec.ts`, `expect(title).toBe('Weekly review')`) asserts the server-provided title. That matches today's behavior, but `review-reminder-title-l10n` will have to change this assertion.
    - Why it matters: Criterion 4 asks for the new strings in English and German on both clients. The German string exists but is not what the user sees in the reminder itself.
    - Suggested fix: For `itemType == WEEKLY_REVIEW`, prefer the client's localized title on Android (`R.string.review_reminder_title`), on the web in-page notification and in the `push-sw.js` TEXTS table, and adjust the client tests, including the new `push-sw.spec.ts` case.
    - Disposition: the parent deferred this to the new task `tasks/review-reminder-title-l10n.md` (deps: review-reminder; it has web and Android criteria for en and de). This is an acceptable disposition for a P2 finding, provided `quality.json` records it as deferred.

## P3 Low

None.

## Review conclusion

No material issues found.

The end-to-end contract is consistent across server, web and Android: setting format, delivery through the existing reminder pipeline, and the `/review` deep link and Android mapping. The new service-worker tests in e6ab74a81 are correct. They exercise the real `push-sw.js` and are not flaky. The only finding is CR-1 (P2, the German notification title), which is deferred with a tracked follow-up task, so it does not block this task. Before `done`, the parent worker must still record evidence for all five acceptance criteria (`python tasks.py verify review-reminder`), including the `sh scripts/check.sh` result.
