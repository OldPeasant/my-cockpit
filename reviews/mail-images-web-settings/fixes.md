# Fix Report

Date: 2026-11-08

Task: mail-images-web-settings - Web mail images follow the synced image settings

Scope: current task. Findings CR-1/TW-1, CR-2 and CR-3 from `reviews/mail-images-web-settings/review.md` and `reviews/mail-images-web-settings/tests.md`, as passed on by the parent worker. CR-4 is about evidence wording only, so the parent handles it.

## Fixed findings

### CR-1 / TW-1 - A sender change made while the settings are not loaded can replace the whole synced sender list

Files changed:
- `web/src/app/features/mail/read/image-allowlist.ts`
- `web/src/app/features/mail/read/image-allowlist.spec.ts`

Problem:
`change()` awaited `ready()`, and `ready()` swallows a failed settings load. `change()` then built the new list from the empty fallback `[]`. `SettingsService.update` reloads the settings before it PATCHes, so if that reload worked, the stored `mailImageAllowedSenders` was replaced by the one new entry.

Fix:
After `ready()`, `change()` now throws an Error, without saving, when `settings.settings()` is still `null`. `add`, `remove` and `removeEntry` therefore reject. Their callers in `mail-read-page.ts` and `general-settings` already catch that rejection. The test-writer's regression test was left unchanged and now passes. One test was added: "rejects a change without saving when the settings cannot be loaded". It checks that `add` and `remove` reject and that nothing is PATCHed.

Validation:
- `cd web && npx ng test --watch=false --include='src/app/features/mail/read/image-allowlist.spec.ts' --include='src/app/features/mail/read/mail-read-page.spec.ts' --include='src/app/features/settings/general/general-settings.spec.ts'` - Pass (3 files, 175 tests)

### CR-2 - With policy "allow", the read view still offers "Stop" for a listed sender

Files changed:
- `web/src/app/features/mail/read/mail-read-page.ts`
- `web/src/app/features/mail/read/mail-read-page.spec.ts`

Problem:
The quiet banner ("Images from this sender load automatically." with a "Stop" button) appeared under policy `allow` when the sender was listed. Under that policy, "Stop" had no visible effect.

Fix:
`senderAllowed` is now true only when `ImageAllowlist.policy()` is `block` and the sender is listed. The template condition is unchanged, so the banner appears only under `block`. Unit test added: 'do not offer "Stop" for an allowed sender when the policy is "allow"'. It checks that images load through the proxy, that no `.images.quiet` banner appears and that there is no "Stop" button. The existing test for the banner under `block` still passes.

Validation:
- same focused `ng test` run as above - Pass

### CR-3 - `storedSenders()` doc comment does not match its behaviour

Files changed:
- `web/src/app/features/mail/read/image-allowlist.ts`
- `web/src/app/features/mail/read/image-allowlist.spec.ts`

Problem:
The comment said `null` is returned when storage is unavailable, but the code returned `[]` when `localStorage` threw.

Fix:
The code now does what the comment intended. Reading `localStorage` and parsing its value are handled in separate steps. If the read throws, the function returns `null`, so the migration does nothing, removes nothing and tries again on the next page load. If the content cannot be parsed or is not an array, it still returns `[]`, so the existing invalid-value test still passes. The comment was updated to match. The test-writer's test "is ready when the browser storage cannot be read" now also checks that `removeItem` is not called.

Validation:
- same focused `ng test` run as above - Pass

## Not fixed

### CR-4 - Evidence for criterion 4 overstates what the Playwright run exercised

Status:
Out of scope

Reason:
This finding concerns the wording of evidence recorded with `tasks.py verify`, not code. Changing task evidence is the parent worker's job, and the parent did not pass this finding to the fixer.

## Validation summary

Focused tests:
- `cd web && npx prettier --write` on the 4 changed files - no further changes needed
- `cd web && npx ng test --watch=false --include='src/app/features/mail/read/image-allowlist.spec.ts' --include='src/app/features/mail/read/mail-read-page.spec.ts' --include='src/app/features/settings/general/general-settings.spec.ts'` - Pass, 175 tests

Full test suite:
- `cd web && npm run check` (api:check, lint --max-warnings=0, e2e:typecheck, test:ci, ng build) - Pass, 187 files, 4181 tests. ng build finished. It shows the existing bundle-budget warning, which this change did not introduce.

Lint/type-check/build:
- included in `npm run check` above - Pass

`.taskcheck`:
- `sh scripts/check.sh` - Pass ("all checks passed": server, web, android)

Remaining unresolved findings:
1 (CR-4, parent's responsibility: evidence wording)

## Final status

All validated findings fixed and validation passed.
