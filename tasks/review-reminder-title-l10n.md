---
title: Weekly review reminder notification title in the client language
status: todo
owner:
deps: [review-reminder]
touches: [web/src/app/core/notifications,android/app/src/main/java/ch/sonensei/cockpit/reminders,android/app/src/test/java/ch/sonensei/cockpit/reminders]
prio: 2
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
