---
title: Android weekly review: start, steps, resume, reflection
status: in_progress
owner: dell26/agent-2
deps: [and-scheduled-list]
touches: [android/app/src/main/java/ch/sonensei/cockpit/review,android/app/src/test/java/ch/sonensei/cockpit/review,android/app/src/main/java/ch/sonensei/cockpit/ui/navigation,android/app/src/test/java/ch/sonensei/cockpit/ui/navigation,android/app/src/main/res/values/strings_review.xml,android/app/src/main/res/values-de/strings_review.xml]
prio: 2
lease_until: 2026-11-08T14:14:39Z
---
## Goal
Android's weekly review is read-only (SPEC.md §9.2 said "read-only first"; the owner decided on 2026-11-08 that
Android gets the full review). Web: `review-session.ts`, `steps/*`.

## Acceptance criteria
- [ ] Start review / continue an open review (same server review session as web, resumable across devices)
- [ ] Step navigation: next/previous, jump to a step, mark complete; progress shown
- [ ] Step 1 Get clear: embedded inbox processing (reuse the Android inbox processing UI) plus a link to Mail
- [ ] Steps 2/3 past/upcoming calendar with quick-add capture
- [ ] Step 8 reflection saved as a journal entry, with the habit stats shown
- [ ] New strings are in English and German (`res/values/` + `res/values-de/`)
- [ ] Unit/Compose tests cover the new behavior; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass

## Notes
Planned 2026-11-08 from the owner's UX request (web/Android parity, mail images, back navigation, mail search); the parity inventory and decisions are in `docs/PARITY.md`.
Steps 4-7 come in and-review-steps.
- ASSUMPTION (2026-11-08 14:28+0100 by dell26/agent-2): 'Mark complete' = 'Next step' marks the current step done and moves on; on step 8 it becomes 'Complete review' (the server tracks only the current step, like the web wizard).
- ASSUMPTION (2026-11-08 14:28+0100 by dell26/agent-2): Steps 4-7 show their hint and a button opening the matching list (Waiting/Projects/Next/Someday) until and-review-steps adds the in-wizard actions.
- ASSUMPTION (2026-11-08 14:28+0100 by dell26/agent-2): The reflection draft lives in the review view model only (survives tab switches and rotation, not app death); the web keeps it in localStorage. Saved on completion by appending '## Weekly review' to today's (device-local date) journal entry, like the web.
- ASSUMPTION (2026-11-08 14:28+0100 by dell26/agent-2): ReviewScreen now takes onOpen for the Mail/list links: one-line change in ui/CockpitShell.kt, outside the task's touches.
- ASSUMPTION (2026-11-08 14:28+0100 by dell26/agent-2): Calendar quick-add goes through the offline capture queue (CaptureQueue), so it works offline, unlike the web's direct createTask.
- ASSUMPTION (2026-11-08 14:58+0100 by dell26/agent-2): Links from the Review screen (step 1 Mail, steps 4-7 lists) push the target on top of Review instead of switching tabs, so system back returns to the wizard (PARITY decision 6); bottom bar then highlights Mail, without an app-bar back arrow
