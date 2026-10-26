# Code Review

Date: 2026-10-26

Task: p6-smoke - Phase 6 demo script

Scope: `git diff f470e9b96..11e0ce729 -- web server` (final revision). Commits: 830125a25 (new `web/e2e/phase6/demo.spec.ts`, one property in `server/src/main/resources/application-e2e.properties`), ec250bb62 (test-writer: Birthdays calendar assertions in the phase 6 spec), 11e0ce729 (CR-1 fix in `web/e2e/README.md`). This re-review focuses on the changes since the first review of 830125a25. The earlier findings on 830125a25 still apply because its code has not changed.

Requirements:
- tasks/p6-smoke.md acceptance criterion and its recorded assumptions
- SPEC.md §13.2 Phase 6 demo script, steps 1-5; SPEC.md §8.3 (virtual read-only birthdays calendar)
- web/e2e/README.md (phase demo conventions, e2e-only hooks)
- AGENTS.md project notes (fictitious test data, conventions)
- reviews/p6-smoke/fixes.md (CR-1 fix)
- Existing phase demo specs and web/playwright.config.ts

Git range:
- Branch: main
- HEAD: 11e0ce729
- Base/start: f470e9b96d1a24a59b723fb4e7f79f9e2477cad0
- Commits reviewed: 3 (830125a25, ec250bb62, 11e0ce729)

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Resolved findings from the earlier review

- [x] **CR-1 | The e2e README does not mention that the e2e profile turns off the bookmark fetcher's SSRF protection** (P3, Quality): resolved in 11e0ce729.
    - `web/e2e/README.md` ("Writing specs") now has a "Bookmarks:" paragraph. It names `cockpit.bookmarks.fetch.allow-private-networks=true`, explains why it is set (the stack has no internet, and the phase 6 demo bookmarks the server's own page), and says that the e2e stack can't test SSRF refusal but the server unit tests do.
    - That last claim is accurate: `PageFetcherTests.kt` contains the test "hosts with a private, loopback or link-local address are refused before any request".
    - The paragraph uses the same style as the Mail paragraph above it, and Prettier passes.

## Notes on verified areas (no findings)

- New birthday assertions (ec250bb62, `web/e2e/phase6/demo.spec.ts:128-140`). I checked each locator against the current templates:
    - `.calendar-list .calendar-name` matches `web/src/app/features/calendar/views/calendar-view.html:29,38`. The exact text "Birthdays" matches `BirthdayCalendar.NAME`. The "Due tasks and ticklers" toggle also uses the `.calendar-name` class, but it is outside `.calendar-list`, so it does not conflict.
    - `.virtual-title`, the calendar name `<p class="note">`, "This date comes from a contact" and the "Open contact" link match `web/src/app/features/calendar/edit/event-dialog.html:15-31`.
    - The link has `[mat-dialog-close]`, so the dialog closes, and it routes to `/contacts/<eventId>`. For birthday occurrences, `eventId` is the contact id (BirthdayCalendar.kt KDoc), so the URL regex and the level-1 heading with Mira's name (`contact-detail.html:25`) check that the link leads back to the right contact.
    - Together, these assertions check what step 1 requires: the event belongs to the Birthdays calendar, it is read-only, and it links back to the contact. They do this through the UI, not through implementation details.
- The rest of 830125a25 is unchanged since the first review. Those notes still hold:
    - Step coverage 1-5 is complete.
    - Weekly reviews in the backup are checked conditionally. The `backup-contrib-review` dependency is recorded as an assumption, and the gap is annotated in the test run.
    - Server and page clocks are handled together, and `afterAll` calls `resetStack`.
    - `allow-private-networks` is set only in the e2e profile.
    - Test data is fictitious.
- Validation by the reviewer:
    - `npm --prefix web run e2e:typecheck` passes.
    - `npx --prefix web prettier --check web/e2e/README.md web/e2e/phase6/demo.spec.ts` passes.
    - Per the parent's instructions, I did not run the e2e suite. The parent runs it on 11e0ce729.

## Review conclusion

No material issues found.
