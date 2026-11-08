---
title: Android Next actions: due, area/project filters and saved presets
status: in_progress
owner: dell26/agent-1
deps: [and-user-settings]
touches: [android/app/src/main/java/ch/sonensei/cockpit/gtd/next,android/app/src/test/java/ch/sonensei/cockpit/gtd/next,android/app/src/main/res/values/strings_next_today.xml,android/app/src/main/res/values-de/strings_next_today.xml]
prio: 2
lease_until: 2026-11-08T15:06:54Z
---
## Goal
Web Next has filters by due and area/project and saved presets synced via the `nextActionPresets` setting
(`next-view.html:12-145`); Android only has context/energy/creativity/estimate.

## Acceptance criteria
- [ ] Filters for due (overdue/today/this week/any) and area/project
- [ ] Saved presets: apply, save current, delete; read/written through the shared `nextActionPresets` setting so presets are the same as on web
- [ ] New strings are in English and German (`res/values/` + `res/values-de/`)
- [ ] Unit/Compose tests cover the new behavior; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass

## Notes
Planned 2026-11-08 from the owner's UX request (web/Android parity, mail images, back navigation, mail search); the parity inventory and decisions are in `docs/PARITY.md`.
- ASSUMPTION (2026-11-08 15:48+0100 by dell26/agent-1): Edited core/settings/UserSettings.kt (outside touches, small): UserSettings.nextActionPresets (raw JSON texts) and UserSettingsChange.nextActionPresets, because presets are stored through the shared UserSettingsRepository (PATCH with If-Match).
- ASSUMPTION (2026-11-08 15:48+0100 by dell26/agent-1): Due 'this week' means today plus the next 6 days (label 'Next 7 days'), same as the server's due=week and the web Next filter, for parity; 'any' is an explicit chip that turns the due filter off.
