---
title: Android journal: other days, mood, tags, calendar, on this day
status: in_progress
owner: rudi/agent-1
deps: []
touches: [android/app/src/main/java/ch/sonensei/cockpit/journal,android/app/src/test/java/ch/sonensei/cockpit/journal,android/app/src/main/res/values/strings_journal.xml,android/app/src/main/res/values-de/strings_journal.xml]
prio: 2
lease_until: 2026-11-08T20:25:33Z
extended: rudi/agent-1
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
- ASSUMPTION (2026-11-08 20:07+0100 by rudi/agent-1): Opening another day first saves the shown one and stays on it (DAY_NOT_SAVED message) if that save fails or a conflict waits; web asks in a leave dialog instead. Navigation is ignored while a photo uploads.
- ASSUMPTION (2026-11-08 20:07+0100 by rudi/agent-1): Month calendar starts weeks on the device locale's first day (not the synced weekStart setting) and is a toggled panel above the entry; marks days by mood strength like the web heatmap.
- ASSUMPTION (2026-11-08 20:07+0100 by rudi/agent-1): Kept JournalRepository.save(date, base, text) (keeps mood/tags) for ReflectionSaver in review/ and added saveEntry(date, base, draft), so no file outside the task's touches changes.
- extended by rudi/agent-1: Implementation, fixes for CR-1..4 and re-review (0 P0-P2, 2 optional P3: camera photo after process death goes to today; on-this-day 'none' shown while loading) are committed at 4fdf5ce4; journal unit tests pass incl. new late-answer/locale/photo-day test files. Left: decide/fix the 2 P3s (if code changes, re-run code-reviewer), let test-writer write reviews/and-journal-full/tests.md with coverage, record verify for criteria 1-6, run sh scripts/check.sh (earlier failed only on server Testcontainers without Docker - skip-verify that part with reason if still so), write quality.json, done.
