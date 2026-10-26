# Fix Report

Date: 2026-10-26

Task: notes-markdown-escapes - Robust plain text of note Markdown with backslash escapes

Scope: current task; finding TW-1 from `reviews/notes-markdown-escapes/tests.md`

## Fixed findings

### TW-1 - titleOf keeps the backslash of a hard line break as a stray character

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/notes/domain/NoteMarkdown.kt`

Problem:
`NoteMarkdown.titleOf` split the raw Markdown into lines and parsed each line on its own. A first line ending in a
backslash hard break (`\` + newline) was then parsed without its newline, so the backslash stayed as literal text
(`F: [x c@d.e\` instead of `F: [x c@d.e`).

Fix:
`titleOf` now takes the first non-blank line of `plainText(markdown)` of the whole body, so constructs spanning lines
(hard breaks, fenced code, setext headings) are read as Markdown. `plainText` already puts every block and line break
on its own line, so the "first line with text" rule is unchanged. The line-by-line split dated from the original
`notes-api` implementation and had no behavior that depended on it: all existing `titleOf` tests (headings, task list
items, images and inline links only, entities on blank-looking lines, length cut counting resolved escapes) pass
unchanged. No test was modified.

Validation:
- `cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.notes.*' --tests 'ch.sonensei.cockpit.mail.gtd.*'` - Pass (BUILD SUCCESSFUL; `NoteMarkdownTests` 29 tests, 0 failures, including the TW-1 test)

## Not fixed

None.

## Validation summary

Focused tests:
- `cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.notes.*' --tests 'ch.sonensei.cockpit.mail.gtd.*'` - Pass

Full test suite:
- `cd server && ./gradlew check` - 4611 tests, 2 failed, both from the environment and not from this change:
  `BackupRoundTripTest > every contributor's data survives export, wipe and import unchanged` and
  `FilesBackupContributorTests > a 60 MB file is streamed through export and import ...` failed with
  `java.io.IOException: Disk quota exceeded` while writing backup/file data (the shared `/tmp` tmpfs was 80% full,
  several agents use it). Neither test touches `NoteMarkdown.titleOf`. Every other test passed, including all
  `notes.*` and `mail.gtd.*` tests. The parent worker should re-run them (or `.taskcheck`) when disk space is free.

Lint/type-check/build:
- `cd server && ./gradlew ktlintCheck` - Pass (part of the focused command)

`.taskcheck`:
- Not run by the fixer: the change is server-only and covered by the server check above; the parent worker runs `.taskcheck` before `done`.

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
