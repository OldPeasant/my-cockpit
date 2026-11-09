---
title: Weekly review reminder control in web settings
status: in_progress
owner: dell26/agent-2
deps: [and-review-flow, and-review-steps, web-small-gaps, review-reminder-server]
prio: 3
parent: review-reminder
lease_until: 2026-11-09T04:11:40Z
---
## Goal
Part of `review-reminder` (Weekly review reminder on a configurable weekday), which was split.
Goal: web settings "General" gets a "Weekly review reminder" control: off, or weekday + time, saved to the `weeklyReviewReminder` setting from review-reminder-server. Tapping the reminder notification opens /review (via the resolver's deepLink; check the reminder notification service handles the WEEKLY_REVIEW item type).

## Acceptance criteria
- [x] control in web/src/app/features/settings/general with unit tests (load, save off, save day+time)
  - verified (2026-11-09 04:50+0100 by dell26/agent-2): cd web && npx ng test --watch=false --include src/app/features/settings/general/general-settings.spec.ts (weekly review reminder block: shows stored day+time, off/unset, saves off, saves day with default time then changed time, empty time not saved, refused save restores, concurrency/edge cases) -> 2 files 92 passed incl. reminder-notifications spec
- [x] reminder notification for WEEKLY_REVIEW navigates to /review (unit test)
  - verified (2026-11-09 04:50+0100 by dell26/agent-2): cd web && npx ng test --include src/app/core/notifications/reminder-notifications.service.spec.ts: WEEKLY_REVIEW reminder (deepLink /review from server WeeklyReviewItemResolver) -> notification click and toast Open both navigateByUrl('/review'), passed
- [ ] English and German strings in web/src/locale; `cd web && npm run check` passes

## Notes
- Work done on `review-reminder` before the split is on `origin/wip/review-reminder` (if it exists): merge it first if your part builds on it.
- ASSUMPTION (2026-11-09 04:50+0100 by dell26/agent-2): Weekly review reminder control: a weekday select with 'Off' plus a time input shown once a day is picked; first switch-on offers 18:00; the time is kept while off; stored values that are not <DAY>@HH:mm show as Off.
- ASSUMPTION (2026-11-09 04:50+0100 by dell26/agent-2): German term for weekly review in the reminder row: 'Wochenrückblick' (as used elsewhere in messages.de.xlf).
