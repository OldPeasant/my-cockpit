# Code Review

Date: 2026-10-26

Task: notes-markdown-escapes - Robust plain text of note Markdown with backslash escapes

Scope: implementation commit 4c710811e on top of base 817b3fc44 (`git diff 817b3fc44 4c710811e`), production code in `server/src/main` plus the test changes in that commit. Tests the test-writer is adding at the same time are out of scope.

Requirements:
- tasks/notes-markdown-escapes.md (acceptance criteria, notes, recorded assumption)
- AGENTS.md project notes (conventions, lint, security: mail content must not create inline links)
- SPEC.md / docs/ARCHITECTURE.md conventions as referenced by AGENTS.md (module boundaries, no new libraries)
- Parent agent instructions (check plain text, title and search regressions for other callers, security of mail-controlled content)

Git range:
- Branch: main
- HEAD: 93d118e171daa7d4d6095fd0049d95e130d8a653 (same `server/src/main` content as 4c710811e43639ce41038bf3a729dc5909e28a96)
- Base/start: 817b3fc44f637e82d3005d33d9bcb79a35ff91e9
- Commits reviewed: 1

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Review notes (verified, no action required)

- Criterion 1: `NoteMarkdown.collect` replaces `TextCollectingVisitor`. Text gets `Escaping.unescapeString`, which resolves backslash escapes and entities in a single pass, so `\&amp;` stays `&amp;` and is not unescaped twice. Code spans, fenced blocks and indented blocks stay verbatim. HtmlEntity, AutoLink/MailLink (`node.text`), and soft and hard breaks are each handled explicitly. Undefined RefNodes rebuild their brackets from base offsets around the children, so `[\[f]]`, `[b][c]`, `[d][]` and `![e]` stay literal. The exact repro string from the task and the entity cases named in the task notes are covered by the new `NoteMarkdownTests`. The latest local results (`server/build/test-results`) show NoteMarkdownTests with 14/14 passing and MailGtdWebTests with 46/46 passing, and Gradle enables `-ea` for Test tasks.
- Criterion 3: `neutralizeInlineLinks` is now `text.replace(BRACKET_PAIR, "[ ")`, the `ESCAPE` regex and U+2216 are gone, and the MailGtdWebTests escape assertions expect the original backslashes.
- Security: mail content still cannot produce `[[type:id]]`. Every `[` that is followed by `[` gets a space, which also covers `[[[` and `\[[`. Truncation runs afterwards and cannot create a new pair. A mail's own `[\[task:id]]` now stays verbatim. The server's `inlineLinks` regex, the web renderer (`note-markdown.ts` `INLINE_LINK` on raw text, no backslash processing) and the Android `MdInline` regex all match only a literal `[[`, so verbatim escapes do not become chips or links. The unescaped `[[...]]` that can now appear in plain text goes only to the search index and derived titles. Search snippets are not rendered through `innerHTML`.
- Other callers (`NoteService` and `JournalEntryService` search upsert, `Note.displayTitle` through `titleOf`) only get plain text and titles. The behavior changes listed in the recorded assumption (table cells separated by a space, undefined brackets kept, mail addresses kept, correct entities) are improvements, and the existing plain-text and title tests still pass. HTML blocks and inline HTML stay excluded, as before.
- Conventions: no new dependency (flexmark is already on the classpath), the change stays inside the task's `touches`, and the deviation is recorded with `tasks.py assume`.

## Review conclusion

No material issues found.
