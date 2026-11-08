---
title: Android journal: other days, mood, tags, calendar, on this day
status: in_progress
owner: rudi/agent-1
deps: []
touches: [android/app/src/main/java/ch/sonensei/cockpit/journal,android/app/src/test/java/ch/sonensei/cockpit/journal,android/app/src/main/res/values/strings_journal.xml,android/app/src/main/res/values-de/strings_journal.xml]
prio: 2
lease_until: 2026-11-08T19:24:56Z
---
## Goal
Android's journal is fixed to today (`JournalViewModel.kt:86`); web has previous/next day, mood, tags, a heatmap
and "on this day" (`journal-page.html`).

## Acceptance criteria
- [ ] Previous/next day, Today, and a date picker to open any day (entries of other days editable)
- [ ] Mood selector and tags editing
- [ ] A month calendar marking days with entries (tap opens the day)
- [ ] "On this day" in earlier years
- [ ] New strings are in English and German (`res/values/` + `res/values-de/`)
- [ ] Unit/Compose tests cover the new behavior; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass

## Notes
Planned 2026-11-08 from the owner's UX request (web/Android parity, mail images, back navigation, mail search); the parity inventory and decisions are in `docs/PARITY.md`.
