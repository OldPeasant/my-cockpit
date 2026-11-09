# Code Review

Date: 2026-11-09

Task: review-reminder-android - Weekly review reminder opens the review on Android

Scope: committed task changes `git diff 81c26c97b..bde783c82` (uncommitted working-tree changes not reviewed)

Requirements:
- tasks/review-reminder-android.md acceptance criteria
- SPEC.md, docs/ARCHITECTURE.md, AGENTS.md project conventions (i18n en + de, lint, feature folders)

Git range:
- Branch: main
- HEAD: bde783c827ba7861e5674b5a0aff066b07e7f1cf
- Base/start: 81c26c97b3139ac4e6b0ce7a3985d4f99cc388ca
- Commits reviewed: 1

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Notes on verification

- AC1: `OpenItemRequest.destination` maps `WEEKLY_REVIEW` to `CockpitDestination.Review`
  (`ReminderOpenRequests.kt`). `ReminderUiTest` covers the mapping table, with the former Dashboard fallback
  expectation replaced by Review. `ReminderAlarmsNotifierTest` adds an end-to-end Robolectric test: notification
  title, then content intent, then `ReminderOpenRequests.offer`, then destination Review. Both notification paths
  (local alarm via `ReminderScheduler` and UnifiedPush via `PushReminderDelivery.toFired`, then
  `scheduler.showFiredByServer`) build `ScheduledReminder` from `item.type.value` and use the same
  `ReminderNotifier`, so the mapping applies to both. `WEEKLY_REVIEW` is in the reminder item type enum in
  `api/openapi.yaml`, which the Android client is generated from.
- AC2: `review_reminder_title` is present in `res/values/strings_review.xml` ("Weekly review") and
  `res/values-de/strings_review.xml` ("Wochenrückblick"). The fallback applies only when the server sends no
  title, through `untitledTitle(itemType)`.
- AC3: the Gradle run was not repeated in this review. The parent worker records that evidence.
- The changes stay inside the reminders feature folder and the review strings file. There are no new
  dependencies, secrets or logging.

## Review conclusion

No material issues found.
