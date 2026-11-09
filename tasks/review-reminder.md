---
title: Weekly review reminder on a configurable weekday
status: in_progress
owner: felix/agent-1
deps: [and-review-flow,and-review-steps,web-small-gaps]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/review,server/src/test/kotlin/ch/sonensei/cockpit/review,web/src/app/features/settings/general,web/src/app/features/review,android/app/src/main/java/ch/sonensei/cockpit/review,android/app/src/test/java/ch/sonensei/cockpit/review,web/src/locale,android/app/src/main/res/values/strings_review.xml,android/app/src/main/res/values-de/strings_review.xml]
prio: 3
lease_until: 2026-11-09T01:50:43Z
---
## Goal
SPEC.md §8.6: a weekly review reminder on a configurable weekday; today web only highlights the review after
`reviewDueDays`.

## Acceptance criteria
- [ ] A setting "weekly review reminder: weekday + time / off" (server setting definition + web settings control)
- [ ] The server creates the reminder notification at that time (existing reminder delivery: web push, UnifiedPush/alarms)
- [ ] Tapping it opens the review on web and Android
- [ ] New strings in English and German on both clients (`web/src/locale`, `res/values{,-de}/`)
- [ ] Unit tests cover the new behavior on each changed part; `sh scripts/check.sh` passes

## Notes
Planned 2026-11-08 from the owner's UX request (web/Android parity, mail images, back navigation, mail search); the parity inventory and decisions are in `docs/PARITY.md`.
- ASSUMPTION (2026-11-09 02:31+0100 by felix/agent-1): Reminder delivered via existing reminder table with new ItemType.WEEKLY_REVIEW + review-module resolver (deepLink /review) so SSE/web push/UnifiedPush/Android alarms work unchanged; setting weeklyReviewReminder = 'off' | '<DAY>@HH:mm', default off
