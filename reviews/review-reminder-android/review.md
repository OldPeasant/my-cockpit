# Code Review

Date: 2026-11-09

Task: review-reminder-android - Weekly review reminder opens the review on Android

Scope: committed task changes `git diff 81c26c97b..0f7b8f3b2` (final revision 0f7b8f3b21aebc67d4083e2dbfa97ecdad2990dd)

Requirements:
- tasks/review-reminder-android.md acceptance criteria
- SPEC.md, docs/ARCHITECTURE.md, AGENTS.md project conventions (i18n en + de, lint, feature folders, fictitious test data)

Git range:
- Branch: main
- HEAD: 0f7b8f3b21aebc67d4083e2dbfa97ecdad2990dd
- Base/start: 81c26c97b3139ac4e6b0ce7a3985d4f99cc388ca
- Commits reviewed: 2 (bde783c82 implementation, 0f7b8f3b2 tests and helper reports)

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
  (`ReminderOpenRequests.kt`). The following tests cover it:
  - the mapping table in `ReminderUiTest`;
  - a Robolectric local-alarm test in `ReminderAlarmsNotifierTest` (title, then content intent, then offer, then
    destination Review);
  - since 0f7b8f3b2, a UnifiedPush test in `PushReminderDeliveryTest`, which takes the server JSON with type
    `WEEKLY_REVIEW` through delivery, the shown notification, the tap intent and finally destination Review.

  Both notification paths build `ScheduledReminder` from `item.type.value` and use the same `ReminderNotifier`.
  `WEEKLY_REVIEW` is in the reminder item type enum in `api/openapi.yaml`, which the Android client is generated
  from.
- AC2: `review_reminder_title` is present in `res/values/strings_review.xml` ("Weekly review") and
  `res/values-de/strings_review.xml` ("Wochenrückblick"). The fallback applies only when the server sends no
  title. The new tests cover:
  - the German locale (`@Config(qualifiers = "de")`);
  - a server-provided title being kept;
  - other or mis-cased item types keeping the generic untitled string.
- AC3: the Gradle run was not repeated in this review. The parent worker records that evidence.
- The second commit changes only tests and review reports. Production code is unchanged since bde783c82. Test
  data is fictitious. The new tests reset the shared `ReminderOpenRequests` state via `take`, as the existing
  tests do. There are no new dependencies, secrets or logging.

## Review conclusion

No material issues found.
