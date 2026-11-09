---
title: Weekly review reminder on a configurable weekday
status: in_progress
owner: felix/agent-1
deps: [and-review-flow, and-review-steps, web-small-gaps, review-reminder-server, review-reminder-web, review-reminder-android]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/review,server/src/test/kotlin/ch/sonensei/cockpit/review,web/src/app/features/settings/general,web/src/app/features/review,android/app/src/main/java/ch/sonensei/cockpit/review,android/app/src/test/java/ch/sonensei/cockpit/review,web/src/locale,android/app/src/main/res/values/strings_review.xml,android/app/src/main/res/values-de/strings_review.xml]
prio: 3
lease_until: 2026-11-09T05:31:54Z
subtasks: review-reminder-server, review-reminder-web, review-reminder-android
releases: 1
extended: 
---
## Goal
SPEC.md §8.6: a weekly review reminder on a configurable weekday; today web only highlights the review after
`reviewDueDays`.

## Acceptance criteria
- [x] A setting "weekly review reminder: weekday + time / off" (server setting definition + web settings control)
  - verified (2026-11-09 05:25+0100 by felix/agent-1): On main 8a340b6b0: cd server && ./gradlew test --tests '*WeeklyReview*' --tests '*SettingsService*' -> WeeklyReviewScheduleTests 20, EdgeCase 27, WeeklyReviewWebTests 9 (setting weeklyReviewReminder off|DAY@HH:mm, invalid -> 400), SettingsServiceTests 6, 0 failures; cd web && npx ng test --watch=false --include general-settings.spec.ts --include reminder-notifications.service.spec.ts -> 92 passed (General settings reminder control load/save off/save day+time)
- [x] The server creates the reminder notification at that time (existing reminder delivery: web push, UnifiedPush/alarms)
  - verified (2026-11-09 05:25+0100 by felix/agent-1): On main 8a340b6b0: cd server && ./gradlew test --tests '*WeeklyReviewReminderTests' (Spring + Postgres testcontainer) -> 15 passed, 0 failures: pending reminder row (ItemType.WEEKLY_REVIEW) created at next weekday/time in user timezone, replaced/removed on change, re-armed after fireDue, overdue kept at startup; delivery uses the existing reminder table/fireDue pipeline (SSE/web push/UnifiedPush/Android alarms unchanged); ItemResolverRegistryTests 5 passed
- [x] Tapping it opens the review on web and Android
  - verified (2026-11-09 05:27+0100 by felix/agent-1): Server WeeklyReviewItemResolver deepLink '/review' matches web route path 'review' (app.routes.ts:59). cd web && npx ng test --watch=false --include reminder-notifications.service.spec.ts (+general-settings) -> 92 passed incl. WEEKLY_REVIEW click/toast navigateByUrl('/review'). cd android && ./gradlew testDebugUnitTest --tests '*ReminderUiTest' --tests '*ReminderAlarmsNotifierTest' --tests '*PushReminderDeliveryTest' -> BUILD SUCCESSFUL, 23+16+24 tests, 0 failures (WEEKLY_REVIEW -> CockpitDestination.Review for alarm and UnifiedPush taps)
- [x] New strings in English and German on both clients (`web/src/locale`, `res/values{,-de}/`)
  - verified (2026-11-09 05:25+0100 by felix/agent-1): grep: web/src/locale/messages.xlf and messages.de.xlf each have 5 settings.reviewReminder* units (de targets present); android res/values/strings_review.xml review_reminder_title='Weekly review', values-de='Wochenrückblick'; web general-settings spec 92 passed incl. messages; Android German/English title tests in ReminderAlarmsNotifierTest (see #3 run)
- [ ] Unit tests cover the new behavior on each changed part; `sh scripts/check.sh` passes

## Notes
Planned 2026-11-08 from the owner's UX request (web/Android parity, mail images, back navigation, mail search); the parity inventory and decisions are in `docs/PARITY.md`.
- ASSUMPTION (2026-11-09 02:31+0100 by felix/agent-1): Reminder delivered via existing reminder table with new ItemType.WEEKLY_REVIEW + review-module resolver (deepLink /review) so SSE/web push/UnifiedPush/Android alarms work unchanged; setting weeklyReviewReminder = 'off' | '<DAY>@HH:mm', default off
- split by felix/agent-1: into review-reminder-server, review-reminder-web, review-reminder-android. No code yet; design explored (reminder pipeline, item resolvers with deepLink, Android ReminderOpenRequests). Split into server/web/android subtasks with the design in their goals. Once they are done, this task is only integration: merge what is left on wip/review-reminder (if anything), verify the acceptance criteria end to end, done.
- ASSUMPTION (2026-11-09 05:30+0100 by felix/agent-1): Weekly review reminder notification title stays the server's English 'Weekly review' for now (CR-1/TW-1); localizing it on web and Android is deferred to new task review-reminder-title-l10n. German strings exist on both clients and are used in settings and as the Android fallback title.
- release by felix/agent-1: Integration of server/web/android subtasks verified: criteria 1-4 verified via focused server/web/android tests on main; quality evidence (tests.md, review.md, quality.json, reviewed_revision 2abe6d094) committed; CR-1 deferred to new task review-reminder-title-l10n (assumption recorded). LEFT: criterion 5 - sh scripts/check.sh timed out (900s, during server ktlint+tests under machine load); rerun it, verify #5, then tasks.py done (no code change needed; if main moved only update reviewed_revision).
