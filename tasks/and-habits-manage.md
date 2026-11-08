---
title: Android habits: create, edit, archive, incremental check
status: in_progress
owner: dell26/agent-3
deps: []
touches: [android/app/src/main/java/ch/sonensei/cockpit/habits,android/app/src/test/java/ch/sonensei/cockpit/habits,android/app/src/main/res/values/strings_habits.xml,android/app/src/main/res/values-de/strings_habits.xml]
prio: 2
lease_until: 2026-11-08T18:56:18Z
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
- ASSUMPTION (2026-11-08 19:23+0100 by dell26/agent-3): Habit create/edit uses an AlertDialog form with +/- steppers for days-a-week (1-7) and times-a-day (1-1000) instead of free number fields, matching mobile idioms; the reminder uses the Material TimePicker and can be removed.
- ASSUMPTION (2026-11-08 19:23+0100 by dell26/agent-3): Edit/Archive live in a per-row overflow menu (both views); archived habits are shown without a check circle and cannot be checked; edits send all editable fields (like web requestOf), archive sends only {archived}. Writes use a hand-written JsonObject Retrofit API because the generated client sends unset fields as null, which the server's PATCH would apply.
- ASSUMPTION (2026-11-08 19:23+0100 by dell26/agent-3): On a 404/409/412 for a save or archive the list is reloaded and the form stays open with a 'changed elsewhere' message; saving again overwrites with the reloaded version (same as web).
- ASSUMPTION (2026-11-08 19:23+0100 by dell26/agent-3): Weekday chips are shown Monday-first rather than following the user's week-start setting (no Android week-start setting is available in the habits module yet).
