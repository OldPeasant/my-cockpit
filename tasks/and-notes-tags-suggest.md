---
title: Note tags editor: suggestion tap keeps refused text, suggestions filter by current part
status: done
owner: dell26/agent-1
deps: [and-notes-tags]
touches: [android/app/src/main/java/ch/sonensei/cockpit/notes,android/app/src/test/java/ch/sonensei/cockpit/notes]
prio: 2
lease_until: 
---
## Goal
Follow-up of the and-notes-tags round-3 review (reviews/and-notes-tags/review.md, P3 CR-1, CR-2).
In android `notes/NoteTagsEditor.kt`, refused tags stay in the input (e.g. `home,wo`). Suggestions are
filtered by the whole input, so none match. Tapping a suggestion always clears the input, even when
`onAdd` refuses the tag.

## Acceptance criteria
- [x] Suggestions are filtered by the part being typed (`splitTagInput(text).second`), not the whole input
  - verified (2026-11-09 09:04+0100 by dell26/agent-1): NoteTagsEditor.kt:171 filters by splitTagInput(text).second; ./gradlew testDebugUnitTest --tests '*NoteTagsEditorTest*' -> 23 passed, incl. 'after a refused tag, suggestions follow the part being typed...' (home,wo shows #Work/#network)
- [x] Tapping a suggestion keeps refused parts and clears only the typed part on success; if `onAdd` refuses, the input is unchanged
  - verified (2026-11-09 09:04+0100 by dell26/agent-1): ./gradlew testDebugUnitTest --tests '*NoteTagsEditorTest*' -> 23 passed: tap on accepted suggestion after refused 'home,wo' leaves 'home,'; 'home, trip, wo' leaves 'home, trip,'; refused tap leaves 'home,wo' unchanged
- [x] Compose tests cover both; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass
  - verified (2026-11-09 09:04+0100 by dell26/agent-1): Compose tests in NoteTagsEditorTest cover both; cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug -> BUILD SUCCESSFUL; sh scripts/check.sh -> all checks passed (at c585ae6d5)

## Notes
- done by dell26/agent-1: `sh scripts/check.sh` passed in 73s on 373b2a7d2; criteria verified 3/3
