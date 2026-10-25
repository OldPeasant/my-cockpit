# Code Review

Date: 2026-10-26

Task: web-new-view - Combined New view (GTD inbox + mail inbox)

Scope: final revision of the task. `git diff 464e31b7..c5811bbf` covers commits 1bb76906 (implementation), 3c747664 (e2e spec and palette spec), 3f14a29a (round-1 reports), b975de5e (fixes), 2ca73f94 (extra tests) and merge c5811bbf. Changed code: web/src/app/features/new/*, web/src/app/app.routes.ts, web/src/app/shell/nav-sections.ts, web/src/app/features/search/search-palette.provider.spec.ts, web/e2e/new/new.spec.ts. Only committed code was reviewed. The working tree has uncommitted test-writer changes (new-page.spec.ts, new.routes.spec.ts) that are not part of this review.

Requirements:
- tasks/web-new-view.md acceptance criteria 1-4 and the three recorded assumptions: nav placement in the Home group, hold-back of older tasks while more mail can load, and keys 1/2/3, `?source=`, j/k/o/Enter/t with a flat unified inbox
- SPEC.md §5.3 "New view", §9.1
- docs/ARCHITECTURE.md (web conventions), AGENTS.md "Project notes"
- Earlier review rounds and reviews/web-new-view/fixes.md
- Existing conventions in MailListStore, MailListPage (infinite scroll, focusCursor), ListStore/TaskStore and MailReadService.gtd

Git range:
- Branch: main
- HEAD: c5811bbfe7f2bfaa4e0e70dc1fe8359843b73917
- Base/start: 464e31b760b54e108001f2f5da4c3d6defac7483
- Commits reviewed: 6 (1bb76906, 3c747664, 3f14a29a, b975de5e, 2ca73f94, c5811bbf)

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## What was checked

- **Criterion 1:** `/new` is lazy-loaded in app.routes.ts, and the route title is "New". The nav entry sits in the Home group right after Dashboard, which makes it the entry before Inbox (the first Tasks section), as the assumption records. `mergeNew` (new-feed.ts) sorts tasks by `createdAt` and mails by `receivedAt`, falling back to `sentAt`. Ties are broken by key. While `nextCursor` is set under "All", tasks older than the oldest loaded mail are held back, so loading the next page only appends rows. Each row has an icon and a label. Infinite scroll uses the same IntersectionObserver sentinel as MailListPage, plus a "Load older messages" button and loadMore on `j` at the last row.
- **Criterion 2:** The filter comes from `?source=` through `toSignal(queryParamMap)` with `replaceUrl` and `merge`, and `all` removes the param. Keys 1/2/3 are ignored in inputs, with modifiers, on repeat and while a dialog is open. Open goes to `/tasks/:id` or `/mail/messages/:id`, and both routes exist. "→ Inbox task" calls the existing `MailReadService.gtd(id, 'task')` (mail-gtd API). It is guarded against double sends by `converting`, refreshes the task store and leaves the mail list unchanged. Live updates come from the stores: TaskStore refetches on TASK events and on resync. MailListStore refetches the unified inbox on any MAIL_FOLDER/MAIL_ACCOUNT event and on resync.
- **Criterion 3:** No server or OpenAPI changes are in the diff. Only existing generated API calls are used, through the existing stores and services. Vitest covers merge order across paging, each filter, SSE add/remove and resync, "→ Inbox task" (success, ApiError, unexpected error, double send), load errors with retry, keys and infinite scroll.
- **Async/state:** Out-of-order answers are handled by the stores' sequence counters (ListStore `requestSeq`, MailListStore `loadSeq`/`listGeneration`/`mutationSeq`). Loading ends on failure: a source counts as loading only while it has neither data nor an error. Errors show a banner with "Try again". The empty state is hidden next to an error. A failed `tasks.refresh()` after a successful conversion shows the recoverable task-error banner.
- **Security:** Nothing is rendered with innerHTML. Interpolation is escaped by Angular. Cursor keys are passed through `CSS.escape` in selectors. No logging. The e2e test data is fictitious and the e2e password is a labelled dummy.
- **Out-of-touches edit:** search-palette.provider.spec.ts now types `new t` instead of `new`, because the new "Go to New" palette entry would otherwise also match. This is a needed consequence of the nav entry and does not weaken the test.
- **app.spec.ts:** It is unchanged against the base. Main's version already checks that no nav section shows the coming-soon placeholder, and this now includes `/new`.

## Evidence notes for the parent worker

- Running `ng test --include='src/app/features/new/**'` in the current working tree gives 1 failure out of 68. That run includes the test-writer's uncommitted spec changes (new-page.spec.ts adds two live-update tests, and new.routes.spec.ts is new). The committed suite was reported green in fixes.md (60/60 focused, `npm run check` 3524 passed). Before committing them, confirm whether the failing test points to a production defect or to a wrong expectation.
- No criterion is recorded in `tasks.py verify` yet. Criterion 4 (Playwright `e2e/new/new.spec.ts` against the e2e stack) has not been run in any recorded evidence. It must be run with `npm run e2e -- e2e/new/new.spec.ts` against the stack, or marked `--skip` with a reason. A static review or the Vitest suite is not evidence for it.

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
