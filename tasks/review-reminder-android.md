---
title: Weekly review reminder opens the review on Android
status: in_progress
owner: dell26/agent-2
deps: [and-review-flow, and-review-steps, web-small-gaps, review-reminder-server]
prio: 3
parent: review-reminder
lease_until: 2026-11-09T03:41:19Z
---
## Goal
Part of `review-reminder` (Weekly review reminder on a configurable weekday), which was split.
Goal: an Android reminder notification (local alarm or UnifiedPush) for item type WEEKLY_REVIEW opens the review screen; title fallback string "Weekly review" in strings_review.xml (en + de).

## Acceptance criteria
- [x] ReminderOpenRequests/navigation maps WEEKLY_REVIEW to the review destination (unit test)
  - verified (2026-11-09 04:25+0100 by dell26/agent-2): cd android && ./gradlew testDebugUnitTest -> BUILD SUCCESSFUL; ReminderUiTest destination table (WEEKLY_REVIEW -> CockpitDestination.Review), ReminderAlarmsNotifierTest 'a weekly review reminder without a title ... opens the review' and PushReminderDeliveryTest 'a pushed weekly review reminder ... a tap opens the review' passed (16/16, 24/24, 0 failures)
- [x] English and German strings in res/values{,-de}/strings_review.xml
  - verified (2026-11-09 04:25+0100 by dell26/agent-2): review_reminder_title in android/app/src/main/res/values/strings_review.xml ('Weekly review') and values-de ('Wochenrückblick'); ./gradlew testDebugUnitTest -> 'in German ... is called Wochenrückblick' (@Config qualifiers=de) and English title tests passed
- [x] `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` passes
  - verified (2026-11-09 04:25+0100 by dell26/agent-2): cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport -> BUILD SUCCESSFUL in 7m21s

## Notes
- Work done on `review-reminder` before the split is on `origin/wip/review-reminder` (if it exists): merge it first if your part builds on it.
