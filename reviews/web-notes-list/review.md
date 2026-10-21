# Code Review

Date: 2026-10-21

Task: web-notes-list - Notes: list and organization

Scope: second review of the final code; task commits e5d6a4fe, 248c986a, bdd9f206, 0214f865 on top of base 61709e7c (`git diff 61709e7c..0214f865`), committed changes only (working tree clean at review time)

Requirements:
- tasks/web-notes-list.md acceptance criteria and recorded assumptions (client-side search over all loaded pages; inline composer prefilled with the active tag; cards link to /notes/<id>; grid default stored in localStorage `cockpit.notes.view`; composer hidden in the archived view; one-line wiring edits in notes.routes.ts and app.spec.ts outside touches)
- SPEC.md §8.1 (Notes), §4.2 conventions (ETag/If-Match, Problem Details)
- AGENTS.md project notes and conventions
- Server contract: `server/.../notes/domain/NoteMarkdown.kt` (`titleOf`) and its tests, generated client `web/src/app/api/generated`
- Previous review (same file, revision 07ddc501) and `reviews/web-notes-list/fixes.md`

Git range:
- Branch: main
- HEAD: 0214f865
- Base/start: 61709e7c22e55f81443a4f5a6b6bac646098d079
- Commits reviewed: 4

Summary: 0 P0, 0 P1, 0 P2, 0 P3

Verification performed by the reviewer:
- `cd web && npx ng test --watch=false --include='src/app/features/notes/**/*.spec.ts' --include='src/app/app.spec.ts'` -> 5 files, 121 tests passed
- `cd web && npx eslint --max-warnings=0 src/app/features/notes src/app/app.spec.ts` -> no findings
- Full `npm run check` was not run by the reviewer. `python tasks.py verify web-notes-list` shows no evidence recorded yet for either criterion; the parent worker must record it (AC2 names `npm run check`) before `done`.

Previous findings, re-checked on this revision:
- CR-1 (stale-load and 404 tests): resolved. `notes-page.spec.ts` now has `shows the newest tag filter when an older response arrives last`, `ignores the failure of an outdated load` and `drops a note that was deleted elsewhere when pinning it (404)`. They flush the older response last and check the visible result.
- CR-2 (note created during a reload not shown): resolved. `create()` (`notes-page.ts:156-158`) starts a new `load()` when `notes()` is null, and that load supersedes the running one through `loadSeq`. `shows a note created while the list reloads by loading it again` covers it.
- CR-3 (Cancel/Escape during save): resolved. `closeComposer()` returns early while `saving()` (`notes-page.ts:129-132`), Cancel is `[disabled]="saving()"`, and `openComposer()` clears `createError`. A test covers Cancel and Escape during a failing save.
- CR-4 (excerpt repeats the derived title): resolved for the realistic cases. Lines are cleaned before the title line is chosen, and the blank-title check uses `title?.trim()` (`notes-filter.ts:40-51`). This matches `NoteMarkdown.titleOf` for empty-alt images (the form used by pasted images), inline links and empty list items. Tests cover these cases.

Card extraction (`note-card.ts` / `note-card.scss`): the markup, labels, `aria-pressed`, disabled state and `routerLink` match the previous inline card. `compact` maps to a host class that limits the list view to 3 lines. Pin and archive are outputs, and the page still owns the state and the `busy` set, so state has one owner. The page spec exercises the card through the rendered page.

Acceptance criteria assessment:
- AC1: implemented.
  - Grid/list toggle: masonry with CSS columns; the choice is persisted with a safe localStorage fallback.
  - Pinned first: `sortNotes`, with Pinned and Other notes groups.
  - Tag filter: server `tag` parameter; choosing the same tag again clears it.
  - Archived toggle: `archived=true|false`.
  - Search: client-side; every word must match title, text or tags.
  - Create note: inline composer, POST, active tag prefilled.
- AC2: Vitest tests cover filtering at the pure-function and component level (search, tag, archived) and pin ordering (`sortNotes`, pin and unpin reordering, search keeping pinned first), plus service paging. Evidence that `npm run check` passes still has to be recorded by the parent (see above).

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Review conclusion

No material issues found.
