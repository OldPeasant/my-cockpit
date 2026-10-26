---
title: German UI
status: in_progress
owner: dell26/agent-2
deps: [p6-smoke]
touches: [web/src/locale,web/angular.json,android/app/src/main/res/values-de]
prio: 3
lease_until: 2026-10-26T12:40:40Z
---
## Goal
German translations (SPEC.md §4.2).

## Acceptance criteria
- [x] Web builds a `de` locale (all messages translated, no missing-translation warnings); Android values-de complete (lint `MissingTranslation` clean)
  - verified (2026-10-26 13:01+0100 by dell26/agent-2): cd web && npm run build:de (ng build --configuration production,de, i18nMissingTranslation=error) -> exit 0, dist/web/browser/de with lang=de, 0 translation diagnostics, 1708/1708 targets (messages-de.spec.ts 7 passed; removing a trans-unit makes the build fail 'No translation found'); cd android && ./gradlew lintDebug -> BUILD SUCCESSFUL, no MissingTranslation (removing one values-de string makes lint fail with MissingTranslation)

## Notes
- ASSUMPTION (2026-10-26 12:39+0100 by dell26/agent-2): German UI uses standard German orthography (with ß, not Swiss ss) and informal 'du'; terms fixed in a glossary (e.g. Inbox=Eingang, Weekly review=Wochenrückblick, Event=Termin). SPEC only says 'German UI'.
- ASSUMPTION (2026-10-26 12:39+0100 by dell26/agent-2): Web: 'de' is an extra build configuration (ng build --configuration production,de -> dist/web/browser/de, npm run build:de) with i18nMissingTranslation=error; the default build, the Docker image and .taskcheck stay English-only. Serving/choosing the German bundle at runtime (locale switch, server routing) is not in this task's criteria.
- ASSUMPTION (2026-10-26 12:39+0100 by dell26/agent-2): Android values-de makes lint MissingTranslation (warningsAsErrors) mandatory: every future string in values/ needs a German entry or the Android check fails. Accepted as the intended contract.
