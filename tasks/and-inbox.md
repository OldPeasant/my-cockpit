---
title: Android inbox processing
status: in_progress
owner: dell26/agent-4
deps: [and-gtd-data]
touches: [android/app/src/main/java/ch/sonensei/cockpit/gtd/inbox,android/app/src/test/java/ch/sonensei/cockpit/gtd/inbox]
prio: 1
lease_until: 2026-10-21T08:50:34Z
extended: dell26/agent-4
---
## Goal
Process the inbox on the phone (SPEC.md §5.3).

## Acceptance criteria
- [ ] Inbox list + one-at-a-time processing with the same actions as web (except calendar/reference until later), setting energy/creativity/contexts/estimate
- [ ] `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.inbox.*'` passes, covering each action calls the repository; offline state disables actions (Robolectric/Compose UI tests for screens)

## Notes
- ASSUMPTION (2026-10-21 09:51+0200 by dell26/agent-4): Android inbox omits 'To reference' and 'To calendar' (task says: except calendar/reference until later); actions shown: Do it, Next, To project, Waiting, Schedule, Tickler, Someday, Trash.
- ASSUMPTION (2026-10-21 09:51+0200 by dell26/agent-4): Clarification PATCH uses a hand-written Retrofit interface (InboxTaskPatchApi, JsonObject body) because the generated TaskPatchRequest serializes every null field, which the server treats as 'clear'; sending it would wipe due date/notes/recurrence.
- ASSUMPTION (2026-10-21 09:51+0200 by dell26/agent-4): Inbox strings live in a new res/values/strings_inbox.xml (outside the task's touches, like strings_share.xml for capture); no other file outside gtd/inbox changed.
- ASSUMPTION (2026-10-21 09:51+0200 by dell26/agent-4): Dates on the phone: quick chips (tomorrow / in a week / in a month) plus a Material3 DatePickerDialog; 'To project' sets the item as next step when the project is stalled after the move (as the web does); a failed next-step call still counts as processed.
- ASSUMPTION (2026-10-21 09:56+0200 by dell26/agent-4): InboxScreen falls back to the placeholder when not hosted in a Hilt activity (LocalActivity is not GeneratedComponentManagerHolder): the shell's navigation tests use a plain ComponentActivity, where hiltViewModel() throws. Other real screens can reuse this pattern.
- extended by dell26/agent-4: Implementation, first review and fixes (CR-1/2/4) are committed. Left: inbox tests run 90 with 1 failing: InboxRepositoryTest 'a malformed project answer after the move still counts as processed' (Rejected(status=null) instead of Processed). Decide whether the repository or the test is wrong and fix it. Then run test-writer and code-reviewer again on the final code (the reports are stale after the fixes), write quality.json, verify criteria 1 and 2, .taskcheck, done.
