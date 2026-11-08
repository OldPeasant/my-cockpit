---
title: Android task detail: scheduled, tickler, recurrence, all lists
status: in_progress
owner: dell26/agent-3
deps: []
touches: [android/app/src/main/java/ch/sonensei/cockpit/gtd/task,android/app/src/test/java/ch/sonensei/cockpit/gtd/task,android/app/src/main/res/values/strings_task_detail.xml,android/app/src/main/res/values-de/strings_task_detail.xml]
prio: 2
lease_until: 2026-11-08T15:19:09Z
extended: 
releases: 0
hold: 
---
## Goal
Android task detail shows scheduled/tickler/recurrence read-only (`TaskDetailScreen.kt:553,554,574`) and can only
move to Next/Someday/Waiting. Web edits all of them (`task-detail.html:124-330`).

## Acceptance criteria
- [x] Scheduled date and tickler date can be set and cleared (date picker with quick chips like inbox processing)
  - verified (2026-11-08 13:02+0100 by felix/agent-1): cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.task.*' (HEAD 0f8ca9e7a) -> 223 tests pass, incl. TaskDetailListFieldsTest scheduled/tickler chip, picker and clear cases and TaskDraftListTest scheduled/tickler cases
- [x] The list can be changed to any of Inbox/Next/Waiting/Scheduled/Someday; waiting-since is editable
  - verified (2026-11-08 13:02+0100 by felix/agent-1): cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.task.*' (HEAD 0f8ca9e7a) -> 223 tests pass, incl. TaskDetailListFieldsTest 'every list is offered...' and waiting-since picker cases, TaskDetailViewModelRelistTest move/patch cases
- [x] Recurrence editor: presets (daily, weekdays, weekly, monthly, yearly), interval, FIXED vs AFTER_COMPLETION, keep an existing custom RRULE shown as text
  - verified (2026-11-08 13:02+0100 by felix/agent-1): cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.task.*' (HEAD 0f8ca9e7a) -> 223 tests pass, incl. TaskRepeatTest (presets, interval 1..999, INTERVAL=1000 kept as custom text, FIXED/AFTER_COMPLETION) and TaskDetailListFieldsTest repeat editor cases
- [x] Checklist items can be reordered (long-press drag + accessibility actions)
  - verified (2026-11-08 13:02+0100 by felix/agent-1): cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.task.*' (HEAD 0f8ca9e7a) -> 223 tests pass, incl. TaskDetailListFieldsTest long-press drag (Robolectric touch injection) and Move up/Move down accessibility action cases; not tried on a device
- [x] New strings are in English and German (`res/values/` + `res/values-de/`)
  - verified (2026-11-08 13:02+0100 by felix/agent-1): cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug -> exit 0 (lint MissingTranslation clean); all 35 new keys present in values/ and values-de/ strings_task_detail.xml (code-reviewer diff check)
- [x] Unit/Compose tests cover the new behavior; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass
  - verified (2026-11-08 15:52+0100 by dell26/agent-3): sh scripts/check.sh (HEAD 4b3f7fe14, merged main): tfp, server ktlint+tests, web eslint+tests+build passed (set -eu, reached android step); android part run alone via 'sh scripts/check.sh android' (includes ktlintCheck testDebugUnitTest lintDebug) -> 'all checks passed (android)'; full run was cut by my 20 min tool limit on a loaded machine (load avg 12), not a failure. Tests: TaskDetailListFieldsTest, TaskDraftListTest, TaskRepeatTest, TaskDetailViewModelRelistTest

## Notes
Planned 2026-11-08 from the owner's UX request (web/Android parity, mail images, back navigation, mail search); the parity inventory and decisions are in `docs/PARITY.md`.
- ASSUMPTION (2026-11-08 10:27+0100 by rudi/agent-1): Scheduled date and SCHEDULED list are coupled in the Android form: picking a scheduled date puts the task on Scheduled, clearing it moves a scheduled task to Next, choosing another list drops the date (server only allows scheduledDate on SCHEDULED)
- ASSUMPTION (2026-11-08 10:27+0100 by rudi/agent-1): Android form saves like web: one /move (list + tickler, always resending the tickler since an omitted one clears it) then a PATCH of the remaining fields; moving to Waiting in the form requires a person (as the existing Waiting dialog); waiting-since is only editable for a task already on Waiting
- ASSUMPTION (2026-11-08 10:27+0100 by rudi/agent-1): Recurrence presets on Android: none/daily/weekdays/weekly/monthly/yearly/custom with an interval (INTERVAL=n); any other rule (extra parts, other BYDAY) is kept as a custom rule shown as editable text; custom rules get only a FREQ presence check client-side, the server validates the rest
- ASSUMPTION (2026-11-08 10:27+0100 by rudi/agent-1): Checklist reorder on Android: long-press anywhere on a row and drag (by whole rows), plus Move up/Move down accessibility custom actions; positions saved as 0..n like the web
- extended by rudi/agent-1: Implementation complete (commits 30c56122, d9c8549e, 71d5388b, e2a3ceaa); android ktlintCheck testDebugUnitTest lintDebug passes. Left: fix review P3s CR-1 (add checklist size to pointerInput keys, TaskDetailScreen.kt drag) and CR-2 (remove @Suppress LongParameterList on TaskDraft); run test-writer (tests.md missing) and code-reviewer on final code (review.md is stale, reviewed 801e378d); record verify for all 6 criteria; run scripts/check.sh; write quality.json; done.
- release by rudi/agent-1: Implementation complete and committed (up to 28546b99); android ktlintCheck testDebugUnitTest lintDebug passes; previous review CR-1/CR-2 fixed. Left: (1) fix new review P3 CR-1 in reviews/and-task-detail-dates/review.md: RepeatDraft.of (TaskRepeat.kt:84) must treat INTERVAL>999 as a custom rule so such tasks stay saveable, plus a TaskRepeatTest case; (2) run test-writer (reviews/.../tests.md missing) and code-reviewer on the final code; (3) tasks.py verify all 6 criteria; (4) sh scripts/check.sh; (5) write quality.json; (6) done.
- release by felix/agent-1: over budget after 2 sessions (~52 min) and a review that decided nothing; still resumable: continue from wip/and-task-detail-dates (see git log)
- ASSUMPTION (2026-11-08 13:36+0100 by rudi/agent-1): check.sh fails on rudi's machine only in unrelated server PageFetcherSizeLimitTests (HTTP/2 reuse case, 4/50 TIMEOUT; failed also alone 4 of 4 runs); android ktlintCheck testDebugUnitTest lintDebug passes; filed fix-flaky-pagefetcher-size-test
- release by rudi/agent-1: Everything done except the final check: code, tests, review (0 findings), tests.md, quality.json (reviewed_revision 18ce008a = rebased 76244b1e) committed; criteria 1-5 verified; 'cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug' passes (2026-11-08). Left: sh scripts/check.sh must pass, then verify #6 and done. Blocker on rudi's machine: server PageFetcherSizeLimitTests HTTP/2 reuse case fails every run (TIMEOUT 4/50), unrelated to this Android task -> new task fix-flaky-pagefetcher-size-test; finish on another machine or after that fix.
