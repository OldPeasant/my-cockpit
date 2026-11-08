---
title: Android weekly review: waiting, projects, next, someday steps
status: in_progress
owner: dell26/agent-1
deps: [and-review-flow]
touches: [android/app/src/main/java/ch/sonensei/cockpit/review,android/app/src/test/java/ch/sonensei/cockpit/review,android/app/src/main/res/values/strings_review.xml,android/app/src/main/res/values-de/strings_review.xml]
prio: 2
lease_until: 2026-11-08T17:50:33Z
releases: 1
extended: 
---
## Goal
The action steps of the weekly review on Android, matching web `steps/waiting-step.ts`, `projects-step.ts`,
`next-actions-step.ts`, `someday-step.ts`.

## Acceptance criteria
- [x] Waiting: keep / follow up / done per item
  - verified (2026-11-08 18:22+0100 by dell26/agent-1): cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.review.*' --tests 'ch.sonensei.cockpit.gtd.projects.*' -> BUILD SUCCESSFUL, 0 failures; ReviewActionStepsTest 'waiting for - keep, follow up or complete', ReviewDecisionsViewModelTest still waiting/done/follow up, ReviewActionsTest follow-up/complete requests pass
- [x] Projects: add next step, change status, keep; rows open project detail
  - verified (2026-11-08 18:22+0100 by dell26/agent-1): cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.review.*' --tests 'ch.sonensei.cockpit.gtd.projects.*' -> BUILD SUCCESSFUL, 0 failures; ReviewActionStepsTest projects title opens project / next-step field / status menu, ReviewDecisionsViewModelTest projects + add next step, ProjectOpenRequestsTest (detail opened) pass
- [x] Next actions older than 30 days: keep / move to someday / delete
  - verified (2026-11-08 18:22+0100 by dell26/agent-1): cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.review.*' -> BUILD SUCCESSFUL, 0 failures; ReviewActionStepsTest 'next actions - keeps, moves to someday or deletes it', ReviewDecisionsViewModelTest next actions keep/someday/delete pass
- [x] Someday (tasks and projects): activate / keep / delete
  - verified (2026-11-08 18:22+0100 by dell26/agent-1): cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.review.*' -> BUILD SUCCESSFUL, 0 failures; ReviewActionStepsTest 'someday - tasks and projects are activated, kept or deleted', ReviewDecisionsViewModelTest someday, ReviewActionsTest task vs project routing pass
- [x] New strings are in English and German (`res/values/` + `res/values-de/`)
  - verified (2026-11-08 18:22+0100 by dell26/agent-1): strings_review.xml in values/ and values-de/; ReviewActionStepsTest 'every review string has a German translation and no German string is orphaned' and 'in German the steps are translated' pass (gradlew testDebugUnitTest)
- [x] Unit/Compose tests cover the new behavior; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass
  - verified (2026-11-08 18:32+0100 by dell26/agent-1): cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug -> exit 0, 4200 tests 0 failures (test-writer run at 125c22729); sh scripts/check.sh -> exit 0, all checks passed; task tests ReviewActionsTest 19, ReviewDecisionsViewModelTest 24, ReviewActionStepsTest 17, ProjectOpenRequestsTest 5

## Notes
Planned 2026-11-08 from the owner's UX request (web/Android parity, mail images, back navigation, mail search); the parity inventory and decisions are in `docs/PARITY.md`.
- ASSUMPTION (2026-11-08 16:49+0100 by felix/agent-1): Project rows open the detail via a Hilt @Singleton ProjectOpenRequests (gtd/projects) that ProjectsViewModel takes on creation or while kept; this touches gtd/projects outside the task's touches, kept minimal (one class, one init block, one constructor param).
- ASSUMPTION (2026-11-08 16:49+0100 by felix/agent-1): Delete in the next-actions and someday steps moves to the trash without a confirmation dialog, matching the web wizard (trash is recoverable).
- ASSUMPTION (2026-11-08 16:49+0100 by felix/agent-1): Follow up creates a Next task titled like the Waiting list's follow-up (waiting_follow_up_title[_who]) in the waiting task's project, matching web followUpTask(); the waiting task itself stays.
- release by felix/agent-1: over budget after 2 sessions (~54 min) and a review that decided nothing; still resumable: continue from wip/and-review-steps (see git log)
