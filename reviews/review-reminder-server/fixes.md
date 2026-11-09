# Fix Report

Date: 2026-11-09

Task: review-reminder-server - Weekly review reminder setting and scheduling (server)

Scope: Findings CR-1 (verification only) and CR-2 from `reviews/review-reminder-server/review.md`, as supplied by the parent worker. CR-3 (P3, doc-only, "no change required") was not in the supplied scope.

## Fixed findings

### CR-2 - The startup sync deletes a missed (overdue) weekly reminder before it can fire

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/review/domain/WeeklyReviewReminders.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/review/WeeklyReviewReminderTests.kt`

Problem:
`sync()` compared all pending reminders of the weekly review item with `next()`, which is always in the future. A pending reminder that was already due (for example, the server was down at fire time) was deleted and replaced with next week's, so it never fired. This happened at startup (before the scheduler's first run, 15 s initial delay) and also on a setting or time-zone change before the next poll.

Fix:
When the setting is not `off`, `sync()` now splits the pending reminders into due ones (`remindAt <= now`) and future ones. It keeps the latest due reminder for `ReminderScheduler` to fire and deletes any older due duplicates, so it fires once. It only replaces or dedupes the future ones, so exactly one future pending reminder exists at `next()`. When `off`, it still deletes every pending reminder. After the kept reminder fires, `onReminderFired` re-syncs, and the future reminder already matches. KDoc updated.

Regression tests (Spring, Testcontainers):
- `startup keeps a reminder that became due while the server was down, so it still fires`: arms Sunday 18:00, moves the clock to Sunday 18:10, then calls `onStartup()`. The missed reminder is still pending (same id), next Sunday's reminder is armed next to it, and a second `sync()` keeps it. Then `fireDue()` returns 1, the missed reminder has `fired_at` set, and only next Sunday's reminder is pending. This test would fail with the old code, which deleted the missed reminder.
- `turning it off also removes a pending reminder that is already due`.

Validation:
- `cd server && ./gradlew ktlintFormat` - Pass
- `cd server && ./gradlew test --tests '*WeeklyReview*'` - Pass (WeeklyReviewReminderTests 13/13, WeeklyReviewScheduleTests 20/20, WeeklyReviewEdgeCaseTests 27/27, WeeklyReviewWebTests 9/9)

## Not fixed

### CR-1 - The regenerated `ItemType` union breaks the web build

Status:
Invalid (already fixed, no change needed)

Reason:
Commit de3db3720 already fixed it: `item-type.ts` has the GLYPHS entry and the `itemTypeLabel` case, `item-type.spec.ts` has `LABELS` with `WEEKLY_REVIEW: 'Weekly review'`, and `messages.xlf`/`messages.de.xlf` have the i18n entries. `cd web && npm run check` passes (lint, unit tests and build; only the existing bundle-budget warning).

### Outside the supplied findings - 3 server tests fail in `./gradlew check`

Status:
Out of scope (requires a parent decision)

Reason:
These failures already exist before the CR-2 change. The task's own earlier commits added the setting and the item type, but these two core test classes still list the old values:
- `core/items/ItemResolverRegistryTests` > `ItemType has every type of SPEC section 4_3`: expects 11 types, actual includes `WEEKLY_REVIEW`.
- `core/settings/SettingsServiceTests` > `unset settings read as their defaults` and `a stored value its definition no longer accepts reads as the default`: expected key lists lack `weeklyReviewReminder`.
Acceptance criterion 4 (`./gradlew check` passes) and `.taskcheck` need these updated. That means adding `WEEKLY_REVIEW` (an ItemType beyond SPEC §4.3, covered by the task's recorded assumption) and `weeklyReviewReminder` to the expected lists. These were not supplied findings, so the parent worker decides.

## Validation summary

Focused tests:
- `cd server && ./gradlew test --tests '*WeeklyReview*'` - Pass (69 tests, 0 failures)

Full test suite:
- `cd server && ./gradlew ktlintFormat check` - Fail: 5245 tests, 3 failed (the 3 pre-existing failures listed above; none in the review module). ktlint made no changes beyond the fix.
- `cd web && npm run check` - Pass

Lint/type-check/build:
- Included in the commands above.

`.taskcheck`:
- Not run separately: the server part fails on the 3 pre-existing tests above.

Remaining unresolved findings:
0 of the supplied findings (CR-2 fixed, CR-1 already fixed). Separately, 3 failing tests outside the supplied findings need a parent decision.

## Final status

Fixes completed, but validation still fails (3 failing tests that existed before the fix and are outside the supplied findings; see "Not fixed").
