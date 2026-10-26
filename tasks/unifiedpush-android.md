---
title: UnifiedPush for Android
status: in_progress
owner: dell26/agent-2
deps: [and-reminders, unifiedpush-server, unifiedpush-android-client]
touches: [android/app/src/main/java/ch/sonensei/cockpit/push,android/app/src/test/java/ch/sonensei/cockpit/push]
prio: 3
lease_until: 2026-10-26T22:57:28Z
subtasks: unifiedpush-server, unifiedpush-android-client
releases: 0
extended: 
hold: 
---
## Goal
Push via a self-hosted distributor (ntfy).

## Acceptance criteria
- [ ] App registers with UnifiedPush; server sends reminder/new-mail pushes; fallback to periodic sync
- [ ] `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.push.*'` passes, covering message handling (Robolectric/Compose UI tests for screens)

## Notes
- split by dell26/agent-2: into unifiedpush-server, unifiedpush-android-client. Nothing implemented yet: neither the server push API nor the Android client exists. Split into server (registrations + sending) and Android client; this task then only integrates and verifies. Once they are done, this task is only integration: merge what is left on wip/unifiedpush-android (if anything), verify the acceptance criteria end to end, done.
- hold by Peter Gürtler: test
- ASSUMPTION (2026-10-26 23:02+0100 by dell26/agent-2): No device/emulator/ntfy in the agent environment: end-to-end verified by combining server tests (WireMock distributor, RFC 8291 encryption, reminder+mail pushes) with Android tests (registration, PUT contract via generated client, message handling) plus a new app-side contract test pinning the server payload format; a manual on-device run with ntfy is left to the owner
- ASSUMPTION (2026-10-26 23:34+0100 by dell26/agent-2): Reminder push now loads the pushed reminder by id (GET /reminders/{id}) and shows it once via ReminderScheduler.showFiredByServer (small addition in reminders/, outside touches); a redelivered push after a later sync may re-post (replace) the notification
