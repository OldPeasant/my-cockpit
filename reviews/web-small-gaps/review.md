# Code Review

Date: 2026-11-08

Task: web-small-gaps - Web: search type filter, read-later capture, Android download link

Scope: `git diff 9c93d3e59..HEAD -- web/ docs/ARCHITECTURE.md`, final revision (committed changes only). The task's own commits are the ones prefixed "web-small-gaps:". The other commits in the range come from main (heartbeats, claims, the `tasks/web-palette-type-hint.md` follow-up) and were not reviewed as part of this task.

Requirements:
- tasks/web-small-gaps.md acceptance criteria and recorded assumptions
- SPEC.md §4.2, §4.3 (ItemType), §4.4 search, §4.5 capture, §8.4 bookmarks / read-later, §11 Android APK download
- docs/ARCHITECTURE.md (run/dev proxy, conventions), AGENTS.md project notes
- Server contract: AndroidDownloadController, SecurityConfiguration, SpaWebConfiguration; generated client (`search`, `createBookmark`, `getAndroidAppVersion`, `downloadAndroidApp`)
- reviews/web-small-gaps/tests.md (TW-1) and reviews/web-small-gaps/fixes.md

Git range:
- Branch: main
- HEAD: f3e36cde7ab434c35281da9f349228348af12bb8
- Base/start: 9c93d3e5934bb1c693330376255cd441e90621fb
- Commits reviewed: 6 task commits (bce39e09f, 5a5dc8211, b4b9d7b43, 70e14275a, 672a4b918, f3e36cde7). The first review covered the first four, before a rebase (then 024c5158f, d493ad24b, 8b1ce8a67, 371c178e9). This review adds the test-writer commit 672a4b918 and the fix commit f3e36cde7.

Summary: 0 P0, 0 P1, 0 P2, 0 P3 open (2 P3 from the first review: CR-1 fixed, CR-2 deferred to a follow-up task)

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [x] **CR-1 | The dev proxy does not forward `/download`, so the new Android app row cannot work under `npm start`** (status: fixed in f3e36cde7, verified)
    - File: `web/proxy.conf.json:12` (used by `web/src/app/features/settings/general/android-app-download.ts`)
    - Category: Integration
    - Problem: The component calls `GET /download/android/version` and links `/download/android` relative to the web origin. The dev proxy used to forward only `/api` and `/actuator`.
    - Why it matters: Under the documented dev setup the row showed a wrong state and the link reached the Angular dev server instead of the APK.
    - Suggested fix: Add a `/download` proxy entry and update docs/ARCHITECTURE.md.
    - Verification: `web/proxy.conf.json` now has a `/download` entry with the same target and options as `/api` and `/actuator`. docs/ARCHITECTURE.md line 95 now names `/download` too. No Angular route starts with `download`, so the prefix proxy hides no SPA page. Backup downloads go through `/api/...` and are not affected. Production behavior (Caddy, server SPA fallback) is unchanged.

- [ ] **CR-2 | The `type:` filter cannot be discovered in the UI** (status: deferred to task `web-palette-type-hint`, accepted)
    - File: `web/src/app/features/search/search-query.ts:40` (palette input: `web/src/app/shell/command-palette.html:13`)
    - Category: Quality
    - Problem: The `type:` prefix works as specified, but no string in the palette tells the user the syntax exists.
    - Why it matters: A filter users cannot find does little for the parity goal in docs/PARITY.md.
    - Suggested fix: A hint or chips in the palette. The palette lives in `shell/`, outside this task's `touches`.
    - Disposition check: `tasks/web-palette-type-hint.md` exists, depends on `web-small-gaps`, touches `web/src/app/shell`, and its criteria (hint or chips, English and German strings, unit tests) cover this finding. The criterion "chips or `type:` prefix" is met by the prefix, so deferring this does not leave a criterion unmet.

## Notes on what was checked in the final revision (no new findings)

- TW-1 fix (`search-query.ts:79`): `Object.hasOwn(ALIASES, key)` now guards the alias lookup, so inherited keys (`constructor`, `toString`, `__proto__`, and the plural fallback `constructors`) no longer resolve to a function and no longer leak a non-ItemType value into `types`. The fallback `SEARCH_TYPES.find(...)` was not affected. `Object.hasOwn` is available for the project's `ES2022` target (web/tsconfig.json). The new spec cases `constructor`, `constructors` and `note,constructor` check that the whole `type:` word stays as text and `types` stays empty. This matches the documented rule that a list with one unknown name is kept as text.
- New tests from the test-writer (672a4b918) were read in full:
  - `search-query.spec.ts`: dash-written labels and plurals, prefix case, an empty query, and German UI labels loaded with `loadTranslations` and cleared in `afterEach`, so no state leaks into other specs. The result order follows `SEARCH_TYPES` (`type:termin,note` gives `['NOTE', 'EVENT']`), which matches the parser.
  - `capture.service.spec.ts`: `readLater` request body, a missing or empty description left out, no client id, errors not silenced (the interceptor toast is the user's error message), and a 400 rejecting with `ApiError`.
  - `capture-dialog.spec.ts`: Ctrl+Enter from the notes goes to Read later and not to `/api/v1/capture`. A double submit (Enter, Enter, click) sends one bookmark and closes once. Shift+Enter keeps notes typed during the save. `https://[::1` is rejected by `isSingleUrl`.
  - `android-app-download.spec.ts`: a status-0 network failure is shown as a failure (not "no app"), with no link and no toast. A 404 after Try again shows the "none" state.
  - `messages-de.spec.ts`: every new unit has a non-empty German target that differs from the English text (the version string is excluded on purpose), and the interpolation placeholders are kept.
  - The tests check behavior through the DOM and HTTP, not internals. No existing test was removed, skipped or weakened. The only line removed in 672a4b918 is a changed import.
- From the first review, still true for the final code: `types` is sent only when given and in the server's order. The min-length check applies to the text left after the filter. Out-of-order safety in the palette is unchanged. Read later is offered only for exactly one http(s) URL with no files. Double submits are guarded. A failed save keeps the input. The Android row request is silent and handles 404, other errors with Try again, and loading. The download link is a same-origin anchor behind the server's login. 15 new i18n units are in both locale files.
- Validation: the parent reported `npm --prefix web run check` on HEAD f3e36cde7 -> exit 0 (lint clean, 193 files / 4520 tests, build ok). I did not re-run it in this review (read-only inspection). `sh scripts/check.sh` is for the parent to run and record.

## Review conclusion

No material issues found.
