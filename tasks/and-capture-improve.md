---
title: Android capture: syntax preview, save and add another, attachments
status: in_progress
owner: dell26/agent-3
deps: []
touches: [android/app/src/main/java/ch/sonensei/cockpit/capture/share,android/app/src/test/java/ch/sonensei/cockpit/capture/share,android/app/src/main/res/values/strings_share.xml,android/app/src/main/res/values-de/strings_share.xml]
prio: 2
lease_until: 2026-11-08T17:45:35Z
---
## Goal
Web capture shows a live preview of `#project @context !due ^waiting` and offers "save and add another";
Android's capture sheet has neither and can only attach files that came from a share intent.

## Acceptance criteria
- [ ] The sheet shows the parsed project/contexts/due/waiting as chips while typing (same parsing rules as web; offline-capable, no server call)
- [ ] "Save and add another" keeps the sheet open, cleared
- [ ] An attach button picks files/photos (system picker) and attachments can be removed before saving; works with the offline queue
- [ ] New strings are in English and German (`res/values/` + `res/values-de/`)
- [ ] Unit/Compose tests cover the new behavior; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass

## Notes
Planned 2026-11-08 from the owner's UX request (web/Android parity, mail images, back navigation, mail search); the parity inventory and decisions are in `docs/PARITY.md`.
- ASSUMPTION (2026-11-08 17:53+0100 by dell26/agent-3): Syntax preview on Android uses a Kotlin port of the server's CaptureParser (capture/share/CaptureSyntax.kt) fed by the offline GTD cache (cached_project/cached_context, server's status order); a project/context created since the last GTD refresh is not shown as a chip though the server will recognize it. Web uses the server preview endpoint instead.
- ASSUMPTION (2026-11-08 17:53+0100 by dell26/agent-3): Picked attachments behave like shared files: names read from the provider, copied into the queue only at save; if the title is blank it is filled with the file names (same as the share target). Attaching files hides the read-later choice (files cannot become a bookmark).
- ASSUMPTION (2026-11-08 17:53+0100 by dell26/agent-3): 'Save and add another' is a TextButton next to Save on both capture sheets (share target and widget/tile/shortcut); after saving it clears the sheet (incl. shared URL/files), refocuses the title and shows the 'Saved' toast. CaptureActivity (capture/entry, outside touches) got the minimal wiring for it and the attach picker.
