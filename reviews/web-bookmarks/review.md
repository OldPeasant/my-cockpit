# Code Review

Date: 2026-10-22

Task: web-bookmarks - Bookmarks UI

Scope: commits after base 1c092335 up to HEAD 7dadda74 (`git diff 1c092335..HEAD -- web`): `web/src/app/features/bookmarks/` (service, display helpers, page, reader route `/bookmarks/:id`, specs) and `web/src/app/app.i18n.spec.ts`

Requirements:
- tasks/web-bookmarks.md acceptance criteria and recorded assumptions
- SPEC.md §4.1, §4.2, §8.4
- docs/ARCHITECTURE.md (web conventions, testing)
- AGENTS.md project notes
- deploy/Caddyfile (production CSP)
- Generated API client models and functions for Bookmarks, ChangeEvent and the files preview (`api/openapi.yaml` contract)
- Sibling feature `web/src/app/features/habits` for conventions

Git range:
- Branch: main
- HEAD: 7dadda74
- Base/start: 1c092335a2909e97cec38e57d680049129ca72ef
- Commits reviewed: 1 (7dadda74)

Review method note: shell access was not available to the reviewer, so the commit list and diff could not be printed. The review covers the full current contents of every file in the feature folder named in the scope (bookmarks.routes.ts, bookmarks-page.ts/.html/.scss, bookmark-reader.ts/.html/.scss, bookmarks.service.ts, bookmark-display.ts and their specs), plus the contracts and docs listed above. `testing.ts` and `app.i18n.spec.ts` were not read in full. Tests and `npm run check` were not re-run by the reviewer.

Summary: 0 P0, 0 P1, 1 P2, 4 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | Remote favicons are blocked by the production CSP and leave an empty icon instead of the letter fallback**
    - File: `web/src/app/features/bookmarks/bookmarks-page.html:117-121`, `web/src/app/features/bookmarks/bookmark-display.ts:49-51`
    - Category: Integration
    - Problem: The list renders `<img [src]="faviconOf(b)">` pointing straight at the bookmarked site's favicon URL (absolute http(s) URL per `BookmarkResponse.favicon`). The deployed CSP in `deploy/Caddyfile:24` is `img-src 'self' data: blob:`, so the browser blocks every external favicon. When a favicon is set, the template takes the `@if` branch and never shows the host-initial fallback. The result is an empty icon box for every bookmark whose metadata fetch found a favicon, which is the normal case. Unit tests in jsdom don't enforce CSP, so they can't catch this. Loading third-party images directly also sends the owner's IP and request timing to every bookmarked site each time the list renders. SPEC.md §4.1 avoids that for mail, where remote images go through a server proxy.
    - Why it matters: In the deployed stack the favicon feature never works, and it hides the fallback that would otherwise show. It only looks right in dev, where no CSP is set.
    - Suggested fix: Show the letter fallback unless the icon can load. One option is to render the letter underneath and hide a failed `<img>` with an `(error)` handler. A better option is to serve favicons same-origin through the server: a server-side proxy or a stored file, which needs a server/API task. Recording that as a task or assumption is the parent's call. Don't widen `img-src` to arbitrary hosts.

## P3 Low

- [ ] **CR-2 | Change events for bookmarks not currently listed are ignored, so items that enter the view from elsewhere don't appear**
    - File: `web/src/app/features/bookmarks/bookmarks-page.ts:240-241`, `:254-258`
    - Category: Correctness
    - Problem: `refreshOne` returns early when the event's id isn't in the shown list. Some changes should add a row to the current view, for example: another device puts a bookmark back into Read later, marks one unread, or adds the filtered tag, or the bookmark is restored from the Trash and that is reported as `UPDATED` (`ChangeEvent.op` only has CREATED/UPDATED/DELETED). None of these show up until the user navigates. `reloadQuietly` also does nothing while more than one page is loaded (`this.nextCursor()` is set), so with more than 50 bookmarks, `CREATED` events from other devices (for example an Android share to Read later) are dropped too.
    - Why it matters: The live update is only partial, and the queue goes stale without any sign.
    - Suggested fix: For an `UPDATED` id that isn't shown, fetch it and insert it in order when `belongs()` is true. When more pages are loaded, insert fetched `CREATED` items at the top instead of skipping the reload.

- [ ] **CR-3 | A quiet reload that was started before a row change can overwrite that change's result**
    - File: `web/src/app/features/bookmarks/bookmarks-page.ts:254-264`
    - Category: Correctness
    - Problem: `reloadQuietly` only throws its response away if a change is still running when the response arrives (`this.busy().size`). Suppose a reload starts (for example from the CREATED event of the user's own `add()`), then a PATCH or DELETE starts and finishes before the reload's response arrives. The older list then replaces the newer state with `bookmarks.set(page.items)`, so a just-read or just-deleted bookmark briefly reappears. The following `UPDATED`/`DELETED` SSE event usually repairs it, through the version check in `refreshOne` or `drop`. Without that event (SSE reconnecting) it stays wrong until the next load.
    - Why it matters: Out-of-order async results flicker or show stale state. This is exactly the race class the review checklist names.
    - Suggested fix: Keep a mutation counter or generation that increments when any change starts. Capture it when the reload starts and discard the reload result if it changed. Alternatively, merge by `version` instead of replacing the list.

- [ ] **CR-4 | Reader's "Back to bookmarks" loses the view and tag the user came from**
    - File: `web/src/app/features/bookmarks/bookmark-reader.html:2`
    - Category: Correctness
    - Problem: `routerLink=".."` navigates to `/bookmarks` with no query params. A user who opens "Read here" from `/bookmarks?view=all&tag=essays` lands back in the Read later queue, not in the filtered list.
    - Why it matters: A small navigation regression in the "all bookmarks by tag" flow from the acceptance criterion.
    - Suggested fix: Pass the originating `view`/`tag` as query params on the "Read here" link and back again. Using `Location.back()` when there is in-app history would also work.

- [ ] **CR-5 | Race and error paths of the page have no tests**
    - File: `web/src/app/features/bookmarks/bookmarks-page.spec.ts`
    - Category: Tests
    - Problem: The spec covers the main queue actions well: list, tag filter, add, mark read/unread, unqueue, delete, 412 conflict, metadata event, load more, load error. It has no test for: (a) a stale list response for an earlier view being ignored (`loadSeq`), (b) `resync$`/`CREATED` triggering `reloadQuietly`, (c) `add()` failing on the server (the `addError` from `ApiError`), (d) a 404 on a row change dropping the row.
    - Why it matters: These are the guards most likely to regress, and the bugs they prevent are invisible in normal use.
    - Suggested fix: Add focused cases for (a) to (d) with `HttpTestingController`, flushing responses out of order where relevant.

## Review conclusion

No material issues found. The acceptance criteria look implemented: Read-later queue, all bookmarks by tag, add by URL, mark read, and the snapshot view. Queue actions have Vitest coverage. The XSS checks passed. `renderMarkdown` escapes all text and its `"` before markup is added, links only take http(s)/mailto targets, and Angular's `[innerHTML]` sanitizer runs on top. `[href]="b.url"` is sanitized by Angular, and the server contract says the URL is absolute http(s). Outbound links use `rel="noopener noreferrer"`. `normalizeUrl` rejects `javascript:`/`mailto:`/`ftp:`. CR-1 should be fixed or recorded as a known limitation or follow-up task before or soon after completion, because favicons won't work in the deployed stack. CR-2 to CR-5 are optional improvements.
