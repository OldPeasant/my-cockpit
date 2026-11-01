---
title: Android: Settings screen (server, notifications, capture queue, app version)
status: in_progress
owner: dell26/agent-1
deps: [and-shell,and-capture-queue,and-reminders,unifiedpush-android-client]
touches: [android/app/src/main/java/ch/sonensei/cockpit/settings,android/app/src/test/java/ch/sonensei/cockpit/settings,android/app/src/main/res/values/strings_settings.xml,android/app/src/main/res/values-de/strings_settings.xml]
prio: 2
lease_until: 2026-11-01T16:02:12Z
---
## Goal
The Android Settings screen shows only passkeys and logout; the rest is a placeholder. The owner chose "phone
essentials" (2026-11-01): no account setup on the phone, that stays on the web.

## Acceptance criteria
- [ ] Server section: shows the configured server address and lets the user change it, reusing `ServerUrlRepository.setServerUrl` (changing the origin logs out, as today); shows the signed-in user name
- [ ] Notifications section: shows whether notifications are allowed, whether exact alarms (reminders) are allowed, and the UnifiedPush distributor state, each with a button that opens the matching system settings or distributor picker; existing app-side toggles (if any) for reminders and new-mail notifications are shown here, no new server settings are invented
- [ ] Capture section: number of captures waiting to sync, last sync result, and a "Sync now" button that enqueues the existing capture upload work
- [ ] About section: app version name and code, and a button that opens `<server>/download/android` in the browser to get a newer APK
- [ ] Passkeys and logout stay; the placeholder is gone
- [ ] All new UI strings exist in English and German (`res/values`, `res/values-de`)
- [ ] Unit tests (ViewModel, plus Compose UI tests where the project has them for similar screens) cover the criteria above; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` passes

## Notes
Requested by the owner (2026-11-01) after trying the Android app on the phone. Out of scope: web settings sections (GTD defaults, accounts, integrations, backup).
- ASSUMPTION (2026-11-01 16:35+0100 by dell26/agent-1): Settings device sections (server, notifications, capture, about) only render inside a session, gated on the existing LocalPasskeySettingsEnabled flag, so previews and shell-only tests without Hilt keep working; auth/ was outside this task's touches.
- ASSUMPTION (2026-11-01 16:35+0100 by dell26/agent-1): No in-app toggles for reminders or new-mail notifications exist, so none were added; the 'Notification settings' button opens the system app-notification page where the reminder and new-mail channels can be switched individually.
- ASSUMPTION (2026-11-01 16:35+0100 by dell26/agent-1): 'Last sync result' = the capture queue's newest lastError (failed: {error}), else 'waiting' when captures are pending, else 'everything synced'; the app stores no success timestamp, so none is shown.
- ASSUMPTION (2026-11-01 16:35+0100 by dell26/agent-1): Distributor picker: an in-app dialog listing installed UnifiedPush distributors; switching deletes the old registration on the server, unregisters it, saves the new distributor and registers a new instance id. With none installed the button opens https://unifiedpush.org/users/distributors/.
- ASSUMPTION (2026-11-01 16:35+0100 by dell26/agent-1): Signed-in user name comes from GET /api/v1/auth/me (MeResponse.username), loaded when the screen opens; offline shows 'cannot be reached' with Retry. Edited InAppPasskeyTest/AppStartRoutingTest (auth tests, outside touches) to scroll to passkeys/Log out, which the new sections push below the fold.
