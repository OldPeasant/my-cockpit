---
title: Android share target
status: in_progress
owner: dell26/agent-4
deps: [and-capture-queue]
touches: [android/app/src/main/java/ch/sonensei/cockpit/capture/share,android/app/src/test/java/ch/sonensei/cockpit/capture/share,android/app/src/main/AndroidManifest.xml]
prio: 1
lease_until: 2026-10-21T01:37:03Z
---
## Goal
Share text, URLs, images and files into the inbox (SPEC.md §4.5).

## Acceptance criteria
- [ ] Intent filters for `text/plain`, `image/*`, `*/*` (single + multiple); a compact capture sheet (edit text, save) writes to the queue
- [ ] Shared content URIs are copied (not referenced) before the activity ends
- [ ] `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.capture.share.*'` passes, covering intent parsing for text, URL, multiple images (Robolectric/Compose UI tests for screens)

## Notes
- ASSUMPTION (2026-10-21 03:19+0200 by dell26/agent-4): Shared URIs are copied into the capture queue when the user taps Save; the sheet closes only after the copy is stored. Cancelling (or leaving) drops the share, nothing is staged before Save.
- ASSUMPTION (2026-10-21 03:19+0200 by dell26/agent-4): Only content: URIs are accepted from shares; file: URIs are dropped so another app cannot make the cockpit read (and upload) its own private files.
- ASSUMPTION (2026-10-21 03:19+0200 by dell26/agent-4): Share draft: subject (page title) is the title and EXTRA_TEXT (URL) the notes when both are given; otherwise first text line = title, rest = notes; file-only shares default the title to up to 3 file names.
