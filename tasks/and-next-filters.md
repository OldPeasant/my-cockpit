---
title: Android Next actions: due, area/project filters and saved presets
status: in_progress
owner: dell26/agent-1
deps: [and-user-settings]
touches: [android/app/src/main/java/ch/sonensei/cockpit/gtd/next,android/app/src/test/java/ch/sonensei/cockpit/gtd/next,android/app/src/main/res/values/strings_next_today.xml,android/app/src/main/res/values-de/strings_next_today.xml]
prio: 2
lease_until: 2026-11-08T15:32:12Z
---
## Goal
Web Next has filters by due and area/project and saved presets synced via the `nextActionPresets` setting
(`next-view.html:12-145`); Android only has context/energy/creativity/estimate.

## Acceptance criteria
- [x] Filters for due (overdue/today/this week/any) and area/project
  - verified (2026-11-08 16:13+0100 by dell26/agent-1): cd android && ./gradlew testDebugUnitTest on bffd4e704 -> NextViewModelPresetsTest (due overdue/today/week/any, area via project, project, area change drops other-area project), NextFilterMatchTest, NextScreenPresetsTest (due chips incl. Any, area/project menus) pass; 3672 tests, 0 failures
- [x] Saved presets: apply, save current, delete; read/written through the shared `nextActionPresets` setting so presets are the same as on web
  - verified (2026-11-08 16:13+0100 by dell26/agent-1): cd android && ./gradlew testDebugUnitTest on bffd4e704 -> NextPresetsTest (reads/writes exact web filter-presets.ts JSON, same-name replace, delete, unreadable kept), NextViewModelPresetsTest + NextViewModelPresetEdgeCasesTest (apply/toggle, save current, delete, PATCH via UserSettingsRepository nextActionPresets, conflict/offline/not-loaded, serialized writes, 50 limit) and NextScreenPresetsTest pass, 0 failures
- [x] New strings are in English and German (`res/values/` + `res/values-de/`)
  - verified (2026-11-08 16:13+0100 by dell26/agent-1): 21 new strings + plurals next_preset_too_many in res/values and res/values-de strings_next_today.xml; ./gradlew lintDebug (MissingTranslation) on bffd4e704 -> pass; NextScreenPresetsTest German qualifier test shows Fällig/Nächste 7 Tage/Alle Bereiche/Filter speichern… -> pass
- [x] Unit/Compose tests cover the new behavior; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass
  - verified (2026-11-08 16:13+0100 by dell26/agent-1): cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport on bffd4e704 -> exit 0, 3672 tests, 0 failures (task files 99.4% lines); sh scripts/check.sh on bffd4e704 -> all checks passed

## Notes
Planned 2026-11-08 from the owner's UX request (web/Android parity, mail images, back navigation, mail search); the parity inventory and decisions are in `docs/PARITY.md`.
- ASSUMPTION (2026-11-08 15:48+0100 by dell26/agent-1): Edited core/settings/UserSettings.kt (outside touches, small): UserSettings.nextActionPresets (raw JSON texts) and UserSettingsChange.nextActionPresets, because presets are stored through the shared UserSettingsRepository (PATCH with If-Match).
- ASSUMPTION (2026-11-08 15:48+0100 by dell26/agent-1): Due 'this week' means today plus the next 6 days (label 'Next 7 days'), same as the server's due=week and the web Next filter, for parity; 'any' is an explicit chip that turns the due filter off.
- ASSUMPTION (2026-11-08 15:48+0100 by dell26/agent-1): Preset format equals web filter-presets.ts: {name, filter:{context,energy,creativity,maxMinutes,area,project,due}}; same name (any case) replaces in place; max 50 presets, name max 80; unreadable stored entries are kept as-is on write. Applying the active preset clears the filter (web behavior). A failed write (409/412 -> settings reloaded) shows a message; no automatic retry.
- ASSUMPTION (2026-11-08 15:48+0100 by dell26/agent-1): Area/project filters are applied client-side on the cached Next list (area via the cached project's areaId), so they work offline; areas come from ProjectWorkRepository.observeAreas (online only; the area menu is empty offline). Project choices are active projects, of the chosen area if one is chosen; choosing another area clears a project of a different area (web behavior).
