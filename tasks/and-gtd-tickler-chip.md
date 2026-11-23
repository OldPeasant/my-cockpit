---
title: Android: Tickler as list choice, 'new today' marker in Next
status: in_progress
owner: dell26/agent-1
deps: []
touches: [SPEC.md,android/app/src/main/java/ch/sonensei/cockpit/gtd/task,android/app/src/main/java/ch/sonensei/cockpit/gtd/next,android/app/src/main/java/ch/sonensei/cockpit/gtd/inbox,android/app/src/test/java/ch/sonensei/cockpit/gtd,android/app/src/main/res/values/strings_task_detail.xml,android/app/src/main/res/values-de/strings_task_detail.xml,android/app/src/main/res/values/strings_inbox.xml,android/app/src/main/res/values-de/strings_inbox.xml,android/app/src/main/res/values/strings_next_today.xml,android/app/src/main/res/values-de/strings_next_today.xml]
prio: 2
lease_until: 2026-11-23T18:08:51Z
releases: 1
extended: 
---
## Goal
Two usability problems in the Android GTD views, found in daily use:

1. **Tickler is hard to find and can be combined with a list that it overrides.** The task edit form shows the
   lists Inbox / Next / Waiting For / Scheduled / Someday as chips, plus a separate date field "Comes back"
   ("Kommt zurück"), which is the tickler. A tickler date overrides the selected list: the task is hidden
   everywhere and goes to the Inbox on that date. The server clears the scheduled date and waiting-for fields then
   (`TaskService.releaseDue`). So you can set "Scheduled" + "Comes back", and the Scheduled choice has no effect
   without any hint. The feature also has three names: "Tickler"/"Wiedervorlage" in Inbox processing and
   "Comes back"/"Kommt zurück" in the form.
2. **Scheduled tasks that come back on their date get lost in a long Next list.** On its scheduled date a task moves
   from SCHEDULED to NEXT, and the server sets `releasedOn` = that day (already in the API). The Next view shows it
   like any other next action, so it is easy to miss.

Decisions (made by the owner, 2026-11-23): Tickler becomes a choice among the list chips; Scheduled tasks keep going
to Next (no change to the server) but are marked "new today" there. Server and API stay unchanged. The web client
gets the same change in its own task (`web-gtd-tickler-chip`).

