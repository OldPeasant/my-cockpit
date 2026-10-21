---
title: Android reminders
status: in_progress
owner: dell26/agent-3
deps: [and-gtd-data,reminders-core,and-capture-entrypoints]
touches: [android/app/src/main/java/ch/sonensei/cockpit/reminders,android/app/src/test/java/ch/sonensei/cockpit/reminders,android/app/src/main/AndroidManifest.xml]
prio: 2
lease_until: 2026-10-21T08:19:10Z
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
- ASSUMPTION (2026-10-21 09:53+0200 by dell26/agent-3): Reminders whose item is available=false (trashed/deleted or no resolver) get no local alarm on Android, same as the server skipping gone items; no API change. A reminder on a type without resolver therefore only notifies via the web/SSE.
- ASSUMPTION (2026-10-21 09:53+0200 by dell26/agent-3): Android has no item detail screens yet, so tapping a reminder notification opens the screen listing the item's type (TASK->Next, PROJECT->Projects, EVENT->Calendar, EMAIL->Mail, NOTE->Notes, FILE/FOLDER->Files, ...). Detail screens can hook into ReminderOpenRequests later.
- ASSUMPTION (2026-10-21 09:53+0200 by dell26/agent-3): Android reminder sync is its own WorkManager job (every 15 min + once when the shell opens), window = next 7 days, max 100 earliest alarms (AlarmManager caps alarms per app); stored in private SharedPreferences so alarms are re-set after boot without network.
- ASSUMPTION (2026-10-21 09:53+0200 by dell26/agent-3): Exact alarms use SCHEDULE_EXACT_ALARM (user-granted via the in-app prompt opening the system page), not USE_EXACT_ALARM (Play policy restricts it to alarm/calendar apps); without it alarms are inexact setAndAllowWhileIdle. Permissions are asked in context: a dismissible card above the shell content appears only once the user has upcoming reminders.
