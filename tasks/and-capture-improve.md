---
title: Android capture: syntax preview, save and add another, attachments
status: in_progress
owner: dell26/agent-3
deps: []
touches: [android/app/src/main/java/ch/sonensei/cockpit/capture/share,android/app/src/test/java/ch/sonensei/cockpit/capture/share,android/app/src/main/res/values/strings_share.xml,android/app/src/main/res/values-de/strings_share.xml]
prio: 2
lease_until: 2026-11-08T17:10:05Z
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
