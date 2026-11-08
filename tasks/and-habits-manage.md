---
title: Android habits: create, edit, archive, incremental check
status: in_progress
owner: dell26/agent-3
deps: []
touches: [android/app/src/main/java/ch/sonensei/cockpit/habits,android/app/src/test/java/ch/sonensei/cockpit/habits,android/app/src/main/res/values/strings_habits.xml,android/app/src/main/res/values-de/strings_habits.xml]
prio: 2
lease_until: 2026-11-08T18:41:15Z
---
## Goal
Android can only check habits, and checks all-or-nothing (count = target) while web increments by one up to the
target (`habits-page.ts:169`). The owner decided habits editing comes to Android.

## Acceptance criteria
- [ ] Create/edit a habit: name, schedule (daily / N per week / weekdays), target per day, reminder time
- [ ] Archive/unarchive; an "Archived" view
- [ ] Checking increments by one up to the target, then resets (same as web); best streak shown
- [ ] New strings are in English and German (`res/values/` + `res/values-de/`)
- [ ] Unit/Compose tests cover the new behavior; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass

## Notes
Planned 2026-11-08 from the owner's UX request (web/Android parity, mail images, back navigation, mail search); the parity inventory and decisions are in `docs/PARITY.md`.
