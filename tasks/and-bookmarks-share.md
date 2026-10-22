---
title: Android: share URL to read later
status: in_progress
owner: dell26/agent-2
deps: [and-capture-entrypoints,bookmarks-api]
touches: [android/app/src/main/java/ch/sonensei/cockpit/capture/share,android/app/src/test/java/ch/sonensei/cockpit/capture/share]
prio: 3
lease_until: 2026-10-22T11:04:16Z
---
## Goal
Share sheet offers Inbox or Read later (SPEC.md §8.4).

## Acceptance criteria
- [ ] Shared URLs show a choice Inbox / Read later; read-later goes through the offline queue too
- [ ] `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.capture.share.*'` passes, covering the choice routing (Robolectric/Compose UI tests for screens)

## Notes
- ASSUMPTION (2026-10-22 11:58+0200 by dell26/agent-2): Read-later shares go into the existing capture queue (new pending_bookmark table, Room auto-migration 1->2, sent by CaptureUploader after the captures) instead of a second queue; this touches capture/queue outside the task's touches, because the criterion asks for 'the offline queue'.
- ASSUMPTION (2026-10-22 11:58+0200 by dell26/agent-2): POST /bookmarks has no client id, so a retry after a lost answer can create a duplicate bookmark; accepted (nothing lost) instead of adding server-side idempotency to an Android task.
- ASSUMPTION (2026-10-22 11:58+0200 by dell26/agent-2): The choice is offered only when the share contains an http(s) URL and no files; Inbox stays the default. Read later sends the sheet's title (none if it is just the URL, so the server takes the page title), no notes, and the pending count of the widget includes queued bookmarks.
