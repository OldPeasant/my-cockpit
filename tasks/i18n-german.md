---
title: German UI
status: in_progress
owner: dell26/agent-2
deps: [p6-smoke]
touches: [web/src/locale,web/angular.json,android/app/src/main/res/values-de]
prio: 3
lease_until: 2026-10-26T11:56:39Z
---
## Goal
German translations (SPEC.md §4.2).

## Acceptance criteria
- [ ] Web builds a `de` locale (all messages translated, no missing-translation warnings); Android values-de complete (lint `MissingTranslation` clean)

## Notes
- ASSUMPTION (2026-10-26 12:39+0100 by dell26/agent-2): German UI uses standard German orthography (with ß, not Swiss ss) and informal 'du'; terms fixed in a glossary (e.g. Inbox=Eingang, Weekly review=Wochenrückblick, Event=Termin). SPEC only says 'German UI'.
