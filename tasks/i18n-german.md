---
title: German UI
status: in_progress
owner: dell26/agent-2
deps: [p6-smoke]
touches: [web/src/locale,web/angular.json,android/app/src/main/res/values-de]
prio: 3
lease_until: 2026-10-26T12:20:29Z
---
## Goal
German translations (SPEC.md §4.2).

## Acceptance criteria
- [ ] Web builds a `de` locale (all messages translated, no missing-translation warnings); Android values-de complete (lint `MissingTranslation` clean)

## Notes
- ASSUMPTION (2026-10-26 12:39+0100 by dell26/agent-2): German UI uses standard German orthography (with ß, not Swiss ss) and informal 'du'; terms fixed in a glossary (e.g. Inbox=Eingang, Weekly review=Wochenrückblick, Event=Termin). SPEC only says 'German UI'.
- ASSUMPTION (2026-10-26 12:39+0100 by dell26/agent-2): Web: 'de' is an extra build configuration (ng build --configuration production,de -> dist/web/browser/de, npm run build:de) with i18nMissingTranslation=error; the default build, the Docker image and .taskcheck stay English-only. Serving/choosing the German bundle at runtime (locale switch, server routing) is not in this task's criteria.
- ASSUMPTION (2026-10-26 12:39+0100 by dell26/agent-2): Android values-de makes lint MissingTranslation (warningsAsErrors) mandatory: every future string in values/ needs a German entry or the Android check fails. Accepted as the intended contract.
