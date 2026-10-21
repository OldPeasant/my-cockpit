# Code Review

Date: 2026-10-21

Task: web-files-browser - Files: browser and operations

Scope: re-review of the final code at HEAD b39b306c. Task commits in `2c1825c8..b39b306c` (900578df, dd2934c0, d346dbee, 95e9cadd, 60443304, 9fd5366b, b03516a0, a2240248, b39b306c): web/src/app/features/files/browser/*, web/src/app/features/files/files.routes.ts (+ spec), web/src/app/app.spec.ts, web/e2e/files-browser/browser.spec.ts. Production code was fully reviewed at a2240248. The delta `a2240248..b39b306c` changes only test code (web/src/app/features/files/browser/files-page.spec.ts, +66 lines, 3 new tests) and reviews/web-files-browser/tests.md. That delta was reviewed in full here. Review artifacts under reviews/ were read for context only.

Requirements:
- tasks/web-files-browser.md (acceptance criteria and recorded assumptions)
- SPEC.md §8.2 (Files), §4.2 (ETag/If-Match, Trash)
- docs/ARCHITECTURE.md, AGENTS.md project notes
- Earlier review rounds (CR-1 to CR-5, TW-1) and reviews/web-files-browser/fixes.md

Git range:
- Branch: main
- HEAD: b39b306c1a66a508958fa1a35e314f9261c76318
- Base/start: 2c1825c89ea94926fe6b46ba21166e5030844bd2
- Commits reviewed: 9 (production code unchanged since a2240248; the only uncommitted change is this report)

Summary: 0 P0, 0 P1, 0 P2, 1 P3

Delta a2240248..b39b306c (test code only):
- "does not change anything when the rename dialog is cancelled": opens Rename on Report.pdf, clicks Cancel, waits for the dialog to close (the `closed` helper answers and settles all pending requests), then checks there was no PATCH, no info notification, no new request (no listing, tree or usage reload) and an unchanged listing. This pins the NameDialog cancel path (`mat-dialog-close` closes with `''`, `openName` returns `false`, files-page.ts:247-251), the counterpart of the TW-1 move-dialog fix.
- "ignores a 'Show more' failure that answers after navigating to another folder": the root page 2 fails with a 503 after the newer DOCS load has answered. The test asserts the stale request was really sent (`cursor=2`), and that neither `moreError` ("Try later") nor a "Show more" button for the old folder appears. It discriminates: without the `seq === this.loadSeq` guard in `loadMore`'s catch (files-page.ts:147), the detail text would render and the test would fail.
- "ignores a picker load failure that answers after a newer picker load": in the move dialog, the Trip 2026 load fails after the newer root load answered. The test asserts the Trip request was sent, no `role="alert"` is shown, and the picker stays on "My drive" with the root folders. It discriminates on the guard at move-dialog.ts:183.
- The three tests use the existing FakeDrive and helpers and test behavior through the DOM, not implementation details. No existing test was removed or weakened. Test data is fictitious.
- Run by this review: `npx ng test --watch=false --include src/app/features/files/browser/files-page.spec.ts` -> 1 file, 35 tests passed.

Earlier findings, checked against the current code (unchanged since a2240248):
- CR-1 (stale tree and usage, first round): resolved. `FolderTree.load` keeps a per-folder sequence number; `FilesPage.loadUsage` has a `usageSeq` guard (files-page.ts:260-268). Out-of-order tests exist for both.
- CR-2 (closing a dialog while it saves): resolved. Both dialogs set `ref.disableClose = true` while their request runs and disable Cancel; closing is re-enabled in `finally`.
- CR-3 (missing tests): resolved, and further strengthened by the three tests above (late failures, rename cancel).
- CR-4 (no runtime evidence for downloads): resolved. The e2e spec downloads a file and a folder zip (browser.spec.ts:74-103). The parent reported `npm run e2e -- e2e/files-browser/browser.spec.ts` -> 6 passed. This review did not run it again.
- CR-5 (no heading while loading or after an error): partly resolved; the remaining case is CR-1 below.
- TW-1 (cancelling the move dialog showed "Moved" and reloaded): resolved (move-dialog.ts:70); the rename-dialog counterpart is now covered by a test.

Acceptance criteria (from the code):
- #1: every listed feature is present: tree, list and grid, breadcrumbs, new folder, rename, move dialog, delete to the Trash, download, folder as zip, and disk usage. Endpoints, `If-Match` and request bodies match the server contract. Nothing changed since the earlier review.
- #2: Vitest coverage of operations and error states is broad, including late successes and late failures of every guarded async path (listing, "Show more", tree, usage, picker). The focused spec passes (see above); the full `npm run check` was not run by this review.
- #3: the e2e spec covers create folder, rename, move and delete, with a server-side check of the Trash.
- The parent worker must record criterion evidence with `tasks.py verify` before running `done`. This is a process step, not a code finding.

Security: no secrets or personal data. Names are rendered only through interpolation. Download URLs encode the id with `encodeURIComponent` under the same-origin `rootUrl`. Test data is fictitious. No issues found.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | After navigating to another folder, the heading and breadcrumbs still show the previous folder while it loads, and stay that way if the load fails** (open; carried over from the a2240248 review, production code unchanged)
    - File: `web/src/app/features/files/browser/files-page.ts:109-128`; `web/src/app/features/files/browser/files-page.html:38-56`
    - Category: Quality
    - Problem: `load()` resets `entries`, `nextCursor` and the errors, but not `crumbs`. The page reuses one component for every folder, through the route matcher. So when a user goes from folder A to folder B, the `h1` (with `aria-current="page"`) and the breadcrumbs still name A while B loads. If B cannot be loaded (for example, it was deleted in another tab, or a tree link is stale), "The folder could not be loaded" appears under A's heading. Meanwhile `folderId` is already B: "Download as zip", "New folder" and the tree's current marker all refer to B. The "Files" fallback that the CR-5 fix added only shows on the first load.
    - Why it matters: the heading names the wrong folder in exactly the state where the user needs to know where they are. "New folder" in that state creates the folder in B, not in the A that the heading names.
    - Suggested fix: when `folderId` changes, clear `crumbs` at the start of `load()`, as `entries` already is, so the "Files" heading shows until the new crumbs arrive or the load fails. Keep the crumbs on a reload of the same folder to avoid flicker. Add a vitest that navigates to a folder whose load fails and checks the heading.

## Review conclusion

No material issues found.

The test-only delta a2240248..b39b306c is sound: the three new tests are meaningful, discriminate on the guards they target, and pass. Earlier findings remain resolved, apart from the narrower heading case left open as P3 CR-1. The parent worker should still record criterion evidence (`tasks.py verify`) before running `done`.
