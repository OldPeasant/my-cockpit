---
title: Note tags editor: suggestion tap keeps refused text, suggestions filter by current part
status: todo
owner:
deps: [and-notes-tags]
touches: [android/app/src/main/java/ch/sonensei/cockpit/notes,android/app/src/test/java/ch/sonensei/cockpit/notes]
prio: 2
---
## Goal
Follow-up of the and-notes-tags round-3 review (reviews/and-notes-tags/review.md, P3 CR-1, CR-2).
In android `notes/NoteTagsEditor.kt`, refused tags stay in the input (e.g. `home,wo`). Suggestions are
filtered by the whole input, so none match. Tapping a suggestion always clears the input, even when
`onAdd` refuses the tag.

## Acceptance criteria
- [ ] Suggestions are filtered by the part being typed (`splitTagInput(text).second`), not the whole input
- [ ] Tapping a suggestion keeps refused parts and clears only the typed part on success; if `onAdd` refuses, the input is unchanged
- [ ] Compose tests cover both; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass

## Notes
