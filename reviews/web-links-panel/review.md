# Code Review

Date: 2026-10-20

Task: web-links-panel - Linked items panel (shared)

Scope: Final revision of the task, round 3: `git diff c7a3abd6..4e5d4d94 -- web/` (all in `web/src/app/shared/links/`). Commits afe5faed (implementation), bf41efa0 (test-writer tests, round-1 reports), 31f1140b (fixes for CR-1/TW-1 and CR-2), 4e5d4d94 (fix for CR-4, round-2 tests). Committed code only. An uncommitted spec change in `linked-items.spec.ts` (from a test-writer running at the same time) was not reviewed.

Requirements:
- tasks/web-links-panel.md acceptance criteria and recorded assumptions
- SPEC.md §4.3 (Universal items & links), §4.4 (Global search)
- api/openapi.yaml: `GET/POST /api/v1/links`, `DELETE /api/v1/links/{id}`, `GET /api/v1/search`
- docs/ARCHITECTURE.md, AGENTS.md project notes
- The round-2 review (this file, previous revision). Its hashes refer to commits from before the rebase: 3a4ef451 -> c7a3abd6, 91ae04e7 -> afe5faed, dbab6d3a -> bf41efa0, 9bff3e37 -> 31f1140b.

Git range:
- Branch: main
- HEAD: 4e5d4d947f60fb0002db7929e6f5fdf3aaf9d931
- Base/start: c7a3abd6485be1183f42e295798f0bb3539dfc1a
- Commits reviewed: 4

Summary: 0 P0, 0 P1, 0 P2, 2 P3

Verification done by the reviewer: I ran `npm --prefix web run check` (api check, lint, e2e typecheck, `test:ci`, `ng build`) and it passed. `npm --prefix web run test:ci` reported 27 test files and 429 tests passed. The working tree included the uncommitted spec addition from the concurrent test-writer, so this count includes those tests. The build shows the known initial-bundle budget warning (501.24 kB against 500 kB). This task does not cause it, because no route imports the panel yet.

Status of earlier findings:
- CR-1 and CR-2 (round 1): still fixed in the final code. `load()` uses `loadSeq` (linked-items.ts:283-293). The item effect increments `itemSeq`, clears `actionError` and resets the search (lines 150-159). `remove()` and `addLink()` ignore results that arrive after an item change.
- CR-4 (round 2, a link added while a load is pending vanishes): fixed and verified. When `addLink` succeeds while `links()` is null, it now calls `reload()` (linked-items.ts:260). That increments `loadSeq`, so the older pending load can no longer apply its stale list. The new list is fetched after the POST has committed, so it contains the link. This also covers the case where the earlier load failed: `links` is null and `loadError` is set, and the reload clears the error. The test "keeps a link added while the list is reloading (CR-4)" (linked-items.spec.ts, committed in 4e5d4d94) flushes the pending list without the link, then expects a second list request and the link in the panel. 4e5d4d94 also adds item-change tests for a removal or a 409 on the previous item, and for an A -> B -> A change while a link is being created.

Acceptance criteria assessment:
1. Listing: each row shows the type icon (`app-item-type-icon`), the title, the type label plus subtitle, and a `routerLink` deep link. Unavailable items are greyed out (`.unavailable`), have no link and show "Unavailable item" as a fallback title. MANUAL links can be removed. SYNC links show "In text", as the recorded assumption says. Met.
2. Add link: the search is debounced and uses `switchMap`, so an older search cannot overwrite a newer one. It searches all types (no `types` filter) through `GET /search`. It hides the item itself and items it is already linked to. The link is created with `POST /links`, by mouse or keyboard. The create-linked slot works by projecting `[appLinkedCreate]` content, and features call the public `reload()`. Met.
3. Vitest component and service tests cover listing, adding via search and removal, plus the error, race and focus paths. `npm run check` passes. Met.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-5 | A link removed while a `reload()` is pending can reappear**
    - File: `web/src/app/shared/links/linked-items.ts:187`
    - Category: Correctness
    - Problem: This is the removal version of CR-4. Suppose a feature calls the public `reload()` (for example after a "create linked…" action) while a DELETE is still running. `load()` sets `links` to null. The DELETE then succeeds, and `dropLink` filters a null list, so it does nothing. The pending GET can reach the server before the DELETE commits, because both requests run in parallel. If it does, it returns the removed link, and `load()` applies that list because it is still the latest load. The link shows again until the next reload. Clicking remove again gets a 404, which `remove()` handles by dropping the row.
    - Why it matters: For a short time the panel shows a link that no longer exists. It needs a feature-triggered reload at the same moment as a removal, so it is rare, and a reload or a second remove fixes it.
    - Suggested fix: Do what `addLink` does. After a successful (or 404) removal, if `links()` is null, call `void this.reload()` instead of `dropLink`. Add a test that removes a link while a `reload()` request is still open.

- [ ] **CR-6 | Keyboard focus is lost when creating a link fails**
    - File: `web/src/app/shared/links/linked-items.html:40`, `web/src/app/shared/links/linked-items.ts:263-271`
    - Category: Quality
    - Problem: `[disabled]="linking()"` disables the focused search input while the POST runs, and browsers take focus away from a disabled element. On success, `closeSearch()` puts the focus back on "Add link". On a non-409 error, the search stays open and the `role="alert"` error is shown, but nothing returns the focus to the input. A keyboard user who pressed Enter has to tab back in to retry. The 409 path is fine because it closes the search.
    - Why it matters: This is a minor accessibility gap in a keyboard-first flow (combobox plus Enter).
    - Suggested fix: In the error branch of `addLink`, call `afterNextRender(() => this.searchInput()?.nativeElement.focus(), { injector: this.injector })`. Alternatively, use `aria-disabled` or `readonly` while linking instead of `disabled`. Cover it with a focus assertion in the existing "another add error" test.

## Review conclusion

No material issues found. CR-4 is fixed correctly and covered by a test. CR-1 and CR-2 remain fixed. All three acceptance criteria are met and `npm run check` passes. The two remaining findings (CR-5, CR-6) are minor (P3). They can be fixed or recorded with a reason.
