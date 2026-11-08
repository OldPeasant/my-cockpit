---
title: Android: inbox and New view rows complete and open tasks
status: in_progress
owner: dell26/agent-2
deps: []
touches: [android/app/src/main/java/ch/sonensei/cockpit/gtd/inbox,android/app/src/test/java/ch/sonensei/cockpit/gtd/inbox,android/app/src/main/java/ch/sonensei/cockpit/newitems,android/app/src/test/java/ch/sonensei/cockpit/newitems,android/app/src/main/res/values/strings_inbox.xml,android/app/src/main/res/values-de/strings_inbox.xml,android/app/src/main/res/values/strings_new.xml,android/app/src/main/res/values-de/strings_new.xml]
prio: 1
lease_until: 2026-11-08T10:22:05Z
extended: dell26/agent-2
---
## Goal
Android inbox list rows have no checkbox and no click (`InboxScreen.kt:234-240`); the New view opens a task in a
read-only sheet (`NewScreen.kt:488-536`) although SPEC.md §5.3 says it opens the task detail.

## Acceptance criteria
- [x] Inbox list rows have a complete checkbox (with the existing undo snackbar from Next) and tapping a row opens task detail
  - verified (2026-11-08 11:06+0100 by dell26/agent-2): InboxScreenTest/InboxRouteTest/InboxViewModelTest (Robolectric Compose) via ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.inbox.*' -> pass: checkbox completes via shared TaskCompleter with Next's undo snackbar, row tap opens task detail
- [x] A task row in the New view opens task detail (back returns to New with its filter and scroll position); the read-only `NewTaskView` is removed
  - verified (2026-11-08 11:06+0100 by dell26/agent-2): NewRouteTest in CockpitShellFrame via ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.newitems.*' -> pass: task row opens detail route, Back returns with filter and scroll kept; NewTaskView/NewOpen.Task removed (grep finds none)
- [ ] New strings are in English and German (`res/values/` + `res/values-de/`)
- [ ] Unit/Compose tests cover the new behavior; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass

## Notes
Planned 2026-11-08 from the owner's UX request (web/Android parity, mail images, back navigation, mail search); the parity inventory and decisions are in `docs/PARITY.md`.
- ASSUMPTION (2026-11-08 10:17+0100 by dell26/agent-2): Inbox row checkbox reuses the shared Next/Today strings (tasks_complete, tasks_completed, tasks_undo) and the shared TaskCompleter, so no new inbox strings were needed; the New view's now-unused new_task_created_at/new_task_hint strings were removed in en+de.
- ASSUMPTION (2026-11-08 10:17+0100 by dell26/agent-2): New view task rows open the shell task detail via LocalTaskOpener (no in-screen sheet). A saved 'task:<id>' open state from the old version is ignored (list shown). Inbox cache is not force-refreshed after completing from the Inbox: the completer hides the task by version until the next sync, same as Next/Today.
- ASSUMPTION (2026-11-08 10:49+0100 by dell26/agent-2): Supersedes the earlier 'Inbox cache is not force-refreshed' note: after review CR-1, InboxViewModel reloads TaskView.INBOX once a complete/undo/refused answer arrives from the server (not after offline failures); done in the Inbox view model to stay out of the shared gtd/next/TaskCompletion.kt.
- extended by dell26/agent-2: Implementation, tests, CR-1 fix done; tests.md/review.md/fixes.md committed; sh scripts/check.sh passes at a5a8ac8de. Left: decide P3 finding CR-1 (second review: offline decision read from shared snackbar) - fix or accept; re-run code-reviewer on final code (a5a8ac8de added tests after review scope 3a3ca26ec) and test-writer if code changes; record tasks.py verify for AC1-4; write+commit reviews/and-inbox-rows/quality.json; tasks.py done.
