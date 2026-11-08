# Code Review

Date: 2026-11-08

Task: mail-images-web-settings - Web mail images follow the synced image settings

Scope: final pass over the whole task diff `git diff 55b1f084e..c3e2f199a`, with the focus on c3e2f199a. The task commits are 04d1cc7b2, 22154836b, fecd57e88, e36d59980, fb91dc29e and c3e2f199a. 07bd17d10 only adds `tasks/web-de-invite-translations.md`, which belongs to another task, so it was skipped. The PNG screenshots were also skipped. The earlier passes reviewed the same production code before a rebase, and it has not changed since then. c3e2f199a changes only tests (`image-allowlist.spec.ts`, `mail-read-page.spec.ts`) and `reviews/mail-images-web-settings/tests.md`. No production file changed after fb91dc29e. The only uncommitted change is this report.

Requirements:
- tasks/mail-images-web-settings.md (acceptance criteria 1-6, notes, the 3 recorded assumptions, recorded evidence)
- SPEC.md §4.1, §4.7 (mailImagePolicy / mailImageAllowedSenders)
- AGENTS.md project notes, docs/ARCHITECTURE.md, docs/QUALITY.md
- Earlier review passes and reviews/mail-images-web-settings/fixes.md, tests.md

Git range:
- Branch: main
- HEAD: c3e2f199ad3d911c2559037b43edc273027683be
- Base/start: 55b1f084e1e81823df801048b6019d5ef465dad6
- Commits reviewed: 6 task commits. The last one, c3e2f199a, changes only tests and the test report.

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## Status of the earlier findings

- [x] **CR-1 (P2) | A sender change built while the settings were not loaded could replace the whole synced list**: fixed in fb91dc29e and still correct at HEAD.
  If `settings.settings()` is still `null` after `ready()`, `ImageAllowlist.change()` throws (`web/src/app/features/mail/read/image-allowlist.ts:75`). Nothing is PATCHed, and the chain stays usable through `result.catch` (line 81). c3e2f199a adds two regression tests. The first checks that `removeEntry` rejects without a PATCH, and that the next `add` starts from the stored list once the settings load. The second, at page level, checks that "Always load" with settings that cannot load still loads the images of this mail once, sends no PATCH and offers no "Stop". Both match the code: `mail-read-page.ts:166` swallows the rejection and `showImages()` still runs.
- [x] **CR-2 (P3) | "Stop" offered under policy "allow"**: fixed in fb91dc29e and still correct at HEAD.
  `senderAllowed` (`mail-read-page.ts:115-117`) requires `policy() === 'block'`. The new test changes the policy to "allow" while the mail is open and checks that "Stop" disappears and the proxied body stays. This works because `senderAllowed` is a `computed` over the settings signal.
- [x] **CR-3 (P3) | `storedSenders()` doc comment vs behaviour**: fixed in fb91dc29e and still correct at HEAD.
  If `getItem` throws, the function returns `null` (`image-allowlist.ts:134-138`), so `migrate()` removes nothing. The new test checks that the key stays. It then checks that a new instance, which stands for the next page load, merges the list with one PATCH and removes the key. The spy is restored in `finally`, so it cannot leak into other tests.
- [x] **CR-4 (P3) | Criterion 4 evidence overstated the Playwright run**: resolved in the evidence.
  The second evidence entry for criterion 4 says that `page.route` answers the image-proxy request. It also records a separate check of the real server proxy (200 image/png).

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Notes on the final revision

- The new tests check behaviour (PATCH payloads, the localStorage contents, the rendered buttons and the iframe `srcdoc`) and match the current code. No existing test was weakened or removed.
- This reviewer did not run the tests. Results come from `reviews/mail-images-web-settings/tests.md`: the focused `ng test`, `npm run check` and `sh scripts/check.sh` passed. Its Scope line names HEAD fb91dc29e "plus the uncommitted test additions". Those additions are what c3e2f199a committed, so the report still describes the final revision.
- Criterion 6 ("Unit tests cover the new behavior; `npm run check` and `sh scripts/check.sh` pass") is still unticked in the task file. The parent has to record it with `tasks.py verify` before `done`.

## Review conclusion

No material issues found.
