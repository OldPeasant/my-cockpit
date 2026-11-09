---
title: Weekly review reminder setting and scheduling (server)
status: todo
owner:
deps: [and-review-flow, and-review-steps, web-small-gaps]
prio: 3
parent: review-reminder
---
## Goal
Part of `review-reminder` (Weekly review reminder on a configurable weekday), which was split.
Goal: SPEC.md §8.6 weekly review reminder, delivered through the existing reminder pipeline (SSE, web push, UnifiedPush, Android alarms via /reminders/upcoming).
Design: setting `weeklyReviewReminder` in the review module (e.g. `review/infra/ReviewSettings.kt`), value "off" or "<DAY>@HH:mm" (DayOfWeek name, e.g. "SUNDAY@18:00"), default "off". Add `ItemType.WEEKLY_REVIEW` (core enum, one line) plus an ItemResolver in the review module for a fixed well-known item id: title "Weekly review", deepLink "/review", always exists. The review module keeps exactly one pending reminder for that item at the next occurrence in the user's time zone: recompute when the setting or timeZone changes and after it fires (a small scheduled job or ReminderFiredEvent listener), delete the pending one when "off".

## Acceptance criteria
- [ ] setting definition with validation (unit tests for parse: off, valid, invalid day/time)
- [ ] next-occurrence computation honours timeZone and DST (unit tests)
- [ ] pending reminder created/replaced/removed on setting change and re-armed after firing (Spring tests)
- [ ] regenerate api/openapi.yaml if the item type enum appears there; `cd server && ./gradlew check` passes

## Notes
- Work done on `review-reminder` before the split is on `origin/wip/review-reminder` (if it exists): merge it first if your part builds on it.
