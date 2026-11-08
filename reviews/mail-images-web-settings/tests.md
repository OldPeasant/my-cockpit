# Test Report

Date: 2026-11-08

Task: mail-images-web-settings - Web mail images follow the synced image settings

Scope: base `20422f99a5de45eb8259d3e7168c3866bf499dac` .. HEAD `b106f5a06eccf61c5f3930b9bbf90d9dc17e3e01` on `main`. The task commits are `8e96e6038`, `055a84c7b` and `b106f5a06`, as the parent supplied them. The tests were written against uncommitted test-file changes only (see the snapshot below).

Requirements:
- `tasks/mail-images-web-settings.md` (acceptance criteria and the three recorded assumptions)
- `AGENTS.md`, `docs/ARCHITECTURE.md` ("Coverage")
- Parent instructions (do not rerun the e2e spec, which needs a running stack)

## Test files changed

- `web/src/app/features/mail/read/image-allowlist.spec.ts`
- `web/src/app/features/mail/read/mail-read-page.spec.ts`
- `web/src/app/features/settings/general/general-settings.spec.ts`

No changes were needed in `web/src/app/features/mail/mail.routes.spec.ts`. Its change in the task (it answers the settings request) was reviewed and is correct.

## Tests added or updated

Count: 15 added (7 in image-allowlist, 5 in mail-read-page, 3 in general-settings). No existing test was changed.

image-allowlist.spec.ts:
- `does not reload settings that are already loaded`
- `merges the old local list only once when several pages get ready at the same time`
- `merges duplicates of the old local list (any case) into one entry`
- `removes an old local list whose entries are all allowed already, without saving`
- `adds a sender after the old local list is merged, keeping both`
- `is ready when the browser storage cannot be read`
- `does not replace the stored senders with a list built before the settings were loaded` (**FAILS**, see TW-1)

mail-read-page.spec.ts (`remote images`):
- `show the error state, without a blocked fallback, when the policy is "allow" and the proxied load fails`
- `load only once through the proxy when the policy is "allow" and the sender is allowed too`
- `stay blocked, and the message still shows, when the settings cannot be loaded`
- `do not request a message left while the settings were still loading`
- `keep the sender allowed, and the images shown, when saving "Stop" fails`

