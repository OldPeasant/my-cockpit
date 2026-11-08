# Code Review

Date: 2026-11-08

Task: fix-mail-images-firefox - Mail images never load in Firefox (frame CSP 'self' in srcdoc)

Scope: `git diff 54f0e79f..9b95dfd5 -- . ':!tasks' ':!reviews'` (task commits 1c03e70f..9b95dfd5, committed changes only)

Requirements:
- `tasks/fix-mail-images-firefox.md` acceptance criteria and notes
- `AGENTS.md` project notes (conventions, Evidence rules)
- `docs/ARCHITECTURE.md` (Install, E2E table)
- `web/e2e/README.md`
- Previous review findings CR-1/CR-2 and `reviews/fix-mail-images-firefox/fixes.md`

Git range:
- Branch: main
- HEAD: 9b95dfd5
- Base/start: 54f0e79f
- Commits reviewed: 5 (1c03e70f, 0be1f9b2, 57b04279, fce80bf9, 9b95dfd5)

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | New lines go past the files' wrap width**
    - File: `web/e2e/README.md:16`, `web/e2e/mail-read/read.spec.ts:17`
    - Category: Quality
    - Problem: The rewrapped README paragraph has a 151-character line ("The server image is tagged per project ...
      tear the stack"); the rest of the paragraph wraps at about 115. The read.spec doc comment line 17 is 122
      characters, while the rest of the comment wraps at 100. Prettier does not reflow prose or comments, so lint
      does not flag either line.
    - Why it matters: Small readability and consistency issue in docs and comments that other agents edit.
    - Suggested fix: Rewrap both paragraphs to the width the surrounding lines use.

## Verification notes

- Criterion 1: `mailFrameCsp(origin = location.origin)` (`web/src/app/features/mail/read/mail-frame.ts:20`) adds the
  origin to `img-src` and `font-src` only. It keeps `'self'` and `data:`, still sets `default-src 'none'` and
  `script-src 'none'`, and allows no other host. `location.origin` cannot contain `;`, quotes or spaces, so the
  directive cannot be injected through it. Image URLs from the server are root-relative (`MailReadService.kt:308`,
  `:318`), so naming the page origin covers both the proxy and the `cid:` attachment URLs. The new spec pins every
  directive exactly (`mail-frame.spec.ts` "allows exactly the listed sources"), lists the hosts and checks that
  there is no `connect-src`. It also covers a dev origin with a port, the `location.origin` default and the
  rendered srcdoc meta. Only `mail-frame.ts` uses the removed `MAIL_FRAME_CSP` export; no other web code references
  it. The Android `mailDocument` is separate and outside this task's scope. Criterion 1 has no recorded evidence yet;
  the parent worker still has to record it.
- Criterion 2: the `firefox` project (`web/playwright.config.ts`) matches `mail-read/*.spec.ts` and depends on
  `phase-demos`. Install lines in `docs/ARCHITECTURE.md` and `web/e2e/README.md` name `chromium firefox`. The fix for
  earlier CR-1 (`inbox-exclusive` depends on `['chromium', 'firefox']`) and earlier CR-2 (README) is present and
  correct.
- Criterion 3: the e2e test now builds a `multipart/related` mail with a `cid:` PNG part. It asserts the inline image
  loads (`complete && naturalWidth > 0`) before and after "Load images". The CSP check happens in the browser before
  the network, so the stubbed proxy route still catches a CSP block of the proxied image. The recorded run (37 passed:
  35 phase-demos + chromium + firefox) predates 9b95dfd5. That commit only changes `inbox-exclusive` dependencies and
  docs, which a `e2e/mail-read` filtered run does not execute, so the evidence still applies.
- Criterion 4 (`npm run check`, `sh scripts/check.sh`) has no recorded evidence yet; the parent worker still has to
  run and record it.
- The `mail-frame.spec.ts` checks "allows no foreign host..." and "allows exactly the listed sources" partly overlap.
  This is harmless and not raised as a finding.

## Review conclusion

No material issues found.
