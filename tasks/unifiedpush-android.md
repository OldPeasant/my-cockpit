---
title: UnifiedPush for Android
status: in_progress
owner: dell26/agent-2
deps: [and-reminders]
touches: [android/app/src/main/java/ch/sonensei/cockpit/push,android/app/src/test/java/ch/sonensei/cockpit/push]
prio: 3
lease_until: 2026-10-26T14:24:51Z
---
## Goal
Push via a self-hosted distributor (ntfy).

## Acceptance criteria
- [ ] App registers with UnifiedPush; server sends reminder/new-mail pushes; fallback to periodic sync
- [ ] `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.push.*'` passes, covering message handling (Robolectric/Compose UI tests for screens)

## Notes
