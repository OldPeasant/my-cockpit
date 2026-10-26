# Code Review

Date: 2026-10-26

Task: notes-markdown-escapes - Robust plain text of note Markdown with backslash escapes

Scope: production code of the task, `git diff 6c5a19ed8 5616eb928 -- server/src/main` (commits f51cf2c56, 93e01d206, 5616eb928), re-review of the final revision including the TW-1 fix (`titleOf` derived from the plain text of the whole body). Tests added concurrently by the test-writer are out of scope.

Requirements:
- `tasks/notes-markdown-escapes.md` acceptance criteria and notes (incl. the recorded plainText assumption)
- `SPEC.md`, `docs/ARCHITECTURE.md`, `AGENTS.md` project notes
- `reviews/notes-markdown-escapes/fixes.md` (TW-1)

Git range:
- Branch: main
- HEAD: 5616eb9288a61c7d6d6626fcba2f924597e72186
- Base/start: 6c5a19ed8ba198ed0aa1dd900a4a8c79d36f800d
- Commits reviewed: 3

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | Derived titles now parse the whole note body on every `displayTitle` read**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/notes/domain/NoteMarkdown.kt:138`
    - Category: Quality
    - Problem: `titleOf` now calls `plainText(markdown)` on the full body; before, it parsed line by line and stopped at the first line with text. `Note.displayTitle` (`Note.kt:43`) is a computed getter used by `NoteResponse.of` for every note of a list page (up to 200 per page, bodies up to 100 000 characters), by `NoteItemResolver`, `NoteTrashContributor`, and twice per note in `NotesBackupContributor`. `NoteService` indexing (`NoteService.kt:39`) computes `displayTitle` and `plainText` of the same body, so an untitled note is parsed twice per save.
    - Why it matters: the cost grows with body size, not with title length. Typical notes are small, so this is not a defect today. In the worst case (a page of 200 large untitled notes) one list request parses several MB of Markdown.
    - Suggested fix: optional. Stop collecting once the first non-blank line is found (for example, walk the top-level blocks and parse/collect only until a block yields text), or reuse one `plainText` result where both are needed (search indexing). Correctness stays as it is now.

## Review conclusion

No material issues found.

Notes from the verification (no findings):
- Criterion 1: `collect` resolves backslash escapes and entities per `Text`/`HtmlEntity` node via `Escaping.unescapeString`, so it no longer relies on `TextCollectingVisitor`'s length accounting, which caused the assertion error and garbled text. Code spans, fenced and indented code stay verbatim. Undefined `RefNode` brackets are emitted as text from base-sequence offsets, which are consistent because the `INLINE_LINK` removal happens before parsing. The repro strings are covered in `NoteMarkdownTests` for both `plainText` and `titleOf`.
- `titleOf` change (TW-1): `plainText` already ends a line at every block, soft and hard break, and table row, so "first non-blank line of plainText" matches the previous per-line rule for paragraphs, ATX/setext headings, list/task items, block quotes, and fenced/indented code (the info string is not a child node, so it is not collected). Behavior that differs from before: a leading table now gives its header cells separated by spaces (`Day Place`) instead of the raw `| Day | Place |` line, and the text inside a leading multi-line HTML block or HTML comment is skipped instead of becoming the title. Both follow the recorded assumption (HTML left out, cells space-separated) and are improvements. Callers: `JournalEntry.displayTitle` is `Journal {date}` and does not use `titleOf`. Journal and note search indexing only use `plainText`, which TW-1 did not change. The `MAX_DERIVED_TITLE_LENGTH` cut still applies to the resolved first line only.
- Criterion 3: `MailGtdService.neutralizeInlineLinks` now only rewrites `[[` to `[ [`. The `ESCAPE` regex and U+2216 are gone from `server/src` (`git grep` finds no `∖` or `2216`). `[\[type:id]]` from a mail cannot form an inline link: the server `INLINE_LINK` regex needs a literal `[[`, and the web renderer (`web/src/app/features/notes/editor/note-markdown.ts`) does not unescape backslashes before it matches inline links.
- Test evidence: this environment did not allow running Gradle for the reviewer. The existing JUnit XML in `server/build/test-results/test/` (written 2026-10-26 03:43Z, after the TW-1 fix compiled) shows `NoteMarkdownTests` 29/0 failures, `MailGtdWebTests` 47/0, and `MailGtdMailServerTests` 5/0. Gradle's `Test` task runs with assertions enabled by default. Before `done`, the parent worker should record its own runs of the criterion commands (`./gradlew test --tests 'ch.sonensei.cockpit.notes.*'` and `--tests 'ch.sonensei.cockpit.mail.gtd.*'`) on the final code.
