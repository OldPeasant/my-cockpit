---
title: Android files: in-app preview
status: in_progress
owner: dell26/agent-1
deps: [and-files-manage]
touches: [android/app/src/main/java/ch/sonensei/cockpit/files,android/app/src/test/java/ch/sonensei/cockpit/files,android/app/src/main/res/values/strings_files.xml,android/app/src/main/res/values-de/strings_files.xml]
prio: 2
lease_until: 2026-11-08T15:42:15Z
extended: 
releases: 1
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
- extended by felix/agent-1: Implementation done and committed (85da95bf3, 7badf6f17). Test-writer tests committed (b3c42aeb6, 52ff47c6a) but incomplete: FilePreviewScreenTest 'an image that failed to load says so, and retry loads it again' times out in waitUntil (FilePreviewScreenTest.kt:267), 217/218 files tests pass, ktlint ok. Left: fix that test (or production bug), let test-writer finish + write reviews/and-files-preview/tests.md with coverage, run code-reviewer, fixer if needed, verify 4 criteria, quality.json, scripts/check.sh, done.
- release by felix/agent-1: Implementation done (b9438911f, 863f1793e, bd2f93564); test-writer tests committed (973d58059, ef4be35af, 88f52d4a8); './gradlew ktlintCheck testDebugUnitTest --tests ch.sonensei.cockpit.files.*' passes. Code review committed (reviews/and-files-preview/review.md, 3ad689bc6): 2 P2 + 1 P3 open. Left: (1) run fixer for CR-1 (cancellable PDF download copy loop in FilesRepository.download + per-load cache file in PreviewFiles.target / FilesViewModel.loadPdf finally), CR-2 (pass busy = state.downloading != null to FilePreviewView, disable Save/Open with during any download), CR-3 (readability, optional); (2) re-run test-writer on final code so it writes reviews/and-files-preview/tests.md with task-scoped coverage; (3) re-run code-reviewer (review.md is stale after fixes); (4) verify the 4 criteria, run full android check + sh scripts/check.sh, write quality.json, done.
