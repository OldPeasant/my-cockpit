# Code Review

Date: 2026-11-08

Task: web-small-gaps - Web: search type filter, read-later capture, Android download link

Scope: `git diff 9c93d3e59..HEAD -- web/` (committed changes only; the test-writer's uncommitted spec edits in the working tree were not reviewed)

Requirements:
- tasks/web-small-gaps.md acceptance criteria and recorded assumptions
- SPEC.md §4.2, §4.3 (ItemType), §4.4 search, §4.5 capture, §8.4 bookmarks / read-later, §11 Android APK download
- docs/ARCHITECTURE.md (run/dev proxy, conventions), AGENTS.md project notes
- Server contract: AndroidDownloadController, SecurityConfiguration, SpaWebConfiguration; generated client (`search`, `createBookmark`, `getAndroidAppVersion`, `downloadAndroidApp`)

Git range:
- Branch: main
- HEAD: 371c178e9c5bee7e438f4706ec2c00f5917ceb6f
- Base/start: 9c93d3e5934bb1c693330376255cd441e90621fb
- Commits reviewed: 4 (024c5158f, d493ad24b, 8b1ce8a67, 371c178e9; the same changes the parent called 4112cac74, 1691c01ca, 99d783940, cdfd6112f before a rebase)

Summary: 0 P0, 0 P1, 0 P2, 2 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | The dev proxy does not forward `/download`, so the new Android app row cannot work under `npm start`**
    - File: `web/proxy.conf.json:1` (used by `web/src/app/features/settings/general/android-app-download.ts:113,129`)
    - Category: Integration
    - Problem: The new component calls `GET /download/android/version` and links `/download/android`, both relative to the web origin. `web/proxy.conf.json` forwards only `/api` and `/actuator` to `localhost:8080`, and docs/ARCHITECTURE.md line 95 says the same. With the documented dev setup (`npm start` on :4200), the version request never reaches the server. The row then shows "No Android app is available" or the failure message, and the download link would hit the Angular dev server instead of the APK. Production is fine: Caddy proxies everything to the server, and the server keeps `/download/**` out of the SPA fallback and behind login.
    - Why it matters: Anyone checking this feature by hand in the documented dev setup sees a wrong state, which looks like a bug in the feature or the server.
    - Suggested fix: Add a `/download` entry (same target and options) to `web/proxy.conf.json` and update the dev-proxy sentence in docs/ARCHITECTURE.md. That file is outside this task's `touches`, so the change has to be small, or it can go in a follow-up task.

- [ ] **CR-2 | The `type:` filter cannot be discovered in the UI**
    - File: `web/src/app/features/search/search-query.ts:36` (palette input: `web/src/app/shell/command-palette.html:13`)
    - Category: Quality
    - Problem: The criterion allows "chips or `type:` prefix", and the prefix works as specified (ids, localized labels, aliases, plurals; an unknown word stays search text). But no string in the palette or anywhere else tells the user the syntax exists. The placeholder still reads "Search or type a command". The parity gap it closes (Android has visible type chips) stays a gap for users who don't already know the syntax.
    - Why it matters: A filter that users cannot find is close to no filter for the parity goal in docs/PARITY.md.
    - Suggested fix: Add a short hint, e.g. extend the palette placeholder or an empty-state line with "type:note". This touches `shell/`, so a follow-up or a recorded assumption is acceptable.

## Notes on what was checked (no findings)

- Search: `parseSearchQuery` sends `types` only when given, and in the server's order. `SEARCH_TYPES` matches the generated `types` union and SPEC §4.3. The min-length check applies to the text left after the filter. Out-of-order safety (switchMap / dropped subscription) is unchanged. The palette does not filter provider rows again by the raw query, so the stripped `type:` word causes no hidden rows.
- Capture: Read later is offered only for exactly one trimmed http(s) URL with no files. `javascript:`, `mailto:`, `ftp:` and multi-word input are rejected and tested. The destination is captured before the await. Double submits are guarded by `saving`. A failed save keeps the text and the choice. Shift+Enter clears only what was saved. The request body matches `CreateBookmarkRequest` (`url`, `readLater`, optional `description`). The duplicate-on-retry risk is documented in the code and recorded as an assumption. The preview region stays `aria-live`. The radios are native inputs in a labelled `radiogroup` with a visible focus ring.
- Android row: the request is silent (SILENT_ERRORS). 404 shows "none" without a link. Other errors show an in-place `role="alert"` with Try again. Loading uses `role="status"`. The link is a same-origin `download` anchor built from `downloadAndroidApp.PATH` (rootUrl is `''` in `provideHttp`), and the server requires a login for `/download/**`. Nothing sensitive is logged or exposed.
- i18n: 15 new units are in both `messages.xlf` and `messages.de.xlf`. The German targets keep the `INTERPOLATION` / `INTERPOLATION_1` placeholders. "Eingang" matches the existing Inbox wording. `settings.retry` is reused. The `capture.save` id is unchanged, so its existing translation still applies.
- Tests: the committed specs cover parser cases (aliases, plurals, comma lists, unknown words kept), the provider sending `types`, the capture destination flows (default Inbox, bookmark body, notes as description, switching back, Shift+Enter persistence, files disable the choice, retry after failure), and the Android row's states (loading, ready, unknown version, 404, error with retry) plus its embedding in General settings. Parent-reported `npm --prefix web run check` passed. I did not re-run it in this review.

## Review conclusion

No material issues found.
