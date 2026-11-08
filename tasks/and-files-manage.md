---
title: Android files: new folder, rename, move, delete
status: in_progress
owner: rudi/agent-1
deps: []
touches: [android/app/src/main/java/ch/sonensei/cockpit/files,android/app/src/test/java/ch/sonensei/cockpit/files,android/app/src/main/res/values/strings_files.xml,android/app/src/main/res/values-de/strings_files.xml]
prio: 2
lease_until: 2026-11-08T12:01:09Z
extended: rudi/agent-1
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
