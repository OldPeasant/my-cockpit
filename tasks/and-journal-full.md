---
title: Android journal: other days, mood, tags, calendar, on this day
status: in_progress
owner: dell26/agent-1
deps: []
touches: [android/app/src/main/java/ch/sonensei/cockpit/journal,android/app/src/test/java/ch/sonensei/cockpit/journal,android/app/src/main/res/values/strings_journal.xml,android/app/src/main/res/values-de/strings_journal.xml]
prio: 2
lease_until: 2026-11-09T03:17:57Z
extended: 
hold: 
releases: 0
---
## Goal
Android's journal is fixed to today (`JournalViewModel.kt:86`); web has previous/next day, mood, tags, a heatmap
and "on this day" (`journal-page.html`).

## Acceptance criteria
- [x] Previous/next day, Today, and a date picker to open any day (entries of other days editable)
  - verified (2026-11-08 21:12+0100 by rudi/agent-1): cd android && ./gradlew testDebugUnitTest (Robolectric Compose + VM tests, JVM; no device here): JournalDaysScreenTest(10), JournalDaysViewModelTest(15), JournalDatePickerScreenTest(2: pick Oct 12 opens it, future days disabled), JournalDaysRaceViewModelTest(22) -> all pass; prev/next/Today/picker open and edit other days
- [x] Mood selector and tags editing
  - verified (2026-11-08 21:12+0100 by rudi/agent-1): cd android && ./gradlew testDebugUnitTest (Robolectric Compose + VM tests, JVM; no device here): JournalDaysViewModelTest + JournalDaysScreenTest mood/tags cases, JournalRepositoryTest(17) -> all pass; mood set/clear and tags edit saved via PUT
- [x] A month calendar marking days with entries (tap opens the day)
  - verified (2026-11-08 21:12+0100 by rudi/agent-1): cd android && ./gradlew testDebugUnitTest (Robolectric Compose + VM tests, JVM; no device here): JournalDaysScreenTest month calendar cases, JournalDaysScreenStatesTest(12) -> all pass; days with entries marked, tap opens day
- [x] "On this day" in earlier years
  - verified (2026-11-08 21:12+0100 by rudi/agent-1): cd android && ./gradlew testDebugUnitTest (Robolectric Compose + VM tests, JVM; no device here): JournalDaysViewModelTest on-this-day cases, JournalLateAnswersViewModelTest(8, incl. loading state), JournalDaysScreenStatesTest -> all pass
- [x] New strings are in English and German (`res/values/` + `res/values-de/`)
  - verified (2026-11-08 21:12+0100 by rudi/agent-1): JournalGermanTest(2) + JournalLocaleScreenTest(1) under de locale -> pass; every new key in values/strings_journal.xml and values-de/strings_journal.xml; lintDebug (MissingTranslation) -> pass
- [~] Unit/Compose tests cover the new behavior; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass
  - skipped (2026-11-08 21:12+0100 by rudi/agent-1): cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug -> BUILD SUCCESSFUL, 4584 tests 0 failures; sh scripts/check.sh tfp android -> all checks passed. Full sh scripts/check.sh can't pass on this machine: server part needs Docker (Testcontainers: 'Could not find a valid Docker environment', 424/3068 fail) and web part needs Node >=22 (have v20.9.0). Task changes only android/.

## Notes
Planned 2026-11-08 from the owner's UX request (web/Android parity, mail images, back navigation, mail search); the parity inventory and decisions are in `docs/PARITY.md`.
- ASSUMPTION (2026-11-08 20:07+0100 by rudi/agent-1): Opening another day first saves the shown one and stays on it (DAY_NOT_SAVED message) if that save fails or a conflict waits; web asks in a leave dialog instead. Navigation is ignored while a photo uploads.
- ASSUMPTION (2026-11-08 20:07+0100 by rudi/agent-1): Month calendar starts weeks on the device locale's first day (not the synced weekStart setting) and is a toggled panel above the entry; marks days by mood strength like the web heatmap.
- ASSUMPTION (2026-11-08 20:07+0100 by rudi/agent-1): Kept JournalRepository.save(date, base, text) (keeps mood/tags) for ReflectionSaver in review/ and added saveEntry(date, base, draft), so no file outside the task's touches changes.
- extended by rudi/agent-1: Implementation, fixes for CR-1..4 and re-review (0 P0-P2, 2 optional P3: camera photo after process death goes to today; on-this-day 'none' shown while loading) are committed at 4fdf5ce4; journal unit tests pass incl. new late-answer/locale/photo-day test files. Left: decide/fix the 2 P3s (if code changes, re-run code-reviewer), let test-writer write reviews/and-journal-full/tests.md with coverage, record verify for criteria 1-6, run sh scripts/check.sh (earlier failed only on server Testcontainers without Docker - skip-verify that part with reason if still so), write quality.json, done.
- ASSUMPTION (2026-11-08 21:12+0100 by rudi/agent-1): Criterion 6: full scripts/check.sh not runnable here (no Docker for server Testcontainers, Node 20 too old for Angular CLI); assumed server/web unaffected since this task changes only android/ and reviews/; tfp+android parts pass.
- hold by rudi/agent-1: Implementation, review evidence (quality.json, reviewed_revision c12daa98) and criteria 1-6 recorded; only 'done' is left. .taskcheck cannot pass on this machine (rudi/agent-1) for reasons outside the task: no Docker for server Testcontainers (424/3068 fail) and Node v20.9.0 < Angular CLI's minimum 22. tfp+android parts pass. Run 'python tasks.py done and-journal-full' on a host with Docker and Node >=22, or fix this machine's environment.
