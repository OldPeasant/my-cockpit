---
title: Android Next actions: due, area/project filters and saved presets
status: in_progress
owner: dell26/agent-1
deps: [and-user-settings]
touches: [android/app/src/main/java/ch/sonensei/cockpit/gtd/next,android/app/src/test/java/ch/sonensei/cockpit/gtd/next,android/app/src/main/res/values/strings_next_today.xml,android/app/src/main/res/values-de/strings_next_today.xml]
prio: 2
lease_until: 2026-11-08T15:04:09Z
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
