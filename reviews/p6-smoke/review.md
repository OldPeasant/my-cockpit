# Code Review

Date: 2026-10-26

Task: p6-smoke - Phase 6 demo script

Scope: `git diff f470e9b96..830125a25 -- web server` (commit 830125a25: new `web/e2e/phase6/demo.spec.ts`, one property in `server/src/main/resources/application-e2e.properties`)

Requirements:
- tasks/p6-smoke.md acceptance criterion and its four recorded assumptions
- SPEC.md §13.2 Phase 6 demo script, steps 1-5
- web/e2e/README.md (phase demo conventions, e2e-only hooks)
- AGENTS.md project notes (fictitious test data, conventions)
- Existing phase demo specs (web/e2e/phase0, phase1, phase2, phase4, phase5) and web/playwright.config.ts

Git range:
- Branch: main
- HEAD: 830125a25b0d33065dfe15d37fa7e26d05d28f62
- Base/start: f470e9b96d1a24a59b723fb4e7f79f9e2477cad0
- Commits reviewed: 1

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | The e2e README does not mention that the e2e profile turns off the bookmark fetcher's SSRF protection**
    - File: `server/src/main/resources/application-e2e.properties:8`, `web/e2e/README.md` ("Writing specs")
    - Category: Quality
    - Problem: The e2e profile now sets `cockpit.bookmarks.fetch.allow-private-networks=true`. Only the phase 6 spec's header comment and the properties comment say so. The README section that lists what the e2e stack does differently (test hooks, GreenMail, mail sync) does not mention it.
    - Why it matters: Someone who later writes a bookmarks spec that expects private or loopback URLs to be refused (SSRF protection, SPEC behaviour) will see them fetched on the e2e stack and not know why. The README is where spec authors look for this.
    - Suggested fix: Add one sentence to web/e2e/README.md: in the e2e profile the bookmark page fetcher may reach private and loopback addresses, so the e2e stack can't test SSRF refusal (the server unit tests cover it).

## Notes on verified areas (no findings)

- Step coverage:
    - Step 1: a contact created in the form, a vCard import of 2 contacts, Mira's birthday shown in the Birthdays calendar with her age, and the contact page showing a mail (IMAP APPEND + sync), an upcoming event (attendee) and a Waiting For item linked by contact id.
    - Step 2: the request matches what Android's `CaptureUploader` sends (`url`, `readLater`, no title). The spec polls until `metadataStatus` is FETCHED, then checks the title, the favicon and the item in the web Read later queue.
    - Step 3: the journal entry is typed in the editor and an image is pasted. Both are checked through the API and in the preview (`naturalWidth`). The 3x-a-week habit is checked through the UI on 3 days, and the streak goes 0, 0, 1 week and still shows after a reload.
    - Step 4: the wizard runs through all 8 steps, including inbox processing and the Waiting For item. The spec checks the status API and the reflection in the journal, then the dashboard shows "Today" with no start prompt.
    - Step 5: a backup through the UI. The spec checks the zip contents, changes the data after the backup, restores through the UI, signs in again with a recovery code and TOTP re-enrollment, and compares the snapshot exactly with the one before the backup. The journal image still renders after the restore.
- Weekly reviews in step 5 are covered conditionally because backup-contrib-review is still `todo` and is not a dependency. This is recorded as an assumption, the gap is annotated in the test run, and the check turns on automatically once `data/weekly_reviews.json(l)` exists. This is acceptable as documented.
- Clock handling: server and page clocks move together, the same way as phase 1. TOTP codes after the restore use `serverNow()`. The restored context sets its page clock too. `afterAll` calls `resetStack` (empties the database, resets the clock and lockout), so later specs get an empty stack with the real clock even when a step fails. Serial mode and the `phase-demos` project (1 worker) keep the steps in order.
- Security of the config change: the property exists (`BookmarkFetchProperties.allowPrivateNetworks`, default false) and is set only in `application-e2e.properties`. Only `deploy/docker-compose.e2e.yml` activates the e2e profile. No production profile or default is changed, and the e2e profile already exposes test-only hooks.
- Conventions: the test data is fictitious (`cockpit.test` / `example.org` addresses, a placeholder phone number, invented names) and the spec has its own account. The file follows the structure of the other phase specs (each has its own `apiOf`, `get` and `xsrf` helpers, as phase 2, phase 5 and review do). `npm run e2e:typecheck` passes.

## Review conclusion

No material issues found.
