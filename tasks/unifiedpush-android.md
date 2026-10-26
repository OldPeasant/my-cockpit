---
title: UnifiedPush for Android
status: todo
owner: 
deps: [and-reminders, unifiedpush-server, unifiedpush-android-client]
touches: [android/app/src/main/java/ch/sonensei/cockpit/push,android/app/src/test/java/ch/sonensei/cockpit/push]
prio: 3
lease_until: 
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
