---
title: Android files: new folder, rename, move, delete
status: in_progress
owner: dell26/agent-1
deps: []
touches: [android/app/src/main/java/ch/sonensei/cockpit/files,android/app/src/test/java/ch/sonensei/cockpit/files,android/app/src/main/res/values/strings_files.xml,android/app/src/main/res/values-de/strings_files.xml]
prio: 2
lease_until: 2026-11-08T13:08:13Z
extended: 
releases: 2
---
## Goal
Android Files can browse, download and upload only (web: `files-page.html:76-200`).

## Acceptance criteria
- [ ] New folder, rename, move (folder picker), delete (to Trash) for files and folders
- [ ] Long-press multi-select for move/delete
- [ ] New strings are in English and German (`res/values/` + `res/values-de/`)
- [ ] Unit/Compose tests cover the new behavior; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass

## Notes
Planned 2026-11-08 from the owner's UX request (web/Android parity, mail images, back navigation, mail search); the parity inventory and decisions are in `docs/PARITY.md`.
- extended by rudi/agent-1: Implementation done (230fb24a). Test-writer WIP tests committed (76e1ef66, 15eeeda3), but reviews/and-files-manage/tests.md is not written yet. review.md has 2 P2 + 2 P3 findings (CR-1: retry after 412 from open rename dialog/move picker fails again). Left: finish test-writer (tests.md + coverage), run fixer on the CR findings, re-run test-writer + code-reviewer on the final code, verify criteria #1-4, quality.json, done. Note: scripts/check.sh currently fails in server test PageFetcherSizeLimitTests (HTTP2 size limit), which is unrelated to this Android task and may be flaky. Re-run it before done.
- release by rudi/agent-1: Implementation + fixes for first review (CR-1..CR-4) are committed (55032f4b); extra test-writer tests + second review.md committed (fef381d0). Second review: 0 P0-P2, 2 optional P3 (404 retry dead end in rename dialog/move picker; formatting in FilesScreen.kt:529-550). Left: (1) decide on P3s (fix via fixer or record as accepted in quality.json); (2) run test-writer to write reviews/and-files-manage/tests.md with coverage (still missing) and, if code changes, re-run code-reviewer; (3) run 'cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug' and 'sh scripts/check.sh' (earlier failed in unrelated server PageFetcherSizeLimitTests, possibly flaky); (4) tasks.py verify #1-4; (5) write+commit quality.json; (6) done.
- release by rudi/agent-1: Implementation complete; first-review fixes CR-1..CR-4 committed (0f1dac63); second-review P3s (404 retry dead end in rename dialog/move picker, MovePickerDialog formatting) fixed in 6e1a635d by the parent worker, with tests in FilesViewModelTest; ktlintCheck + files unit tests pass. Left (quality gate only): (1) run test-writer on final code -> write reviews/and-files-manage/tests.md incl. coverage (still missing); (2) re-run code-reviewer on final code (review.md is stale since 6e1a635d); fixer only if new findings; (3) cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug and sh scripts/check.sh (earlier failure in unrelated server PageFetcherSizeLimitTests, possibly flaky); (4) tasks.py verify #1-4; (5) write+commit reviews/and-files-manage/quality.json (record that the P3 fixes in 6e1a635d were made by the parent, not the fixer); (6) done.
