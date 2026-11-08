---
title: Android: inbox and New view rows complete and open tasks
status: in_progress
owner: dell26/agent-2
deps: []
touches: [android/app/src/main/java/ch/sonensei/cockpit/gtd/inbox,android/app/src/test/java/ch/sonensei/cockpit/gtd/inbox,android/app/src/main/java/ch/sonensei/cockpit/newitems,android/app/src/test/java/ch/sonensei/cockpit/newitems,android/app/src/main/res/values/strings_inbox.xml,android/app/src/main/res/values-de/strings_inbox.xml,android/app/src/main/res/values/strings_new.xml,android/app/src/main/res/values-de/strings_new.xml]
prio: 1
lease_until: 2026-11-08T09:51:52Z
---
## Goal
Android inbox list rows have no checkbox and no click (`InboxScreen.kt:234-240`); the New view opens a task in a
read-only sheet (`NewScreen.kt:488-536`) although SPEC.md §5.3 says it opens the task detail.

## Acceptance criteria
- [ ] Inbox list rows have a complete checkbox (with the existing undo snackbar from Next) and tapping a row opens task detail
- [ ] A task row in the New view opens task detail (back returns to New with its filter and scroll position); the read-only `NewTaskView` is removed
- [ ] New strings are in English and German (`res/values/` + `res/values-de/`)
- [ ] Unit/Compose tests cover the new behavior; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass

## Notes
Planned 2026-11-08 from the owner's UX request (web/Android parity, mail images, back navigation, mail search); the parity inventory and decisions are in `docs/PARITY.md`.
- ASSUMPTION (2026-11-08 10:17+0100 by dell26/agent-2): Inbox row checkbox reuses the shared Next/Today strings (tasks_complete, tasks_completed, tasks_undo) and the shared TaskCompleter, so no new inbox strings were needed; the New view's now-unused new_task_created_at/new_task_hint strings were removed in en+de.
- ASSUMPTION (2026-11-08 10:17+0100 by dell26/agent-2): New view task rows open the shell task detail via LocalTaskOpener (no in-screen sheet). A saved 'task:<id>' open state from the old version is ignored (list shown). Inbox cache is not force-refreshed after completing from the Inbox: the completer hides the task by version until the next sync, same as Next/Today.
