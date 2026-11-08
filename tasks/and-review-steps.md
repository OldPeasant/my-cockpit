---
title: Android weekly review: waiting, projects, next, someday steps
status: in_progress
owner: felix/agent-1
deps: [and-review-flow]
touches: [android/app/src/main/java/ch/sonensei/cockpit/review,android/app/src/test/java/ch/sonensei/cockpit/review,android/app/src/main/res/values/strings_review.xml,android/app/src/main/res/values-de/strings_review.xml]
prio: 2
lease_until: 2026-11-08T15:59:10Z
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
