# Code Review

Date: 2026-11-09

Task: review-reminder-server - Weekly review reminder setting and scheduling (server)

Scope: Re-run on the final code after fixes. Committed changes `git diff 2d96f3ce6..HEAD -- server web api` (tasks/ and reviews/ ignored). Checked that the earlier findings CR-1 and CR-2 are fixed, then looked for remaining issues. Uncommitted working-tree edits to `WeeklyReviewReminderTests.kt` (a concurrent test-writer run adding tests) were seen but not reviewed as part of the scope.

Requirements:
- tasks/review-reminder-server.md acceptance criteria, design notes and recorded assumption
- SPEC.md §8.6 (weekly review reminder on a configurable weekday), §4.6 reminders
- AGENTS.md project notes (API changes regenerate the clients; `.taskcheck` covers every part)
- Previous review and fix reports: reviews/review-reminder-server/review.md (earlier revision), fixes.md
- Existing ReminderService / ReminderScheduler / SettingsService contracts

Git range:
- Branch: main
- HEAD: 9ad52c6b47f49222f66e3d41b525ac5159faa208
- Base/start: 2d96f3ce646fc9f1d032f721a7fc68a7f0f26d29
- Commits reviewed: 6 (6ee0cc105, 2c2e37dd3, a81515f71, a48e64622, 28b739d8e, 9ad52c6b4)

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## Verification of earlier findings

- CR-1 (web build broken by the regenerated `ItemType` union): fixed. `web/src/app/shared/links/item-type.ts` has the `WEEKLY_REVIEW` glyph and the `itemTypeLabel` case. `item-type.spec.ts` `LABELS` has the entry, and `messages.xlf`/`messages.de.xlf` have `itemType.weeklyReview`. No other exhaustive `Record<ItemType, ...>` exists in the web code (`note-markdown.ts` uses a non-exhaustive list). Android maps item types as strings with fallbacks, so the new value cannot break deserialization of `/reminders/upcoming`.
- CR-2 (startup sync deletes an overdue reminder): fixed. In `WeeklyReviewReminders.sync()` (`server/src/main/kotlin/ch/sonensei/cockpit/review/domain/WeeklyReviewReminders.kt:64-76`), while the setting is on, pending reminders with `remindAt <= now` are kept for the scheduler. `pendingOfItem` orders them `remindAt asc, id asc`, so `due.dropLast(1)` keeps the latest due one and removes older duplicates. Only future reminders are compared with `next()` and replaced. When the setting is off, everything pending is removed. After the kept reminder fires, `onReminderFired` re-syncs, and the existing future reminder already matches, so the sync changes nothing. The regression test `startup keeps a reminder that became due while the server was down, so it still fires` covers the scenario end to end, through `fireDue()` and re-arming. `turning it off also removes a pending reminder that is already due` covers the off path.
- Core test expectations: `ItemResolverRegistryTests` and `SettingsServiceTests` now include `WEEKLY_REVIEW` and `weeklyReviewReminder` (commit 9ad52c6b4). This resolves the three `./gradlew check` failures listed in fixes.md.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | Users can still create or delete WEEKLY_REVIEW reminders through the generic reminders API (carried over from the earlier CR-3, still unaddressed)**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/review/infra/WeeklyReviewItemResolver.kt:15-16`; `server/src/main/kotlin/ch/sonensei/cockpit/review/domain/WeeklyReviewReminders.kt:29-34`
    - Category: Correctness
    - Problem: The resolver reports the well-known item as existing. So `POST /api/v1/reminders` accepts extra reminders for it, and `DELETE /api/v1/reminders/{id}` can remove the managed one. The next `sync()` silently deletes extra future reminders. A deleted managed reminder is only re-armed at the next trigger (a setting or time-zone change, or a restart). The class KDoc promises "exactly one pending reminder" without mentioning this limit.
    - Why it matters: This is a small inconsistency with the design statement. The clients do not offer reminders on this item, so normal use does not hit it.
    - Suggested fix: Add one sentence to the `WeeklyReviewReminders` KDoc that documents the limit, or record it as accepted in quality.json. No code change is needed for this task.

## Review conclusion

No material issues found.

Notes: Setting validation (`SettingType.Formatted` plus `WeeklyReviewSchedule.parse`: strict `<DAY>@HH:mm`, upper-case day names, 00-23 hours, 00-59 minutes) matches criterion 1. `nextAfter` handles time zones and DST correctly: the 0..7 day window covers "today, time already passed"; `ZonedDateTime.of` shifts a time in a DST gap forward and uses the earlier offset in an overlap, as the recorded assumption says. That matches criterion 2. The advisory-lock serialization, the AFTER_COMMIT listeners in isolated `REQUIRES_NEW` transactions, and the Spring tests match criterion 3. The OpenAPI enum and the regenerated web client are consistent (criterion 4, part 1). I did not run any tests: a concurrent test-writer was editing `WeeklyReviewReminderTests.kt` in the working tree, and the parent runs `./gradlew check` for criterion 4. The committed HEAD has no test yet for the "several due reminders, keep only the latest" branch (`due.dropLast(1)`). The uncommitted test-writer edits add one (`of several due pending reminders, sync keeps only the latest`). Commit it with the final code.
