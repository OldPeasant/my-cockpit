# Fix Report

Date: 2026-10-26

Task: p6-smoke — phase 6 demo script (contacts, read later, journal & habits, weekly review, backup round trip)

Scope: current task, approved finding CR-1 from `reviews/p6-smoke/review.md`

## Fixed findings

### CR-1 — The e2e README does not mention that the e2e profile turns off the bookmark fetcher's SSRF protection

Files changed:
- `web/e2e/README.md`

Problem:
The e2e profile (`server/src/main/resources/application-e2e.properties`) sets
`cockpit.bookmarks.fetch.allow-private-networks=true` so the phase 6 demo (`web/e2e/phase6`) can bookmark the
server's own page `http://localhost:8080/`. The README's "Writing specs" section, where spec authors look for
what the e2e stack does differently, did not say so.

Fix:
Added a short "Bookmarks:" paragraph to "Writing specs" (after the Mail paragraph, same style): the stack has no
internet, so the e2e profile sets `cockpit.bookmarks.fetch.allow-private-networks=true` and the bookmark page
fetcher may reach private and loopback addresses (the phase 6 demo bookmarks the server's own page); the e2e
stack therefore can't test the refusal of such URLs (SSRF protection), which the server unit tests cover. The
last claim was checked against `server/src/test/kotlin/ch/sonensei/cockpit/bookmarks/PageFetcherTests.kt`
(test "hosts with a private, loopback or link-local address are refused before any request"). No code changed.

Validation:
- `npx --prefix web prettier --check web/e2e/README.md` — Pass ("All matched files use Prettier code style!")

## Not fixed

None.

## Validation summary

Focused tests:
- `npx --prefix web prettier --check web/e2e/README.md` — Pass

Full test suite:
- Not run (documentation-only change; no code or test changed).

Lint/type-check/build:
- Prettier check of the changed file — Pass. Nothing else applicable.

`.taskcheck`:
- Not run (documentation-only change; the parent worker runs it before `done`).

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
