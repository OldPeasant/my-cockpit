---
title: Android reads and writes the synced user settings
status: in_progress
owner: dell26/agent-1
deps: []
touches: [android/app/src/main/java/ch/sonensei/cockpit/core/settings,android/app/src/test/java/ch/sonensei/cockpit/core/settings,android/app/src/main/java/ch/sonensei/cockpit/settings,android/app/src/test/java/ch/sonensei/cockpit/settings,android/app/src/main/java/ch/sonensei/cockpit/ui/theme,android/app/src/test/java/ch/sonensei/cockpit/ui/theme,android/app/src/main/res/values/strings_settings.xml,android/app/src/main/res/values-de/strings_settings.xml]
prio: 1
lease_until: 2026-11-08T07:58:28Z
---
## Goal
Android does not read `/api/v1/settings` at all (week start comes from the device locale, theme from the system,
energy/creativity labels and mail image settings are unknown). Several parity tasks need these settings. Add one
shared settings repository (cached, refreshed on start and on the settings SSE/push event if one exists) and apply
the basic ones.

## Acceptance criteria
- [ ] A `UserSettingsRepository` (Hilt singleton) loads `GET /api/v1/settings`, caches the last value for offline start, exposes a `StateFlow`, and offers a typed `update(...)` that sends `PATCH` with ETag/If-Match and handles 412 by reloading
- [ ] The theme setting (light/dark/system) is applied app-wide (dynamic color stays for "system")
- [ ] The calendar week start and time zone come from the settings instead of the device locale (fallback to the locale when unset)
- [ ] Energy/creativity labels shown in inbox processing and task detail come from the settings
- [ ] New strings are in English and German (`res/values/` + `res/values-de/`)
- [ ] Unit/Compose tests cover the new behavior; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass

## Notes
Planned 2026-11-08 from the owner's UX request (web/Android parity, mail images, back navigation, mail search); the parity inventory and decisions are in `docs/PARITY.md`.
Editing GTD contexts/areas, labels or time zone on Android is out of scope (web-only admin, owner decision 2026-11-08).
