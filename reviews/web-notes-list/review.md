# Code Review

Date: 2026-10-21

Task: web-notes-list - Notes: list and organization

Scope: review of the final code at 18e0d538; task commits e5d6a4fe, 248c986a, bdd9f206, 0214f865, 18e0d538 on top of base 61709e7c (`git diff 61709e7c..18e0d538`), committed changes only (working tree clean). Production code was reviewed in full at 0214f865. Since then the only code change is the new `web/src/app/features/notes/list/note-card.spec.ts`; the other changes are in `reviews/` only.

Requirements:
- tasks/web-notes-list.md acceptance criteria and recorded assumptions (client-side search over all loaded pages; inline composer prefilled with the active tag; cards link to /notes/<id>; grid default stored in localStorage `cockpit.notes.view`; composer hidden in the archived view; one-line wiring edits in notes.routes.ts and app.spec.ts outside touches)
- SPEC.md §8.1 (Notes), §4.2 conventions (ETag/If-Match, Problem Details)
- AGENTS.md project notes and conventions
- Server contract: `server/.../notes/domain/NoteMarkdown.kt` (`titleOf`) and its tests, generated client `web/src/app/api/generated`
- First review (revision 07ddc501) and `reviews/web-notes-list/fixes.md`

Git range:
- Branch: main
- HEAD: 18e0d5386cc904840b85138202a4a6abe87608c7
- Base/start: 61709e7c22e55f81443a4f5a6b6bac646098d079
- Commits reviewed: 5

Summary: 0 P0, 0 P1, 0 P2, 1 P3

Verification performed by the reviewer:
- At 0214f865: `cd web && npx ng test --watch=false --include='src/app/features/notes/**/*.spec.ts' --include='src/app/app.spec.ts'` -> 5 files, 121 tests passed
- At 18e0d538: `cd web && npx ng test --watch=false --include='src/app/features/notes/list/note-card.spec.ts'` -> 1 file, 8 tests passed
- At 18e0d538: `cd web && npx eslint --max-warnings=0 src/app/features/notes/list` -> no findings
- The reviewer did not run the full `npm run check`. `python tasks.py verify web-notes-list` showed no evidence recorded for either criterion when checked during this review. The parent worker must record it before `done`; AC2 names `npm run check`.

Previous findings, re-checked:
- CR-1 (stale-load and 404 tests): resolved. `notes-page.spec.ts` has `shows the newest tag filter when an older response arrives last`, `ignores the failure of an outdated load` and `drops a note that was deleted elsewhere when pinning it (404)`.
- CR-2 (note created during a reload not shown): resolved. `create()` (`notes-page.ts:156-158`) starts a new `load()` when `notes()` is null, and that load supersedes the running one through `loadSeq`. A test covers it.
- CR-3 (Cancel/Escape during save): resolved.
  - `closeComposer()` returns early while `saving()`, and Cancel is `[disabled]="saving()"`.
  - `openComposer()` clears `createError`.
  - A test covers Cancel and Escape during a failing save.
- CR-4 (excerpt repeats the derived title): resolved for the realistic cases. Lines are cleaned before the title line is chosen, and the blank-title check uses `title?.trim()`. This matches `NoteMarkdown.titleOf` for empty-alt images, inline links and empty list items. Tests cover these cases.

New `note-card.spec.ts` (8 tests):
- It tests behavior through the rendered DOM and the public inputs and outputs:
  - title, plain-text excerpt, tags, `datetime` and the editor link;
  - text and tags left out when the note has none;
  - pin and archive labels and `aria-pressed` in both states, and labels updated when the input changes;
  - `pin` and `archive` emitted, and nothing emitted while busy;
  - the `compact` host class.
- Nothing is mocked that hides the behavior under test. It adds no production code and weakens no existing test.

Card extraction (`note-card.ts` / `note-card.scss`): the markup, labels, disabled state and `routerLink` match the previous inline card. The page still owns the state and the `busy` set, so state has one owner.

Acceptance criteria assessment:
- AC1: implemented.
  - Grid/list toggle: masonry with CSS columns; the choice is persisted with a safe localStorage fallback.
  - Pinned first: sorted pinned first, with Pinned and Other notes groups.
  - Tag filter: server `tag` parameter; choosing the same tag again clears it.
  - Archived toggle: `archived=true|false`.
  - Search: client-side; every word must match title, text or tags.
  - Create note: inline composer, POST, active tag prefilled.
- AC2: Vitest tests cover filtering (search, tag, archived) and pin ordering at the pure-function and component level, plus service paging and the card component. Evidence that `npm run check` passes is still to be recorded by the parent.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | Card date assertion depends on the machine's time zone**
    - File: `web/src/app/features/notes/list/note-card.spec.ts:47`
    - Category: Tests
    - Problem: the test expects `'Oct 3, 2026'` for `updatedAt: '2026-10-03T08:00:00Z'`. The template formats it with `date: 'mediumDate'` in the local time zone. In a zone more than 8 hours behind UTC on that date (for example Hawaii, UTC-10), the card shows `Oct 2, 2026` and the test fails.
    - Why it matters: the test can fail depending on where it runs, not because of a defect.
    - Suggested fix: use a mid-day time that is the same date in every zone (for example `2026-10-03T12:00:00Z`), or build the expected string with `formatDate` in the local zone.

## Review conclusion

No material issues found. CR-1 is a minor robustness improvement in a test and does not block completion; the parent may fix it or record its disposition in `quality.json`.
