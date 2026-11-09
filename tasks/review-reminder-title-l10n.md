---
title: Weekly review reminder notification title in the client language
status: in_progress
owner: dell26/agent-1
deps: [review-reminder]
touches: [web/src/app/core/notifications,android/app/src/main/java/ch/sonensei/cockpit/reminders,android/app/src/test/java/ch/sonensei/cockpit/reminders]
prio: 2
lease_until: 2026-11-09T09:19:15Z
---
## Goal
Finding CR-1 of review-reminder (reviews/review-reminder/review.md): the server's WeeklyReviewItemResolver always
returns the English title "Weekly review", and the clients prefer the server title, so German users never see
"Wochenrückblick" (web: reminder-notifications.service.ts, push-sw.js; Android: ReminderNotifier.kt).
For item type WEEKLY_REVIEW the clients show their own translated title.

## Acceptance criteria
- [x] Web (in-app notification/toast and service worker push) shows the localized title for WEEKLY_REVIEW reminders (unit tests, en + de)
  - verified (2026-11-09 09:57+0100 by dell26/agent-1): cd web && npx ng test --watch=false --include='src/app/core/notifications/**/*.spec.ts' -> 8 files, 135 passed (reminder-notifications.service.spec: weekly review notification+toast en, de 'Wochenrückblick' with server English title; push-sw.spec: weekly review en/de, names match messages.xlf/messages.de.xlf)
- [x] Android (local alarm and UnifiedPush) shows review_reminder_title for WEEKLY_REVIEW reminders, also when the server sends a title (unit tests, en + de)
  - verified (2026-11-09 09:57+0100 by dell26/agent-1): cd android && ./gradlew testDebugUnitTest --tests '*ReminderAlarmsNotifierTest' --tests '*PushReminderDeliveryTest' -> pass (19+25 tests, 0 failures): local alarm and UnifiedPush show 'Weekly review' / de 'Wochenrückblick' for WEEKLY_REVIEW also when the server sends a title
- [x] `sh scripts/check.sh` passes
  - verified (2026-11-09 10:00+0100 by dell26/agent-1): sh scripts/check.sh on fe391e372 (web lint+tests+build, server check, android ktlint+unit tests+lint) -> all checks passed, exit 0

## Notes
- ASSUMPTION (2026-11-09 09:49+0100 by dell26/agent-1): WEEKLY_REVIEW reminders always show the client's translated name (web i18n id reminders.weeklyReview, push-sw TEXTS.weeklyReview, Android review_reminder_title) and ignore any server title, since the server title is a fixed English label, not user data. Touched web/public/push-sw.js and android push/PushReminderDeliveryTest.kt outside the declared touches: the goal names push-sw.js and that test asserted the old keep-server-title behavior.
