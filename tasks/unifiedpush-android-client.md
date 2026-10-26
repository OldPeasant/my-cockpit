---
title: Android UnifiedPush client
status: in_progress
owner: dell26/agent-2
deps: [and-reminders, unifiedpush-server]
prio: 3
parent: unifiedpush-android
lease_until: 2026-10-26T18:19:12Z
extended: dell26/agent-2
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
- ASSUMPTION (2026-10-26 17:38+0100 by dell26/agent-2): Distributor choice has no UI: the saved one, else the system default, else (several, no default) the first installed one; no distributor -> no registration, the existing WorkManager periodic syncs stay the only sync
- ASSUMPTION (2026-10-26 17:38+0100 by dell26/agent-2): Instance id = random UUID per login (SharedPreferences 'push'); registration runs at app start and after each login (PersistentTokenStore.session); endpoint PUT is best effort without retry (next app start registers again; the distributor re-sends the endpoint, the PUT is idempotent)
- ASSUMPTION (2026-10-26 17:38+0100 by dell26/agent-2): Logout: new LogoutHook multibinding in AuthRepository runs before device revocation so DELETE /push/unifiedpush/{id} is still authenticated (5 s bound); non-user logouts (refresh rejected) only unregister locally via SessionCache, the server drops the endpoint on 404/410
- ASSUMPTION (2026-10-26 17:38+0100 by dell26/agent-2): Pushes are only acted on when the connector decrypted them (RFC 8291): plain-text posts to the endpoint (anyone knowing the ntfy topic) are ignored. Reminder push -> one-time ReminderSyncWorker (unique 'push-reminders-sync', REPLACE); mail push -> one content-free 'New mail' notification on channel 'mail' that opens the app
- extended by dell26/agent-2: Implementation + tests committed (d75f6b0b2..99a8fa85e). Left: 3 failing tests in push.* expose production bugs: (1) PushRegistrar lets checked exceptions (KeyStoreException) from the connector escape register/unregister (PushRegistrarTest:270,280) -> catch Exception, unregister must still forget the instance; (2) LibraryUnifiedPushConnector selects no distributor when several are installed without a link activity -> pick first installed. Then quality gate (test-writer, code-reviewer, fixer, reviews/unifiedpush-android-client/quality.json), tasks.py verify 1-3, done.
