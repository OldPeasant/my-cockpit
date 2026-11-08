---
title: Android capture: syntax preview, save and add another, attachments
status: in_progress
owner: dell26/agent-3
deps: []
touches: [android/app/src/main/java/ch/sonensei/cockpit/capture/share,android/app/src/test/java/ch/sonensei/cockpit/capture/share,android/app/src/main/res/values/strings_share.xml,android/app/src/main/res/values-de/strings_share.xml]
prio: 2
lease_until: 2026-11-08T18:05:48Z
---
## Goal
Web capture shows a live preview of `#project @context !due ^waiting` and offers "save and add another";
Android's capture sheet has neither and can only attach files that came from a share intent.

## Acceptance criteria
- [x] The sheet shows the parsed project/contexts/due/waiting as chips while typing (same parsing rules as web; offline-capable, no server call)
  - verified (2026-11-08 18:37+0100 by dell26/agent-3): cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug -> BUILD SUCCESSFUL, 4230 tests 0 failures; CaptureSyntaxTest (26, mirrors server CaptureParserTests), CaptureVocabularySourceTest (Room cache only), CaptureSheetCaptureTest syntax-chip tests, ShareViewModelCaptureTest 'the preview is local only and queues nothing', ShareAddAnotherActivityTest 'a project from the offline GTD cache is previewed as a chip while typing' pass
- [x] "Save and add another" keeps the sheet open, cleared
  - verified (2026-11-08 18:37+0100 by dell26/agent-3): same run -> CaptureSheetCaptureTest add-another tests, ShareViewModelCaptureTest add-another tests and ShareAddAnotherActivityTest 'save and add another queues the share, keeps the sheet open and cleared, then a plain save closes it' (real activity + Hilt + Room queue, Robolectric) pass
- [x] An attach button picks files/photos (system picker) and attachments can be removed before saving; works with the offline queue
  - verified (2026-11-08 18:38+0100 by dell26/agent-3): same run -> CaptureSheetCaptureTest attach/remove tests (OpenMultipleDocuments */* via ActivityResultRegistry stand-in; remove only via close button) and ShareViewModelCaptureTest 'picked files are copied into the offline queue on save and survive the source going away' pass. Real DocumentsUI on a device not run (no emulator)
- [x] New strings are in English and German (`res/values/` + `res/values-de/`)
  - verified (2026-11-08 18:38+0100 by dell26/agent-3): values/ + values-de/ strings_share.xml hold all 7 new strings; lintDebug (MissingTranslation) passes; CaptureSheetCaptureTest @Config(qualifiers=de) 'the new controls and chips are in German' passes
- [x] Unit/Compose tests cover the new behavior; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass
  - verified (2026-11-08 18:38+0100 by dell26/agent-3): cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug -> BUILD SUCCESSFUL (4230 tests, 0 failures/errors/skips); sh scripts/check.sh -> '== all checks passed'

## Notes
Planned 2026-11-08 from the owner's UX request (web/Android parity, mail images, back navigation, mail search); the parity inventory and decisions are in `docs/PARITY.md`.
- ASSUMPTION (2026-11-08 17:53+0100 by dell26/agent-3): Syntax preview on Android uses a Kotlin port of the server's CaptureParser (capture/share/CaptureSyntax.kt) fed by the offline GTD cache (cached_project/cached_context, server's status order); a project/context created since the last GTD refresh is not shown as a chip though the server will recognize it. Web uses the server preview endpoint instead.
- ASSUMPTION (2026-11-08 17:53+0100 by dell26/agent-3): Picked attachments behave like shared files: names read from the provider, copied into the queue only at save; if the title is blank it is filled with the file names (same as the share target). Attaching files hides the read-later choice (files cannot become a bookmark).
- ASSUMPTION (2026-11-08 17:53+0100 by dell26/agent-3): 'Save and add another' is a TextButton next to Save on both capture sheets (share target and widget/tile/shortcut); after saving it clears the sheet (incl. shared URL/files), refocuses the title and shows the 'Saved' toast. CaptureActivity (capture/entry, outside touches) got the minimal wiring for it and the attach picker.
