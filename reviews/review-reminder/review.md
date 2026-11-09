# Code Review

Date: 2026-11-09

Task: review-reminder - Weekly review reminder on a configurable weekday (integration of review-reminder-server, review-reminder-web, review-reminder-android)

Scope: integration review of the subtasks' code as merged on main, `git log 502809565^..8a340b6b0 -- server/src web/src android/app/src api/` (review-reminder-related files only). The parent task has no code change of its own. The subtasks' own reviews are in `reviews/review-reminder-{server,web,android}/`. Uncommitted changes: one modified file in the working tree (`web/src/app/core/notifications/push-sw.spec.ts`). It is not part of the committed scope and was not reviewed.

Requirements:
- `tasks/review-reminder.md` acceptance criteria 1-5 and recorded assumption (setting `weeklyReviewReminder` = `off` | `<DAY>@HH:mm`, ItemType WEEKLY_REVIEW, deepLink `/review`)
- SPEC.md §8.6 (weekly review reminder), AGENTS.md project notes, docs/QUALITY.md (report contract)

Git range:
- Branch: main
- HEAD: 8a340b6b0c9f756488f603ca0e51f7572c188fdd
- Base/start: 502809565^
- Commits reviewed: the review-reminder-* commits in that range (69c89585e, 5b315a193, e8ac084d6, f373899f8, f41a6ebc6, 3bb2b343c, adb06dc84, 422a989b2, d2cc62ddb, 8ad8c11a5, 1c05ce794, a43c9f505, 0b72c53d7, d2c7a4ad1). The other commits in the range belong to unrelated tasks and were skipped.

Commands used (read-only): `git log`/`git diff --stat` over the range; `grep`/reads of
`review/domain/WeeklyReviewReminders.kt`, `review/domain/WeeklyReviewSchedule.kt`, `review/infra/ReviewSettings.kt`,
`review/infra/WeeklyReviewItemResolver.kt`, `core/reminders/domain/ReminderService.kt`,
`web/src/app/features/settings/general/general-settings.ts`, `web/src/app/app.routes.ts`,
`web/src/app/core/notifications/reminder-notifications.service.ts`, `web/public/push-sw.js`,
`web/src/locale/messages{,.de}.xlf`, `android/.../reminders/ReminderOpenRequests.kt`, `ReminderNotifier.kt`,
`push/PushReminderDelivery.kt`, `res/values{,-de}/strings_review.xml`, `api/openapi.yaml`; `python tasks.py verify review-reminder` (list only).
I did not run `sh scripts/check.sh` or the test suites in this review.

Integration checks that passed:
- Setting format: the server's `WeeklyReviewSchedule` regex `([A-Z]+)@([01][0-9]|2[0-3]):([0-5][0-9])` with `DayOfWeek` names, plus `off`, matches the web `REMINDER_PATTERN` / `WEEK_DAYS` (`MONDAY`..`SUNDAY`) and `formatReminder` (`off` or `${day}@${time}`). Unknown values count as off on both sides.
- Delivery: `WeeklyReviewReminders.sync()` creates rows through `ReminderService.create` on the WEEKLY_REVIEW item. This means the existing SSE, web push, UnifiedPush and Android alarm paths carry it unchanged. It re-arms on `ReminderFiredEvent`, re-syncs on setting and time-zone changes and at startup, and uses an advisory lock against duplicate syncs. `ItemType.WEEKLY_REVIEW` is in `api/openapi.yaml` and in the regenerated web client models.
- Tap target: the resolver's deepLink `/review` matches the web route `{ path: 'review' }` (`app.routes.ts:59`). It is used both by the in-page notification (`reminder-notifications.service.ts:90`) and by the service worker (`push-sw.js:62,90`). On Android, `OpenItemRequest.destination` maps `WEEKLY_REVIEW` to `CockpitDestination.Review`.
- Strings: the web IDs `settings.reviewReminder{,.hint,.off,.time,.timeInvalid}` exist in `messages.xlf` and have translated targets in `messages.de.xlf`. Android `review_reminder_title` exists in `values/` and `values-de/`.
- Tests exist on each part: server `WeeklyReviewReminderTests`, `WeeklyReviewScheduleTests`; web `general-settings.spec.ts`, `reminder-notifications.service.spec.ts`; Android `PushReminderDeliveryTest`, `ReminderAlarmsNotifierTest`.

Summary: 0 P0, 0 P1, 1 P2, 0 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | Weekly review notification title is always English. The German client strings are never shown in the notification.**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/review/infra/WeeklyReviewItemResolver.kt:16`, `android/app/src/main/java/ch/sonensei/cockpit/reminders/ReminderNotifier.kt:40`, `web/src/app/core/notifications/reminder-notifications.service.ts:88`, `web/public/push-sw.js:56`
    - Category: Integration
    - Problem: The resolver always returns the hard-coded English title `"Weekly review"`. The server test asserts it (`WeeklyReviewReminderTests.kt:118`), and the server has no language setting. Android uses `reminder.title ?: getString(untitledTitle(itemType))`, so the German `review_reminder_title` ("Wochenrückblick") is used only when the server sends no title, which never happens for this item. On web, both the in-page notification (`reminder.item.title || REMINDER`) and the service worker (`item.title || T.reminder`) show the server title. A German user therefore sees "Weekly review" in English on every client. The client strings exist but are dead in the main path. The rest of the web notification text is localized (`push-sw.js` TEXTS en/de, `$localize` body).
    - Why it matters: Criterion 4 asks for the new strings in English and German on both clients. The German string is added but is not what the user sees in the reminder itself. The subtask reviews tested each part separately and did not catch this, because the Android test "server-provided title being kept" fixes this behavior in place.
    - Suggested fix: Let the clients localize by item type. When `itemType == WEEKLY_REVIEW`, Android should prefer `R.string.review_reminder_title` over the server title. Web should use the localized `itemType.weeklyReview` label in `reminder-notifications.service.ts` and a `weeklyReview` entry in the `push-sw.js` TEXTS table. Adjust the client tests to match. Alternatively, record a `tasks.py assume` that the notification title stays English, if that is acceptable.

## P3 Low

None.

## Review conclusion

Material issues found; fix before completing the task.

The end-to-end contract is consistent across server, web and Android: setting format, delivery through the existing reminder pipeline, and the `/review` deep link and Android mapping. The only finding is CR-1 (P2, the German notification title). Fix it, or record it as an assumption, before `done`. Separately, all five parent acceptance criteria are still unverified (`python tasks.py verify review-reminder`). The parent worker must record evidence, including a run of `sh scripts/check.sh`, before completing the task.
