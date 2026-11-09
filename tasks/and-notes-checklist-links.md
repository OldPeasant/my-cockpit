---
title: Note editor: checklist toggle in preview and [[ link autocomplete
status: in_progress
owner: felix/agent-1
deps: [and-notes-list, and-links-panel]
prio: 2
parent: and-notes-editor
lease_until: 2026-11-09T03:00:36Z
releases: 0
extended: 
hold: 
---
## Goal
Part of `and-notes-editor` (Android note editor: tags, checklists, links, revisions), which was split.
Goal: Tapping a checklist item in the preview toggles it and saves like an edit; typing `[[` in the editor offers search-as-you-type and inserts `[[type:id]]`. Helpers `Markdown.toggleChecklist`, `Markdown.linkQuery`, `Markdown.insertLink` and `MarkdownView(onToggleChecklist)` are already on `origin/wip/and-notes-editor` (no tests yet); wire them into NoteEditorScreen/NoteEditorViewModel. Touches android/.../cockpit/notes, its tests and strings_notes.xml (en + de).

## Acceptance criteria
- [x] Tapping a checklist item in preview toggles it (saved like an edit)
  - verified (2026-11-08 22:52+0100 by felix/agent-1): cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.notes.*' (Robolectric) -> pass; NoteEditorChecklistLinksViewModelTest toggle tests (body toggled, unsaved, autosave like edit; no toggle while loading/conflict) and NoteEditorChecklistLinksScreenTest 'tapping a checklist item in the view toggles it by its line' pass (VM 9/9, Screen 4/4, MarkdownChecklistLinksTest 12/12)
- [x] Typing `[[` offers search-as-you-type and inserts `[[type:id]]`
  - verified (2026-11-08 22:52+0100 by felix/agent-1): cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.notes.*' -> pass; VM tests: 250 ms debounced global search with trimmed query, offline/failed, stale results ignored; Screen test 'typing a link reports its query, and picking a hit inserts the link' inserts [[note:<id>]], max 6 hits; Markdown linkQuery/insertLink tests 12/12. No emulator: on-device IME not run
- [x] New strings are in English and German (`res/values/strings_notes.xml` + `res/values-de/strings_notes.xml`)
  - verified (2026-11-08 22:28+0100 by felix/agent-1): git diff 1f9b236ce..HEAD -- android/app/src/main/res -> 5 new notes_link_* strings (hint, searching, none, offline, failed), each in values/strings_notes.xml and values-de/strings_notes.xml; lintDebug (MissingTranslation) run with criterion 4
- [~] Unit/Compose tests cover the new behavior; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass
  - skipped (2026-11-08 23:03+0100 by felix/agent-1): ktlintCheck + notes tests pass, and lintDebug passed in the test-writer's full run, but sh scripts/check.sh fails on clean origin/main itself: AppStartRoutingTest 'logging out...clears the cached files' (auth/capture, not this task) -> see fix-android-logout-cache-test

## Notes
- Work done on `and-notes-editor` before the split is on `origin/wip/and-notes-editor` (if it exists): merge it first if your part builds on it.
- ASSUMPTION (2026-11-08 22:26+0100 by felix/agent-1): Android [[ autocomplete searches all item types via global search (250 ms debounce), shows up to 6 hits above the body field, inserts [[<lowercase type>:<id>]] like web/server; checklist tap in preview only when the note is editable (no conflict/loading).
- release by felix/agent-1: Implementation done (commits 2e03999cf, 15be26a4d) + Markdown helper tests (dc174e159). Criterion 3 verified. WIP ViewModel/screen tests from an unfinished test-writer run committed in 04839b060 (unverified, may not compile/pass). reviews/.../review.md: only finding CR-1 = missing VM/screen tests. Left: finish/run those tests, re-run test-writer (tests.md with coverage) and code-reviewer on final code, verify #1 #2 #4, quality.json, done.
- hold by felix/agent-1: Ready to finish: criteria 1-3 verified; review/tests/quality.json committed. Only blocker: sh scripts/check.sh fails on origin/main (AppStartRoutingTest logout cache test, reproduced on a clean origin/main worktree, unrelated to notes). After fix-android-logout-cache-test is done: unhold, then re-run check.sh, verify #4, add check.sh to quality.json validation, and run done.
