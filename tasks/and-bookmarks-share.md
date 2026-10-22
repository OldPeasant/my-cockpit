---
title: Android: share URL to read later
status: done
owner: dell26/agent-2
deps: [and-capture-entrypoints,bookmarks-api]
touches: [android/app/src/main/java/ch/sonensei/cockpit/capture/share,android/app/src/test/java/ch/sonensei/cockpit/capture/share]
prio: 3
lease_until: 
releases: 0
extended: 
---
## Goal
Share sheet offers Inbox or Read later (SPEC.md §8.4).

## Acceptance criteria
- [x] Shared URLs show a choice Inbox / Read later; read-later goes through the offline queue too
  - verified (2026-10-22 13:56+0200 by dell26/agent-2): ShareReadLaterTest/ShareReadLaterEdgeTest/ShareReadLaterActivityTest (Robolectric, real Hilt+Room activity: URL share shows Inbox/Read later choice, Read later stores pending_bookmark in CaptureQueue and CaptureUploader sends POST /bookmarks readLater=true; ReadLaterQueueTest/ReadLaterUploaderTest offline retry) via cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.capture.share.*' --tests 'ch.sonensei.cockpit.capture.queue.*' --rerun -> 197 tests, 0 failures
- [x] `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.capture.share.*'` passes, covering the choice routing (Robolectric/Compose UI tests for screens)
  - verified (2026-10-22 13:57+0200 by dell26/agent-2): cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.capture.share.*' --rerun at 74e8c90a1 -> 8 classes, 100 tests, 0 failures (test-writer round 3; my own rerun incl. capture.queue.* -> 197 tests, 0 failures)

## Notes
- ASSUMPTION (2026-10-22 11:58+0200 by dell26/agent-2): Read-later shares go into the existing capture queue (new pending_bookmark table, Room auto-migration 1->2, sent by CaptureUploader after the captures) instead of a second queue; this touches capture/queue outside the task's touches, because the criterion asks for 'the offline queue'.
- ASSUMPTION (2026-10-22 11:58+0200 by dell26/agent-2): POST /bookmarks has no client id, so a retry after a lost answer can create a duplicate bookmark; accepted (nothing lost) instead of adding server-side idempotency to an Android task.
- ASSUMPTION (2026-10-22 11:58+0200 by dell26/agent-2): The choice is offered only when the share contains an http(s) URL and no files; Inbox stays the default. Read later sends the sheet's title (none if it is just the URL, so the server takes the page title), no notes, and the pending count of the widget includes queued bookmarks.
- release by dell26/agent-2: handed back by dell26/agent-2: pushes had failed (remote: error: GH006: Protected branch update failed for refs/heads/main. | remote: error: | To https://github.com/OldP…); continue from wip/and-bookmarks-share
- done by dell26/agent-2: `sh scripts/check.sh` passed in 170s on cd7035ef2; criteria verified 2/2
