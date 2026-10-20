---
title: Android reminders
status: todo
owner:
deps: [and-gtd-data,reminders-core,and-capture-entrypoints]
touches: [android/app/src/main/java/ch/sonensei/cockpit/reminders,android/app/src/test/java/ch/sonensei/cockpit/reminders,android/app/src/main/AndroidManifest.xml]
prio: 2
---
## Goal
Local notifications for reminders (SPEC.md §9.2).

## Acceptance criteria
- [ ] Synced upcoming reminders are scheduled with AlarmManager (exact alarm permission flow, fallback to inexact); rescheduled after boot and sync
- [ ] Notification opens the item; POST_NOTIFICATIONS permission requested in context
- [ ] `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.reminders.*'` passes, covering scheduling/cancelling on sync changes (ShadowAlarmManager) (Robolectric/Compose UI tests for screens)

## Notes
