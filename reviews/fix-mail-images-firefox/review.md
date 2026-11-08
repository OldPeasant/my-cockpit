# Code Review

Date: 2026-11-08

Task: fix-mail-images-firefox - Mail images never load in Firefox (frame CSP 'self' in srcdoc)

Scope: `git diff 87ddb981..616ec692 -- . ':!tasks'` (scope given by the parent worker). Files reviewed in full at HEAD:
`web/src/app/features/mail/read/mail-frame.ts`, `web/src/app/features/mail/read/mail-frame.spec.ts`,
`web/e2e/mail-read/read.spec.ts`, `web/playwright.config.ts`, plus the relevant sections of `docs/ARCHITECTURE.md`
("Install", "Testing") and `web/e2e/README.md` for context. The reviewer could not use a shell in this session, so the
diff itself was not printed. The review is based on the current content of the changed files, which the parent named.

Requirements:
- tasks/fix-mail-images-firefox.md (acceptance criteria 1-4, notes and the recorded assumption)
- AGENTS.md project notes (conventions, e2e not in `.taskcheck`)
- docs/ARCHITECTURE.md ("Install", "Testing")
- web/e2e/README.md (project layout of the e2e suite)

Git range:
- Branch: main
- HEAD: 616ec69288ac3fc25bae14e175be64e8aac517d9
- Base/start: 87ddb981da9b900e18315c97980e7a0da569e40e
- Commits reviewed: 3 (2dd95a21, 5c49ac71, 616ec692)

Summary: 0 P0, 0 P1, 1 P2, 1 P3

## Acceptance criteria check

1. CSP: `mailFrameCsp(origin = location.origin)` builds `img-src 'self' <origin> data:` and `font-src 'self' <origin> data:`.
   It keeps `default-src 'none'` and `script-src 'none'` and adds no other host. The unit spec checks the origin in both
   directives, collects every host-like source across all directives and requires that only the origin appears,
   checks that `connect-src` is absent, and checks the CSP in the rendered `srcdoc`. Met. Inserting
   `location.origin` into the attribute is safe because an origin cannot contain a quote. The explicit origin allows
   nothing beyond what `'self'` already allows.
2. Firefox project: `firefox` with `testMatch: /mail-read\/.*\.spec\.ts$/` and `Desktop Firefox`. Install documented in
   ARCHITECTURE.md "Install" and "Testing". Met (but see CR-1 and CR-2).
3. e2e: `inlineImages()` checks that the `…/attachments/…?inline=true` image is `complete && naturalWidth > 0`, both
   before and after "Load images". The `cid:` image is served by the real server, not the stubbed proxy, so the test
   would catch the Firefox CSP block. The parent reports 37 passed for chromium and firefox.
4. Check: the parent ran it. The reviewer did not re-run it (no shell).

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | Firefox mail-read can overlap the `inbox-exclusive` project, which counts the shared inbox**
    - File: `web/playwright.config.ts:44-58`
    - Category: Integration
    - Problem: The new `firefox` project depends only on `phase-demos`. `inbox-exclusive` depends only on `chromium`.
      The config comment and `web/e2e/README.md` both say inbox-exclusive runs "after all other specs" because those
      specs fill the shared inbox. The Firefox run of `e2e/mail-read/read.spec.ts` clicks "→ Inbox task", which adds an
      item to the shared owner's inbox (the `loggedIn` fixture uses one shared account). Nothing orders `firefox` before
      `inbox-exclusive`. In a full `npm run e2e`, the Firefox test can still be running, or can be scheduled late, when
      chromium finishes and the gtd-inbox/review/a11y specs start. Those specs then count an extra inbox item.
    - Why it matters: The full e2e suite becomes intermittently flaky in specs this task did not touch. It also breaks
      the documented rule that inbox-exclusive runs last.
    - Suggested fix: Make `inbox-exclusive` depend on both projects (`dependencies: ['chromium', 'firefox']`) and update
      the comment.

## P3 Low

- [ ] **CR-2 | `web/e2e/README.md` still describes a chromium-only run and install**
    - File: `web/e2e/README.md:8`, `web/e2e/README.md:14`, `web/e2e/README.md:52-53`
    - Category: Quality
    - Problem: The e2e README still installs only `chromium` (`npx playwright install --with-deps chromium`). It still
      says `npm run e2e` "runs Playwright (chromium)" and that `inbox-exclusive` runs "after all other specs". A
      developer who follows it gets a missing-browser failure in the new `firefox` project on `npm run e2e`.
      ARCHITECTURE.md was updated, but this file, which ARCHITECTURE.md points to, was not.
    - Why it matters: The run instructions no longer match the configuration.
    - Suggested fix: Change the install line to `chromium firefox` and mention the `firefox` project for
      `e2e/mail-read/`. The file is outside the task's `touches`, so a one-line edit or a follow-up task is enough.

## Review conclusion

Material issues found; fix before completing the task. CR-1 is a cheap fix that prevents flaky e2e runs. CR-2 is
optional cleanup. The CSP fix, its unit tests and the Firefox e2e coverage meet the acceptance criteria.
