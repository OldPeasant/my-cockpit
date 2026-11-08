---
title: Android files: in-app preview
status: in_progress
owner: felix/agent-1
deps: [and-files-manage]
touches: [android/app/src/main/java/ch/sonensei/cockpit/files,android/app/src/test/java/ch/sonensei/cockpit/files,android/app/src/main/res/values/strings_files.xml,android/app/src/main/res/values-de/strings_files.xml]
prio: 2
lease_until: 2026-11-08T14:53:37Z
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
- ASSUMPTION (2026-11-08 15:09+0100 by felix/agent-1): Tapping a file now opens the in-app preview for every file type (unsupported types show an 'Open with' card) so previous/next walks all files of the folder; opening in another app moved to the row menu and the preview's top bar ('Open with…').
- ASSUMPTION (2026-11-08 15:09+0100 by felix/agent-1): Image preview loads GET /files/{id}/preview (full image) for JPEG/PNG/WebP/GIF only (the types the server previews/thumbnails); other image types fall back to 'Open with'. List thumbnails use /thumbnail?size=256 drawn over the type icon; Coil loader is memory-only (no disk cache), like NoteImages.
- ASSUMPTION (2026-11-08 15:09+0100 by felix/agent-1): PDFs are downloaded whole into cacheDir/files-preview (only the last previewed kept) and rendered page by page with PdfRenderer at 2x screen width (max 2048 px); prev/next page buttons, pinch/double-tap zoom. Text preview uses the server's 256 KB text preview, shown monospaced, with a notice when truncated.
