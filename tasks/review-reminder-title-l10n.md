---
title: Weekly review reminder notification title in the client language
status: in_progress
owner: dell26/agent-1
deps: [review-reminder]
touches: [web/src/app/core/notifications,android/app/src/main/java/ch/sonensei/cockpit/reminders,android/app/src/test/java/ch/sonensei/cockpit/reminders]
prio: 2
lease_until: 2026-11-09T09:07:19Z
---
## Goal
Finding CR-1 of review-reminder (reviews/review-reminder/review.md): the server's WeeklyReviewItemResolver always
returns the English title "Weekly review", and the clients prefer the server title, so German users never see
"Wochenrückblick" (web: reminder-notifications.service.ts, push-sw.js; Android: ReminderNotifier.kt).
For item type WEEKLY_REVIEW the clients show their own translated title.

## Acceptance criteria
- [ ] Web (in-app notification/toast and service worker push) shows the localized title for WEEKLY_REVIEW reminders (unit tests, en + de)
- [ ] Android (local alarm and UnifiedPush) shows review_reminder_title for WEEKLY_REVIEW reminders, also when the server sends a title (unit tests, en + de)
- [ ] `sh scripts/check.sh` passes

## Notes
- ASSUMPTION (2026-11-09 09:49+0100 by dell26/agent-1): WEEKLY_REVIEW reminders always show the client's translated name (web i18n id reminders.weeklyReview, push-sw TEXTS.weeklyReview, Android review_reminder_title) and ignore any server title, since the server title is a fixed English label, not user data. Touched web/public/push-sw.js and android push/PushReminderDeliveryTest.kt outside the declared touches: the goal names push-sw.js and that test asserted the old keep-server-title behavior.
