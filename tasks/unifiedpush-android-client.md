---
title: Android UnifiedPush client
status: in_progress
owner: dell26/agent-2
deps: [and-reminders, unifiedpush-server]
prio: 3
parent: unifiedpush-android
lease_until: 2026-10-26T16:53:21Z
---
## Goal
Part of `unifiedpush-android` (UnifiedPush for Android), which was split.
Goal: The app registers with a UnifiedPush distributor (ntfy) and reacts to server pushes; WorkManager periodic sync stays as the fallback.

## Acceptance criteria
- [ ] UnifiedPush connector library added (libs.versions.toml, ARCHITECTURE.md stack table); registration on login, endpoint sent to `PUT /api/v1/push/unifiedpush/{instanceId}`, unregistered and deleted on logout; no distributor installed -> periodic sync only
- [ ] A reminder push runs the reminder sync; a new-mail push shows a new-mail notification (or runs the mail sync); unknown or garbled messages are ignored
- [ ] `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.push.*'` passes, covering message handling

## Notes
- Work done on `unifiedpush-android` before the split is on `origin/wip/unifiedpush-android` (if it exists): merge it first if your part builds on it.
- ASSUMPTION (2026-10-26 17:38+0100 by dell26/agent-2): UnifiedPush connector 3.3.5 (Maven Central) brings plain tink 1.23 which duplicates tink-android classes: tink-android bumped 1.18.0 -> 1.23.0 (same version the connector is built against) and plain tink excluded
