---
title: Android reads and writes the synced user settings
status: in_progress
owner: dell26/agent-1
deps: []
touches: [android/app/src/main/java/ch/sonensei/cockpit/core/settings,android/app/src/test/java/ch/sonensei/cockpit/core/settings,android/app/src/main/java/ch/sonensei/cockpit/settings,android/app/src/test/java/ch/sonensei/cockpit/settings,android/app/src/main/java/ch/sonensei/cockpit/ui/theme,android/app/src/test/java/ch/sonensei/cockpit/ui/theme,android/app/src/main/res/values/strings_settings.xml,android/app/src/main/res/values-de/strings_settings.xml]
prio: 1
lease_until: 2026-11-08T08:43:44Z
---
## Goal
Android does not read `/api/v1/settings` at all (week start comes from the device locale, theme from the system,
energy/creativity labels and mail image settings are unknown). Several parity tasks need these settings. Add one
shared settings repository (cached, refreshed on start and on the settings SSE/push event if one exists) and apply
the basic ones.

## Acceptance criteria
- [x] A `UserSettingsRepository` (Hilt singleton) loads `GET /api/v1/settings`, caches the last value for offline start, exposes a `StateFlow`, and offers a typed `update(...)` that sends `PATCH` with ETag/If-Match and handles 412 by reloading
  - verified (2026-11-08 09:23+0100 by dell26/agent-1): cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug -q on c317d934e -> exit 0, 2940 tests, 0 failures; UserSettingsRepositoryTest (GET load, DataStore cache offline start, StateFlow, PATCH with If-Match, 412/409/428 reload), UserSettingsRepositoryEdgeCasesTest 11/11 on 8c228f06e (ordering/logout/errors), DataStoreUserSettingsStoreTest pass; Hilt @Singleton compiles in assembleDebug path of lintDebug
- [x] The theme setting (light/dark/system) is applied app-wide (dynamic color stays for "system")
  - verified (2026-11-08 09:23+0100 by dell26/agent-1): cd android && ./gradlew testDebugUnitTest -> CockpitSettingsThemeTest (light/dark/system, dynamic color kept for system), AppearanceSectionTest, AppearanceViewModelTest pass (part of 2940 tests, 0 failures); theme wraps MainActivity/Share/Capture activities via CockpitSettingsTheme
- [x] The calendar week start and time zone come from the settings instead of the device locale (fallback to the locale when unset)
  - verified (2026-11-08 09:23+0100 by dell26/agent-1): cd android && ./gradlew testDebugUnitTest -> CalendarViewModelSettingsTest (synced week start + zone, locale/device fallback when unset, reload on change) and EventEditorViewModelSettingsTest (synced zone for new/edited events) pass, 0 failures
- [x] Energy/creativity labels shown in inbox processing and task detail come from the settings
  - verified (2026-11-08 09:23+0100 by dell26/agent-1): cd android && ./gradlew testDebugUnitTest -> InboxUserLabelsTest and TaskDetailUserLabelsTest (Robolectric Compose: renamed energy/creativity labels from settings shown, defaults translated) + UserSettingsTest pass, 0 failures
- [x] New strings are in English and German (`res/values/` + `res/values-de/`)
  - verified (2026-11-08 09:23+0100 by dell26/agent-1): grep -c '<string' res/values/strings_settings.xml res/values-de/strings_settings.xml -> 52/52, 10 new keys added to both; ./gradlew lintDebug (MissingTranslation) -> pass
- [x] Unit/Compose tests cover the new behavior; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass
  - verified (2026-11-08 09:23+0100 by dell26/agent-1): cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug -q -> exit 0, 2940 tests, 0 failures (c317d934e) + EdgeCasesTest 11/11 (8c228f06e); sh scripts/check.sh on c317d934e -> all checks passed (tfp, server, web, android)

## Notes
Planned 2026-11-08 from the owner's UX request (web/Android parity, mail images, back navigation, mail search); the parity inventory and decisions are in `docs/PARITY.md`.
Editing GTD contexts/areas, labels or time zone on Android is out of scope (web-only admin, owner decision 2026-11-08).
- ASSUMPTION (2026-11-08 08:45+0100 by dell26/agent-1): The server publishes no settings SSE/push event (SettingsChangedEvent is internal only), so Android refreshes the settings at each sign-in and whenever MainActivity comes to the front (onStart), plus the DataStore cache at process start.
- ASSUMPTION (2026-11-08 08:45+0100 by dell26/agent-1): Explicit light/dark themes keep wallpaper dynamic color (dynamicLight/DarkColorScheme) on Android 12+; only the brightness follows the setting. 'system' behaves exactly as before.
- ASSUMPTION (2026-11-08 08:45+0100 by dell26/agent-1): Energy/creativity labels equal to the server's English defaults (Low/Medium/High, Routine/Mixed/Creative) count as not renamed and show the app's own translation, so German users don't get English defaults; renamed labels are shown as typed. Also applied to the Next filter chips for consistency.
- ASSUMPTION (2026-11-08 08:45+0100 by dell26/agent-1): The server always returns weekStart/timeZone (defaults MONDAY/Europe/Zurich), so the device locale/zone is only the fallback while no settings are loaded or cached (fresh install offline, after logout). New and edited events in the Android editor also default to the synced time zone.
- ASSUMPTION (2026-11-08 08:45+0100 by dell26/agent-1): Android edits the theme setting (Settings > Appearance radio group, PATCH with If-Match) since PARITY.md keeps only time zone, week start and labels web-only; update() on 409/412/428 reloads and reports Conflict without retrying the write, the user chooses again.
- ASSUMPTION (2026-11-08 08:45+0100 by dell26/agent-1): Cached settings are dropped on logout (SessionCache), so a different account never sees the previous user's labels/theme.
- ASSUMPTION (2026-11-08 09:24+0100 by dell26/agent-1): Review CR-3 (possible one-frame system theme at cold start before the cached theme loads asynchronously) is accepted, not fixed: P3, not reproduced; a fix would need a splash/hold-until-cache-read mechanism outside this task's scope
