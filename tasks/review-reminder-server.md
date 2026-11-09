---
title: Weekly review reminder setting and scheduling (server)
status: in_progress
owner: dell26/agent-2
deps: [and-review-flow, and-review-steps, web-small-gaps]
prio: 3
parent: review-reminder
lease_until: 2026-11-09T03:31:16Z
releases: 2
extended: 
---
## Goal
Part of `review-reminder` (Weekly review reminder on a configurable weekday), which was split.
Goal: SPEC.md §8.6 weekly review reminder, delivered through the existing reminder pipeline (SSE, web push, UnifiedPush, Android alarms via /reminders/upcoming).
Design: setting `weeklyReviewReminder` in the review module (e.g. `review/infra/ReviewSettings.kt`), value "off" or "<DAY>@HH:mm" (DayOfWeek name, e.g. "SUNDAY@18:00"), default "off". Add `ItemType.WEEKLY_REVIEW` (core enum, one line) plus an ItemResolver in the review module for a fixed well-known item id: title "Weekly review", deepLink "/review", always exists. The review module keeps exactly one pending reminder for that item at the next occurrence in the user's time zone: recompute when the setting or timeZone changes and after it fires (a small scheduled job or ReminderFiredEvent listener), delete the pending one when "off".

## Acceptance criteria
- [x] setting definition with validation (unit tests for parse: off, valid, invalid day/time)
  - verified (2026-11-09 03:43+0100 by dell26/agent-2): cd server && ./gradlew test --tests '*WeeklyReview*' -> WeeklyReviewScheduleTests (20) + EdgeCase (27) incl. parse off/valid/invalid day/time pass; invalid value via settings API -> 400 (WeeklyReviewWebTests)
- [x] next-occurrence computation honours timeZone and DST (unit tests)
  - verified (2026-11-09 03:43+0100 by dell26/agent-2): cd server && ./gradlew test --tests '*WeeklyReviewSchedule*' -> nextAfter tests for Europe/Zurich and other zones, DST gap (shift forward) and overlap (first occurrence) pass
- [x] pending reminder created/replaced/removed on setting change and re-armed after firing (Spring tests)
  - verified (2026-11-09 03:43+0100 by dell26/agent-2): cd server && ./gradlew test --tests '*WeeklyReviewReminderTests' (Spring, Postgres testcontainer) -> 15 pass: create/replace/off removes, timezone change moves, re-armed after fireDue, startup catch-up, overdue kept
- [x] regenerate api/openapi.yaml if the item type enum appears there; `cd server && ./gradlew check` passes
  - verified (2026-11-09 03:45+0100 by dell26/agent-2): api/openapi.yaml contains WEEKLY_REVIEW in ItemType enums and web client regenerated (grep); server/gradlew -p server check (incl. OpenApiContractTest, ktlint, all tests) at b08b3fd2d -> exit 0

## Notes
- Work done on `review-reminder` before the split is on `origin/wip/review-reminder` (if it exists): merge it first if your part builds on it.
- ASSUMPTION (2026-11-09 02:41+0100 by felix/agent-1): Weekly review reminder item uses fixed well-known id 00000000-0000-7000-8000-00000000a001 (ItemType.WEEKLY_REVIEW), icon event_repeat; DST gap shifts forward, overlap fires at first occurrence; schedule synced on settings change, after firing and at startup (no periodic job).
- release by felix/agent-1: Implemented: setting weeklyReviewReminder (ReviewSettings + SettingType.Formatted), WeeklyReviewSchedule parse/nextAfter (DST), WeeklyReviewReminders sync on settings/timezone change, after firing, at startup; ItemType.WEEKLY_REVIEW + resolver; openapi + web client regenerated; web glyph/label/i18n added (web npm run check passes). Tests + reviews/review-reminder-server/{tests.md,review.md} committed. LEFT: run fixer for review CR-2 (P1): onStartup sync deletes a pending reminder that is already due (server down at fire time) instead of letting the scheduler fire it -> in sync() keep pending reminders with remindAt <= now (only replace future ones), add Spring test. CR-1 already fixed in cf6d3e1ae (web). CR-3 doc-only. Then re-run test-writer + code-reviewer on final code, verify criteria, quality.json, done. Nothing verified yet via tasks.py verify.
- release by felix/agent-1: CR-2 fixed (commit 'fix(review): keep overdue weekly review reminder at sync', fixes.md committed; WeeklyReview* tests 69 pass, web check passes). LEFT: ./gradlew check has 3 failing core tests whose expected lists lack the new values: ItemResolverRegistryTests 'ItemType has every type of SPEC section 4_3' (add WEEKLY_REVIEW) and SettingsServiceTests 'unset settings read as their defaults' + 'a stored value its definition no longer accepts...' (add weeklyReviewReminder). Then re-run test-writer + code-reviewer on final code, verify criteria 1-4, quality.json, done.
- ASSUMPTION (2026-11-09 03:55+0100 by dell26/agent-2): Android unit tests (ReminderUiTest, TrashModelsTest) adjusted for the new WEEKLY_REVIEW enum value without changing Android production code: reminder still opens Dashboard until review-reminder-android maps it; trash skips the type since the well-known item is never trashed
