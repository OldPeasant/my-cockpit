---
title: Android reminders
status: in_progress
owner: dell26/agent-3
deps: [and-gtd-data,reminders-core,and-capture-entrypoints]
touches: [android/app/src/main/java/ch/sonensei/cockpit/reminders,android/app/src/test/java/ch/sonensei/cockpit/reminders,android/app/src/main/AndroidManifest.xml]
prio: 2
lease_until: 2026-10-21T07:58:59Z
---
## Goal
Local notifications for reminders (SPEC.md §9.2).

## Acceptance criteria
- [ ] Synced upcoming reminders are scheduled with AlarmManager (exact alarm permission flow, fallback to inexact); rescheduled after boot and sync
- [ ] Notification opens the item; POST_NOTIFICATIONS permission requested in context
- [ ] `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.reminders.*'` passes, covering scheduling/cancelling on sync changes (ShadowAlarmManager) (Robolectric/Compose UI tests for screens)

## Notes
- From reminders-core review (CR-2, P3): `GET /api/v1/reminders/upcoming` returns reminders whose item is
  trashed/deleted and those whose item lookup failed alike as `item.available=false`; the server skips notifying only
  for gone items. Decide how Android treats `available=false` (skip, or extend the API to distinguish the cases).