## Acceptance criteria
- [x] SPEC.md §5.1/§5.3 describes both rules: in the task edit form, Tickler is a list choice ("Inbox · Next · Waiting For ·
      Scheduled · Tickler · Someday") and stores `list = INBOX` + `ticklerDate`; the Next Actions view marks tasks
      released from Scheduled today (`releasedOn == today`) as "new today" and lists them first
  - verified (2026-11-23 18:43+0100 by dell26/agent-1): git diff c4b38a016..HEAD -- SPEC.md -> §5.1 adds 'Released from Scheduled' row (releasedOn, marked new today); §5.3 Next Actions view: releasedOn == today marked 'New today' and listed first; Task edit form: Inbox · Next · Waiting For · Scheduled · Tickler · Someday, Tickler stores list=INBOX + ticklerDate
- [x] Task edit form: the list chips are Inbox, Next, Waiting For, Scheduled, **Tickler**, Someday. Picking Tickler
      requires a future date (the same picker/validation as before) and saves `list = INBOX` + that `ticklerDate`
      through the existing move API. The separate "Comes back" field is gone
  - verified (2026-11-23 18:45+0100 by dell26/agent-1): cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.*' at d67981b30 -> 1279 passed; TaskDetailListFieldsTest (chip order Inbox/Next/Waiting/Scheduled/Tickler/Someday, Tickler date label, no Comes back), TaskDraftListTest (onTickler -> TaskMove(INBOX, ticklerDate), NotFuture/Required)
- [x] A task with a `ticklerDate` opens in the form with the Tickler chip selected and its date shown, whatever its
      stored list is (the tickler wins, as on the server). Picking another chip clears the tickler date; picking
      Scheduled still asks for the scheduled date. Saving without changes doesn't move or change the task
  - verified (2026-11-23 18:45+0100 by dell26/agent-1): same run -> TaskDraftListTest (tickled task opens with Tickler on all 5 lists, unchanged save moves nothing, other chip clears date, Scheduled requires date), TaskDetailListFieldsTest Compose + TaskDetailViewModelRelistTest pass
- [x] One name everywhere in the Android app: "Tickler" (EN) / "Wiedervorlage" (DE) for the form chip, the date
      label, the task detail property and the Inbox processing action. The hint explains it ("Hidden until then; on
      that day it comes back to the Inbox." / German equivalent)
  - verified (2026-11-23 18:45+0100 by dell26/agent-1): same run -> TaskDetailStringsTest (Tickler/Wiedervorlage in form, detail, inbox; shared hint EN/DE), TaskDetailContentTest, InboxScreenTest tickler prompt title+hint pass
- [x] Next Actions view: open NEXT tasks with `releasedOn` == today (user's local date) show a visible "New today" /
      "Neu heute" marker (text label, not color only; accessible content description) and are listed first, in their
      normal order among themselves; from the next day on they show and sort like any other task. Filters and
      presets still apply to them
  - verified (2026-11-23 18:45+0100 by dell26/agent-1): same run -> NextViewModelNewTodayTest (released today first in stable order, filters/presets apply, next day normal, local date Europe/Zurich), NextNewTodayTest, NextScreenTest marker text + content description pass
- [x] All new UI strings exist in English and German; unit/Compose tests cover the criteria above;
      `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` passes
  - verified (2026-11-23 18:52+0100 by dell26/agent-1): cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug on 0538a0c0d -> BUILD SUCCESSFUL, EXIT 0; EN+DE strings asserted by TaskDetailStringsTest/InboxScreenTest; criteria covered by TaskDetailListFieldsTest, TaskDraftListTest, TaskDetailViewModelRelistTest, NextViewModelNewTodayTest (reviews/and-gtd-tickler-chip/tests.md)

## Notes
- Form: `android/app/src/main/java/ch/sonensei/cockpit/gtd/task/TaskDetailListFields.kt` (`FORM_LISTS`, `ListFields`),
  `TaskDetailRepository.kt` (`TaskMove`), `TaskDetailScreen.kt` (property "tickler"). Inbox action:
  `gtd/inbox/InboxScreen.kt`. Next: `gtd/next/NextScreen.kt`, `NextViewModel.kt`, `TaskListUi.kt`.
  `releasedOn` is already in the cached model (`gtd/data/GtdModels.kt`).
- `TaskList` has no TICKLER value and the API doesn't get one: Tickler is a form choice only.
- The calendar overlay's "Back from tickler" label may stay.
- ASSUMPTION (2026-11-23 18:18+0100 by dell26/agent-1): Android form: the Tickler date field shows only while the Tickler chip is chosen; the scheduled date field stays always visible (setting a scheduled date picks Scheduled and clears the tickler). A tickler date restored on a task stored on another list saves to INBOX only when the date changes; unchanged it moves nothing. Inbox tickler prompt title became 'Tickler: hidden until' plus the shared hint.
- release by dell26/agent-1: Implemented: TaskDraft.tickler/onTickler/targetList (TaskDetailViewModel.kt), Tickler chip between Scheduled and Someday, tickler date field only for Tickler, strings EN/DE renamed to Tickler/Wiedervorlage, inbox prompt hint, Next 'New today' marker (TaskRow newToday, isNewToday/newTodayFirst in NextViewModel.kt), SPEC 5.1/5.3, new tests (TaskDetailListFieldsTest tickler section, NextNewTodayTest, NextScreenTest marker) pass. LEFT: 8 old tests encode the replaced behaviour (tickler combined with a list) and must be rewritten to the new rules: TaskDraftListTest (5: tickler tests) and TaskDetailViewModelRelistTest (3); see .tmp-less: run android/gradlew -p android testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.*'. Then quality gate (test-writer, code-reviewer), verify criteria, done. Nothing verified yet.
