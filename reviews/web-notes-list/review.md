# Code Review

Date: 2026-10-21

Task: web-notes-list - Notes: list and organization

Scope: task commits 7fc1d346 and 07ddc501 on top of base 37a3c6cc (`git diff 37a3c6cc..07ddc501`), committed changes only

Requirements:
- tasks/web-notes-list.md acceptance criteria and recorded assumptions (client-side search over all loaded pages; inline composer; cards link to /notes/<id>; grid default stored in localStorage; composer hidden in archived view; one-line wiring edits outside touches)
- SPEC.md §8.1 (Notes), §4.2 conventions (ETag/If-Match, Problem Details)
- AGENTS.md project notes and conventions
- Server contract: `server/.../notes/api/NoteController.kt`, `core/web/ETags.kt`, `notes/domain/NoteMarkdown.kt`, generated client `web/src/app/api/generated/fn/notes/*`

Git range:
- Branch: main
- HEAD: 07ddc501
- Base/start: 37a3c6cc5f3abd1b7bf6a1488e2fec43fce3c1e8
- Commits reviewed: 2

Summary: 0 P0, 0 P1, 1 P2, 3 P3

Verification performed by the reviewer:
- `npx ng test --watch=false --include='src/app/features/notes/list/**/*.spec.ts' --include='src/app/app.spec.ts'` -> 4 files, 71 tests passed
- `npx eslint --max-warnings=0 src/app/features/notes src/app/app.spec.ts` -> no findings
- Full `npm run check` was not run by the reviewer; `python tasks.py verify web-notes-list` shows no evidence recorded yet for either criterion (the parent worker still has to record it).

Acceptance criteria assessment:
- AC1: grid/list toggle (CSS-columns masonry, persisted with safe localStorage fallback), pinned first (`sortNotes` + Pinned/Other groups), tag filter (server `tag` param, toggle to clear), archived toggle (`archived=true|false`), search (client-side, every word over title/text/tags), create note (inline composer, POST, tag prefilled) are all implemented. If-Match format `"<version>"` matches `ETags.of`. Pin/archive use PATCH with only the changed field, as the server's partial-patch contract allows.
- AC2: Vitest tests cover filtering (search, tag filter, archived) and pin ordering at both the pure-function and component level, plus the service paging. Passing `npm run check` is still to be evidenced by the parent.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | Stale-load protection and 404 handling are untested**
    - File: `web/src/app/features/notes/list/notes-page.ts:72` (and `:232`)
    - Category: Tests
    - Problem: `load()` discards results of an older filter via `loadSeq`, and `run()` drops a note that answers 404. Neither path has a test: no spec starts two loads (e.g. click a tag, then the archived toggle before the first list returns) and flushes them out of order, and no spec flushes a 404 for a pin/archive PATCH.
    - Why it matters: tag and archive switching are the core filtering behavior of this task; a regression in the sequencing (e.g. removing the seq check, or a later refactor to an independent method) would silently show the notes of the wrong filter, and the unit suite would stay green.
    - Suggested fix: add a component test that triggers two loads, flushes the newer one first and then the older one (success and failure), and asserts the newer list stays and no load error is shown; add one that flushes 404 for a pin and asserts the card is removed and no action error is shown.

## P3 Low

- [ ] **CR-2 | A note created while the list is reloading does not appear**
    - File: `web/src/app/features/notes/list/notes-page.ts:149-150`
    - Category: Correctness
    - Problem: the composer stays usable while `notes()` is `null` (after a tag change or a 409/412 reload). `create()` then calls `notes.update(notes => notes ? [note, ...notes] : notes)`, which is a no-op, and the in-flight GET may have been answered before the POST committed. The user gets "Note saved" but the note is missing from the view until the next reload.
    - Why it matters: confusing "lost note" impression in a realistic sequence (switch tag, write quickly); data is not lost.
    - Suggested fix: after a successful create while a load is pending (or whenever `notes()` is null), start a fresh `load()`; alternatively keep the previous list visible during reloads and merge the created note into the result of the pending load.

- [ ] **CR-3 | Cancel/Escape during a running save loses the draft and leaves a stale error**
    - File: `web/src/app/features/notes/list/notes-page.html:95,117` / `notes-page.ts:127-133,155`
    - Category: Correctness
    - Problem: Cancel and Escape are not blocked while `saving()` is true. `closeComposer()` clears the draft; if the POST then fails, `createError` is set on the closed composer, so the error is not seen, the draft is gone, and the old error appears the next time the composer is opened (`openComposer()` does not clear `createError`).
    - Why it matters: a failed save can go unnoticed and the typed text is lost.
    - Suggested fix: disable Cancel/ignore Escape while `saving()`, or clear `createError` in `openComposer()` and surface the failure via a notification when the composer was closed.

- [ ] **CR-4 | Excerpt can repeat the derived title when the body starts with a non-text line**
    - File: `web/src/app/features/notes/list/notes-filter.ts:41-44`
    - Category: Correctness
    - Problem: for untitled notes the excerpt drops the first non-blank raw line, but the server derives `displayTitle` from the first line whose plain text is non-blank (`NoteMarkdown.titleOf`, which strips images and link tokens). For a body starting with an image line (`![](cockpit-file:...)`, a typical pasted-image note) or a `[[type:id]]` line, the raw first line is dropped (it renders empty anyway) and the real title line is shown again as the first excerpt line. The client check `!note.title` also treats a whitespace-only title as a title, while the server treats it as absent.
    - Why it matters: duplicated title text on cards for image-first notes; cosmetic.
    - Suggested fix: skip the first line whose cleaned plain text is non-blank (apply the same cleanup before choosing the title line), and test with `title?.trim()`.

## Review conclusion

Material issues found; fix before completing the task. No blocking (P0/P1) defects: the acceptance criteria are implemented and the focused tests pass. CR-1 is a test gap the fixer should close; CR-2 to CR-4 are minor and may be fixed or explicitly dispositioned in `quality.json`.