general-settings.spec.ts (`remote images in mail`):
- `shows "always load" when the server has the policy "allow"`
- `goes back to "ask" when saving "always load" fails`
- `disables Remove while the removal saves and sends it once`

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: PROXY without asking when policy is `allow` | existing `load through the proxy at once, without a banner, when the policy is "allow"`; new `load only once through the proxy when the policy is "allow" and the sender is allowed too`, `show the error state, without a blocked fallback, ...` | `npx ng test --watch=false --include='src/app/features/mail/read/mail-read-page.spec.ts'` | Pass |
| AC1: PROXY when the sender (address or domain) is in `mailImageAllowedSenders` | existing `load at once for a sender whose domain is allowed`, `senderListed` table, `blocks until the settings are loaded, then follows them` | same plus `--include='src/app/features/mail/read/image-allowlist.spec.ts'` | Pass |
| AC1: otherwise the "Load images" banner shows | existing `stay blocked for other senders`; new `stay blocked, and the message still shows, when the settings cannot be loaded`, `do not request a message left while the settings were still loading` | read-page command | Pass |
| AC2: "Always load" adds the sender with PATCH, not localStorage | existing `"Always load from this sender" adds the sender to the synced setting ...`, `adds a sender lower case, once, ...`, `runs changes one after the other ...`, `still load once when saving the sender fails`; new `keep the sender allowed, and the images shown, when saving "Stop" fails` | both commands | Pass |
| AC2: robustness of the add when the settings could not be loaded | new `does not replace the stored senders with a list built before the settings were loaded` | allowlist command | **Fail** (TW-1) |
| AC2: old localStorage entries are merged once, then the key is removed | existing merge, invalid-value and retry tests; new duplicate, already-allowed, concurrent-ready, add-after-merge and storage-throws tests | allowlist command | Pass |
| AC3: Settings control "ask / always load" and sender list with Remove | existing 4 tests in `remote images in mail`; new `shows "always load" when the server has ...`, `goes back to "ask" when saving ... fails`, `disables Remove while the removal saves ...` | `npx ng test --watch=false --include='src/app/features/settings/general/general-settings.spec.ts'` | Pass |
| AC4: manual check against bootRun + npm start | none by test-writer (`web/e2e/mail-images/image-settings.spec.ts` exists) | none | Not run: needs a running stack. The parent says it ran the e2e spec against bootRun + ng serve. |
| AC5: UI strings in en + de locale | indirectly through `npm run check` (`ng build`, which did not run because the tests failed first) | `npm run check` | Not run by test-writer: `ng build` / `build:de` never ran because test:ci failed on TW-1 first |
| AC6: unit tests cover the new behavior; `npm run check` passes | all of the above | `cd web && npm run check` | Fail: only TW-1 fails (1 of 4178). api:check, lint and e2e:typecheck passed |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include='src/app/features/mail/read/image-allowlist.spec.ts'`
`cd web && npx ng test --watch=false --include='src/app/features/mail/read/mail-read-page.spec.ts' --include='src/app/features/settings/general/general-settings.spec.ts' --include='src/app/features/mail/mail.routes.spec.ts'`
`cd web && npx ng test --watch=false --include='src/app/features/mail/read/mail-read-page.spec.ts'` (rerun after the last test was added: 112 passed)

Result:

Fail. image-allowlist: 26 passed and 1 failed (TW-1). mail-read-page, general-settings and mail.routes all passed.

## Full test execution

Command:

`cd web && npm run check`

Result:

Fail. 4177 passed and 1 failed (TW-1, the intended defect test). The earlier steps passed: `api:check`, `lint` and `e2e:typecheck`. `ng build` did not run because the tests failed first. `npm run lint` passed again after the last test was added. Prettier check on the edited specs: clean.

## .taskcheck

Result:

Not run. `sh scripts/check.sh` includes `npm run check`, and that fails on TW-1 until production is fixed. The parent runs `.taskcheck` after the fix.

## Bugs found

### Adding a sender while the settings are not loaded can overwrite the stored allowed senders

Finding ID:

`TW-1`

Test:

`ImageAllowlist > does not replace the stored senders with a list built before the settings were loaded` (`web/src/app/features/mail/read/image-allowlist.spec.ts`)

Production source:

`web/src/app/features/mail/read/image-allowlist.ts:72-80` (`change`). The cause is `ready()` at lines 32-41: when the load fails, it returns without throwing.

Requirement:

AC2: "Always load images from this sender" adds the sender to `mailImageAllowedSenders` through `PATCH /api/v1/settings`. Adding a sender must keep the senders that are already stored.

Expected behavior:

If the settings could not be loaded, `add()` must not send a full replacement list built from nothing. It can reject, or it can load the settings and build the list from them. Either way, `erika@example.test`, which is already stored, stays in the list.

Actual behavior:

`change()` awaits `ready()`. When `ready()` swallows a load error, `this.senders()` is `[]`, and `change()` calls `settings.update({ mailImageAllowedSenders: ['max@example.test'] })`. The real `SettingsService.update` loads the settings itself when none are loaded (`save()`: `this.current() ?? await this.load()`) and then sends this PATCH, which replaces the whole server list with just the new sender. The test reproduces this: the first load fails, and the load inside the save succeeds. The stored list ends up as `['max@example.test']` and `erika@example.test` is lost. Triggering it in the read view takes a load failure in `ready()` followed by a load success moments later, so the window is narrow, but the result is silent data loss across devices. `remove`/`removeEntry` are not affected, because filtering an empty list never saves.

Severity:

Low (narrow window, but silent loss of synced user data)

Fix required:

`Yes. The parent worker or fixer must fix the production code.`

## Coverage

Status: verified. The run had one test deselected by `--filter`: TW-1, which fails on purpose. Without the filter, vitest writes no coverage report when a test fails. `npm run coverage` gave a nonzero exit with no `web/coverage/web/` output.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured by intersection. Whole-file line coverage is 163/163, 103/103 and 51/51 for the three TS files, so every executable changed line is covered. Changed branches are listed below.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `mail-images-web-settings`; base `20422f99a5de45eb8259d3e7168c3866bf499dac`; HEAD `b106f5a06eccf61c5f3930b9bbf90d9dc17e3e01`; commits `8e96e6038`, `055a84c7b`, `b106f5a06` from the parent, checked with `git diff --stat 20422f99a..HEAD` against the task `touches`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD `b106f5a06` plus uncommitted spec changes with these sha256 hashes: image-allowlist.spec.ts `41f4293d88299f608f921bdfd7a449317631127b30b4834f1bdb9f552932e23a`, mail-read-page.spec.ts `5885f4c695ccc0406b66c500455e56adb8378473b8999a1d2b508ba4c394faab`, general-settings.spec.ts `4008708244340bea31ff8e5b43542c2728e1589384c26f5bb3e4a217f84e2c9e`. No production files are modified in the working tree. The untracked `reviews/mail-images-web-settings/review.md` comes from another helper.
- Exact included files; renamed/deleted/excluded files and reasons: included `web/src/app/features/mail/read/image-allowlist.ts`, `web/src/app/features/mail/read/mail-read-page.ts` and `web/src/app/features/settings/general/general-settings.ts`. Excluded: `general-settings.html` and `.scss` (templates and styles; V8 does not instrument them separately, and the template is exercised through component tests), `web/src/locale/messages*.xlf` (translation data), and `web/e2e/**` and `web/playwright.config.ts` (test infrastructure). No renames or deletions.
- Provider/version, command, working directory, and test selection: Vitest + `@vitest/coverage-v8` (the project's provider) through the Angular unit-test builder. Run from `web/`: `npx ng test --watch=false --coverage --coverage-reporters=text-summary --coverage-reporters=json-summary --coverage-reporters=json --coverage-reporters=lcov --filter='^(?!.*does not replace the stored senders with a list built before).*'`. These are the same reporters as `npm run coverage`. The full suite ran, with only the TW-1 test deselected.
- Run start/completion time and exit status: about 12:42 to 12:43:05 +01:00 on 2026-11-08. All 187 files passed (4178 tests, 1 skipped by the filter).
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-final.json` (sha256 `0f2f5740675b539e8a6f2438fc05e0fece5fbc65c743976431f5e84b6d6591c0`, mtime 12:43:05), `web/coverage/web/coverage-summary.json` (sha256 `8dc496e69761b567c8c4c5d78e84a472c5e4ff573a66a903bfa94975b4100a87`), `web/coverage/web/lcov.info`.
- Evidence outputs are fresh and inputs stayed unchanged: yes. `web/coverage/web/` did not exist before the first filtered run, because the failed `npm run coverage` wrote nothing. The final run rewrote it at 12:43:05. The spec hashes above were taken after the run and match the final files.
- Final run after latest test additions and production fixes: yes for the test additions. Production is unchanged, so after the TW-1 fix the numbers must be measured again.

Values below use covered/total (percentage).

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/mail/read/image-allowlist.ts` | 71/71 (100%) | 45/45 (100%) | 25/25 (100%) | 51/51 (100%) |
| `web/src/app/features/mail/read/mail-read-page.ts` | 198/204 (97.06%) | 119/134 (88.81%) | 42/43 (97.67%) | 163/163 (100%) |
| `web/src/app/features/settings/general/general-settings.ts` | 126/127 (99.21%) | 57/63 (90.48%) | 30/30 (100%) | 103/103 (100%) |
| **Aggregate (summed counters, 3 measured files)** | 395/402 (98.26%) | 221/242 (91.32%) | 97/98 (98.98%) | 317/317 (100%) |

The aggregate excludes `general-settings.html`/`.scss` and the locale files (not instrumented by V8; see above). No in-scope file lacks report data.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `mail-read-page.ts:160` (`if (address)` false in `alwaysShowImages`) | Task-changed line. Runs only without a sender address, but the template hides "Always load from this sender" in that case | Unreachable through the UI; no test added |
| `mail-read-page.ts:167` (`if (address)` false in `forgetSender`) | Task-changed line. "Stop" shows only when `senderAllowed()`, which needs an address | Unreachable through the UI. The failure path of the same line (`.catch`) is now covered by `keep the sender allowed, and the images shown, when saving "Stop" fails` |
| `general-settings.ts:233` (`removing().has(entry)` early return) | Task-changed line. Defensive guard against double removal | The button is disabled while saving. `disables Remove while the removal saves and sends it once` shows that only one PATCH goes out; the guard itself cannot be reached through the DOM |
| `mail-read-page.ts:71, 84, 109, 114, 118, 128, 139, 172, 245, 277, 279, 307, 311, 375` | Code that existed before this task (keyboard host binding, `??` fallbacks, attachments, preview kinds) | Outside the task scope |
| `general-settings.ts:82, 168, 169, 211, 221` | Code that existed before this task (`??` fallbacks, time zone blur) | Outside the task scope |

## Unable to test

- AC4 (manual check against bootRun + npm start, remote `https` and `cid:` images). This needs a running stack. The parent says it already ran `web/e2e/mail-images/image-settings.spec.ts` against bootRun + ng serve, and told the test-writer not to rerun it. The parent must record its own evidence.
- AC5 (`ng build` / `npm run build:de` with the German translations). `npm run check` stopped at the tests (TW-1) before `ng build`. Follow-up: rerun `npm run check` after the TW-1 fix.

## Summary

Tests added; production defects were discovered and must be passed to the `fixer` before task completion.
