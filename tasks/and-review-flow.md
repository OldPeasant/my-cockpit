---
title: Android weekly review: start, steps, resume, reflection
status: in_progress
owner: dell26/agent-2
deps: [and-scheduled-list]
touches: [android/app/src/main/java/ch/sonensei/cockpit/review,android/app/src/test/java/ch/sonensei/cockpit/review,android/app/src/main/java/ch/sonensei/cockpit/ui/navigation,android/app/src/test/java/ch/sonensei/cockpit/ui/navigation,android/app/src/main/res/values/strings_review.xml,android/app/src/main/res/values-de/strings_review.xml]
prio: 2
lease_until: 2026-11-08T13:44:16Z
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
