---
title: Android weekly review: waiting, projects, next, someday steps
status: in_progress
owner: dell26/agent-1
deps: [and-review-flow]
touches: [android/app/src/main/java/ch/sonensei/cockpit/review,android/app/src/test/java/ch/sonensei/cockpit/review,android/app/src/main/res/values/strings_review.xml,android/app/src/main/res/values-de/strings_review.xml]
prio: 2
lease_until: 2026-11-08T17:30:23Z
releases: 1
extended: 
---
## Goal
The action steps of the weekly review on Android, matching web `steps/waiting-step.ts`, `projects-step.ts`,
`next-actions-step.ts`, `someday-step.ts`.

## Acceptance criteria
- [ ] Waiting: keep / follow up / done per item
- [ ] Projects: add next step, change status, keep; rows open project detail
- [ ] Next actions older than 30 days: keep / move to someday / delete
- [ ] Someday (tasks and projects): activate / keep / delete
- [ ] New strings are in English and German (`res/values/` + `res/values-de/`)
- [ ] Unit/Compose tests cover the new behavior; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass

## Notes
Planned 2026-11-08 from the owner's UX request (web/Android parity, mail images, back navigation, mail search); the parity inventory and decisions are in `docs/PARITY.md`.
- ASSUMPTION (2026-11-08 16:49+0100 by felix/agent-1): Project rows open the detail via a Hilt @Singleton ProjectOpenRequests (gtd/projects) that ProjectsViewModel takes on creation or while kept; this touches gtd/projects outside the task's touches, kept minimal (one class, one init block, one constructor param).
- ASSUMPTION (2026-11-08 16:49+0100 by felix/agent-1): Delete in the next-actions and someday steps moves to the trash without a confirmation dialog, matching the web wizard (trash is recoverable).
- ASSUMPTION (2026-11-08 16:49+0100 by felix/agent-1): Follow up creates a Next task titled like the Waiting list's follow-up (waiting_follow_up_title[_who]) in the waiting task's project, matching web followUpTask(); the waiting task itself stays.
- release by felix/agent-1: over budget after 2 sessions (~54 min) and a review that decided nothing; still resumable: continue from wip/and-review-steps (see git log)
