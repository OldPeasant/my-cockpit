# Code Review

Date: 2026-10-20

Task: web-search - Command palette search and commands

Scope: TFP task web-search, full task scope at revision 9868beb0e1eeff200212e92750146a3e3d073ce3 (`git diff 565dce5f..9868beb0`). The task commits are bcaa6770, d3a9100c and 9868beb0, plus the assumption commit 8d5b1f30. The other commits in the range are heartbeat and task-file updates from other agents. Only committed code was reviewed. This is a re-review: the earlier review was at e176a5b3, before a rebase. Since then only spec files changed (9868beb0), plus a new assumption in `tasks/web-search.md`.

Requirements:
- tasks/web-search.md acceptance criteria and recorded assumptions (min query length 2, 200 ms debounce, limit 20, results without deepLink left out, only "New task" command added, lazy search provider, no loading/failed palette state (earlier CR-1))
- SPEC.md §4.4 (global search, results grouped by type, Ctrl+K palette with search + commands), §4.5 (Q capture hotkey)
- docs/ARCHITECTURE.md (web conventions, lazy features), AGENTS.md project notes
- web/src/app/shell/shell-tokens.ts (`PaletteProvider` / `PALETTE_PROVIDERS` contract), web/src/app/shell/command-palette.ts/.html

Git range:
- Branch: main
- HEAD: 9868beb0e1eeff200212e92750146a3e3d073ce3
- Base/start: 565dce5f0f0a8717cc833ca762cae4ee2b681cb0
- Commits reviewed: 3 task code commits (10 commits in range in total; the rest only touch task metadata)

Files reviewed: `web/src/app/features/search/search.providers.ts`, `search-palette.provider.ts`, `commands-palette.provider.ts`, `search.providers.spec.ts` (new), `commands-palette.provider.spec.ts` (new), `search-palette.provider.spec.ts` (extended), `web/e2e/search/search.spec.ts`, `web/src/app/app.spec.ts` (updated expectations for the new palette providers), plus how `HttpClientTestingBackend` matches requests in `node_modules/@angular/common/fesm2022/http-testing.mjs`.

Validation run by the reviewer: `cd web && npx ng test --watch=false --include='src/app/features/search/**/*.spec.ts' --include='src/app/app.spec.ts'`: 4 files, 84 tests passed. I did not run `npm run e2e`, as instructed.

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | The unit test's "no request before the debounce" check can never fail**
    - File: `web/src/app/features/search/search-palette.provider.spec.ts:113`
    - Category: Tests
    - Problem: `http.expectNone('/api/v1/search')` passes a string URL. `HttpClientTestingBackend._match` compares a string with `request.urlWithParams` (`http-testing.mjs:167`). The search request is always sent with `q` and `limit` params, so its `urlWithParams` is `/api/v1/search?q=passport&limit=20`, and the string never matches it. If the debounce were removed, the request would go out at t=0, this `expectNone` would still pass, and the `expectOne(isSearch('passport'))` on line 115 would find the early request. The test named "waits for the debounce" would stay green. The same string form appears in `search-palette.provider.spec.ts:147,171` and `search.providers.spec.ts:118,145,151`. There the `http.verify()` in `afterEach` would still catch a stray request, because cancelled requests stay in `open`, so those tests do work. On line 113, though, the request is consumed by `expectOne` before `verify()` runs.
    - Why it matters: The debounce is part of acceptance criterion 1. It is still covered at integration level: "never sends the requests of queries replaced within the debounce" uses the `isSearch` predicate, so it is not a coverage gap. But the focused unit test claims something it does not check, and the misleading string form invites copies.
    - Suggested fix: Use the predicate (`http.expectNone(isSearch('passport'))`) or `http.expectNone((r) => r.url === '/api/v1/search')` on line 113, and preferably in the other places too.

## Notes (not findings)

- Earlier CR-2 (retry after the lazy chunk load fails) is now covered. `search.providers.spec.ts` "fails the query when loading fails and loads again with the next query" makes the first `Injector.create` inside `load()` throw. That runs the same `catch` that clears `this.provider`. The test then checks that the query errors without a request, that the next query loads again, and that two instantiations were attempted. It stubs `Injector.create` rather than the dynamic `import()`, but both are inside the same `try`, so the branch under test is the same. The other new tests cover: a single load for concurrent and later queries, keeping the provider after an HTTP failure, no request when a query is dropped during loading, too-short queries, and the `provideSearch()` provider order.
- Earlier CR-1 (no loading/failed state; "Nothing matches" while a search runs or after it fails) is accepted by the parent as a recorded assumption (shell contract change, outside `touches`). I do not raise it again. Humans can revisit it through `tasks.py assumptions`.
- `commands-palette.provider.spec.ts` covers the "New task" row (group, label, Q hint), running it opens `CAPTURE_DIALOG`, case-insensitive matching after trimming, non-matching queries, and the case where no capture dialog is provided. These are meaningful behavior assertions.
- Acceptance criteria 1-3: in the code and the specs I find grouping in server order, cancellation of stale debounces and requests (`stale.cancelled`), arrow-key navigation with wrap-around, and Enter calling `navigateByUrl(deepLink)`. `web/src/app/app.spec.ts` lies outside `touches`, but its change only updates expectations for the providers the task adds. That is acceptable.
- Evidence: `python tasks.py verify web-search` shows all four criteria unverified at this revision. The parent worker still has to record evidence, including an actual `npm run e2e -- e2e/search/search.spec.ts` run against the e2e stack for criterion 4 (or a `--skip` with a reason), and a full `npm run check` for criterion 3.

## Review conclusion

No material issues found. CR-1 (P3) is a small, non-blocking test-precision fix. The parent worker decides how to handle it.
