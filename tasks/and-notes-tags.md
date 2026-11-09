---
title: Note editor: tag chips with suggestions
status: in_progress
owner: dell26/agent-1
deps: [and-notes-list, and-links-panel, and-notes-checklist-links]
prio: 2
parent: and-notes-editor
lease_until: 2026-11-09T06:37:49Z
releases: 0
extended: 
hold: 
---
## Goal
Part of `and-notes-editor` (Android note editor: tags, checklists, links, revisions), which was split.
Goal: Edit a note's tags in the Android editor as chips, with suggestions from existing tags (web: `note-editor-page.html`). Touches android/.../cockpit/notes, its tests and strings_notes.xml (en + de).

## Acceptance criteria
- [x] Edit tags (chips with suggestions from existing tags), saved with the note
  - verified (2026-11-09 07:18+0100 by dell26/agent-1): NoteTagsEditor (chips + suggestions from tags in use) and NoteEditorViewModel tag save; android/gradlew -p android testDebugUnitTest --tests 'ch.sonensei.cockpit.notes.*' -> 277 tests pass incl. NoteTagsEditorTest (add/remove/suggest Compose tests) and NoteEditorTagsViewModelTest (tags sent in PATCH/POST)
- [x] New strings are in English and German (`res/values/strings_notes.xml` + `res/values-de/strings_notes.xml`)
  - verified (2026-11-09 07:10+0100 by dell26/agent-1): git diff 1bf4ebbbe HEAD -- android/**/strings*.xml -> notes_tag_add + notes_tag_remove added to values/strings_notes.xml (en) and values-de/strings_notes.xml (de), no other new strings; lintDebug (MissingTranslation) passes
- [x] Unit/Compose tests cover the new behavior; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass
  - verified (2026-11-09 07:18+0100 by dell26/agent-1): android/gradlew -p android ktlintCheck createDebugUnitTestCoverageReport lintDebug -> 5294 tests 0 failures, lint pass; sh scripts/check.sh -> exit 0 all checks passed (on final code 9f8ba2a2f, reviews/and-notes-tags/tests.md)

## Notes
- Work done on `and-notes-editor` before the split is on `origin/wip/and-notes-editor` (if it exists): merge it first if your part builds on it.
- ASSUMPTION (2026-11-09 04:44+0100 by dell26/agent-1): Android tags editor is shown in both the rendered view and the plain editor (below the title), editable whenever the note is; suggestions are the tags in use (most used first) minus the note's, filtered by the typed text, max 8, as tappable chips (web uses a datalist).
- ASSUMPTION (2026-11-09 04:44+0100 by dell26/agent-1): Android sends 'tags' in the note PATCH/POST only when they differ from the saved note's (create: when any), and adopts the server's spelling after a save when unchanged meanwhile; skipped merging origin/wip/and-notes-editor (its checklist/link WIP is superseded by the done and-notes-checklist-links).
- release by dell26/agent-1: Implemented + committed (acf2ddefb) with tests by test-writer (5076d1c30, reviews/and-notes-tags/tests.md: 266 notes tests pass, check.sh passed) and review (review.md). Left: run fixer for review P3s CR-3 (InputChip onClick removes the tag: make only the trailing X remove), CR-4 (rejected tag clears input silently: keep text when onAdd returns false), CR-5 (loadKnownTags: cancel previous job); CR-1/CR-2 already resolved by test-writer (record as fixed via fixer verification, see memory: fixed finding needs fixes.md). Then re-run test-writer + code-reviewer on final code, verify 3 criteria, quality.json, done.
- release by dell26/agent-1: Fixer done + committed (c298915fe, reviews/and-notes-tags/fixes.md): CR-3/4/5 fixed, CR-1/2 verified fixed; fixer ran ktlintCheck, full testDebugUnitTest, lintDebug -> pass. Left: re-run test-writer + code-reviewer in parallel on final code (base b6e7dc2da, commits b056900b3 8dc50f014 c298915fe), verify 3 criteria, run check.sh, quality.json (fixes_required=true), done.
- release by dell26/agent-1: Re-review done (245b2383f, review.md): old CR-1..5 all fixed, but NEW findings: CR-1 P2 NoteTagsEditor.kt:114-117 refused tag + comma path rebuilds text so 'home,' loses comma and merges with next tag / spaces accumulate and tags with commas get saved (fix: keep refused parts out, reject or split commas in addTag/commit, add Compose test); CR-2 P3 chip onClick={} still clickable for a11y (use non-clickable container + remove button). Test re-run committed (21cf2718c, tests.md, +2 tests, 272 notes tests + check.sh pass). Left: fixer for new CR-1/CR-2 (fixes.md must keep old CR-1..5 entries too: append), then re-run test-writer + code-reviewer, verify 3 criteria, quality.json (old CR-1/2 P1 fixed, CR-3..5 P3 fixed, plus new ones; use distinct ids e.g. R2-CR-1), done.
