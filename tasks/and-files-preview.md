---
title: Android files: in-app preview
status: in_progress
owner: felix/agent-1
deps: [and-files-manage]
touches: [android/app/src/main/java/ch/sonensei/cockpit/files,android/app/src/test/java/ch/sonensei/cockpit/files,android/app/src/main/res/values/strings_files.xml,android/app/src/main/res/values-de/strings_files.xml]
prio: 2
lease_until: 2026-11-08T14:19:03Z
---
## Goal
SPEC.md §8.2 asks for an in-app preview (PDF via Android `PdfRenderer`); Android only opens files in other apps.

## Acceptance criteria
- [ ] Preview for images (zoom), PDF (`PdfRenderer`, page navigation), text; others fall back to "Open with"
- [ ] Previous/next file in the folder; thumbnails in the list (server thumbnails)
- [ ] New strings are in English and German (`res/values/` + `res/values-de/`)
- [ ] Unit/Compose tests cover the new behavior; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass

## Notes
Planned 2026-11-08 from the owner's UX request (web/Android parity, mail images, back navigation, mail search); the parity inventory and decisions are in `docs/PARITY.md`.
