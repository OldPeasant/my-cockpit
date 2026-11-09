# Code Review

Date: 2026-11-09

Task: and-notes-tags - Note editor: tag chips with suggestions

Scope: Task code commits b056900b3, 8dc50f014, c298915fe and 9cfe04f3e (the last rebased as 9f8ba2a2f, identical code in android/ notes sources, tests and resources), task base b6e7dc2da for the code diff. Third review round: verifies the fixes of the previous round's findings (R2-CR-1 comma path, R2-CR-2 clickable chip) and reviews the final code against the acceptance criteria. Committed changes only.

Requirements:
- tasks/and-notes-tags.md (acceptance criteria and recorded assumptions)
- AGENTS.md project notes (conventions, strings en + de, lint contract)
- Server tag rules in server/.../notes/domain/NoteService.kt (normalizeTags, MAX_TAG_LENGTH = 50, MAX_TAGS = 50)
- reviews/and-notes-tags/fixes.md (Round 1 and Round 2), reviews/and-notes-tags/tests.md

Git range:
- Branch: main
- HEAD: 9f8ba2a2f800b9c253bffe63423f66484a6e0675
- Base/start: b6e7dc2da (task base); claim 1bf4ebbbe
- Commits reviewed: 4 task code commits (b056900b3, 8dc50f014, c298915fe, 9cfe04f3e / rebased 9f8ba2a2f)

Summary: 0 P0, 0 P1, 0 P2, 2 P3

## Verification of previous findings

- R2-CR-1 (comma path): fixed. `take()` in NoteTagsEditor.kt splits on comma and line break, offers only finished parts (all parts on Enter or focus loss) to `onAdd`, and `keptTagInput` keeps refused parts verbatim, each followed by its comma. The text is stable across keystrokes (no growing spaces), a refused `home,` stays separated from the next tag, and no part passed to `onAdd` can contain a comma. `NoteEditorViewModel.addTag` additionally refuses tags containing a comma or line break. Covered by new Compose tests (key-by-key `home,work`, `home, wor` then focus loss), a `keptTagInput` unit test and a view-model test.
- R2-CR-2 (clickable chip): fixed. `TagChip` is a non-clickable `Surface` with a separate `IconButton` carrying the localized content description `notes_tag_remove`; a Compose test asserts no OnClick on the chip node and OnClick, Button role and description on the remove button.
- Round 1 findings CR-1..CR-5: still fixed in the final code (remove only via the X, rejected input kept, `loadKnownTags` cancels the previous job).

## Acceptance criteria

1. Edit tags as chips with suggestions from existing tags, saved with the note: implemented. Tags are part of `NoteDraft`, sent in POST/PATCH only when they differ from the base note, included in `isSaved()`, adopted in the server spelling after a save when unchanged meanwhile, and reset on "use theirs" and flag conflicts. Client limits match the server (trim, leading `#`, 50 chars, 50 tags, case-insensitive duplicates). Suggestions come from `notes.tags()` sorted by count, minus the note's tags, filtered and capped at 8.
2. Strings in English and German: `notes_tag_add` and `notes_tag_remove` present in both `values/strings_notes.xml` and `values-de/strings_notes.xml`.
3. Tests and checks: new Compose and view-model tests cover the behavior, including the round-2 fixes. Per the instruction for this round, Gradle was not run by this reviewer; fixes.md Round 2 reports `./gradlew ktlintCheck testDebugUnitTest lintDebug` as BUILD SUCCESSFUL. `sh scripts/check.sh` evidence must be recorded by the parent worker on the final code.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | Suggestions are filtered by the whole input, so they vanish while a refused tag stays in it**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/notes/NoteTagsEditor.kt:171`
    - Category: Correctness
    - Problem: `matchingTags(suggestions, text)` uses the full input text. Since the R2-CR-1 fix intentionally keeps refused parts in the input (for example `home,` when `home` is a duplicate), typing `wo` afterwards gives `home,wo`, which matches no known tag, so no suggestions are shown for the tag actually being typed.
    - Why it matters: Suggestions are a required part of the feature; after any refused tag they silently stop working until the user deletes the refused text.
    - Suggested fix: Filter by the part still being typed, `splitTagInput(text).second`, and add a Compose test with a refused part followed by a partial tag.

- [ ] **CR-2 | Tapping a suggestion clears the whole input, including refused text, and ignores whether the tag was added**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/notes/NoteTagsEditor.kt:177-178`
    - Category: Correctness
    - Problem: The suggestion `onClick` calls `onAdd(tag)` and then sets `text = ""` unconditionally. Refused parts kept in the input are discarded, and when `onAdd` returns false (for example at 50 tags) the typed text is also cleared with no visible effect.
    - Why it matters: It contradicts the rule this task established for the input (text that was not added stays, as fixed in round 1 CR-4 and round 2 R2-CR-1); the user loses typed text silently.
    - Suggested fix: Only when `onAdd(tag)` returns true, replace just the part being typed: `text = keptTagInput(splitTagInput(text).first.filter { it.isNotBlank() }, "")` (or keep the text unchanged when it is false), with a Compose test.

## Review conclusion

No material issues found. The round-2 fixes are correct and tested; the two P3 findings are minor follow-ups that the parent worker may fix or defer.
