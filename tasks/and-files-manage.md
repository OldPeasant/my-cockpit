---
title: Android files: new folder, rename, move, delete
status: in_progress
owner: rudi/agent-1
deps: []
touches: [android/app/src/main/java/ch/sonensei/cockpit/files,android/app/src/test/java/ch/sonensei/cockpit/files,android/app/src/main/res/values/strings_files.xml,android/app/src/main/res/values-de/strings_files.xml]
prio: 2
lease_until: 2026-11-08T11:00:37Z
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
