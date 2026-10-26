# Code Review

Date: 2026-10-26

Task: mail-identities - Mail sending identities API and settings

Scope: committed changes of the task, `git diff 270652780..ef0da8140` without `tasks/`. Commits dea927fe6 (server identity API, openapi, generated client), 89176b623 (web Settings → Mail identities UI), 3144450d2 (more tests, first review), 2f970eea5 (fixes for CR-1..CR-4 of the first review), merge 292446ec5, and ef0da8140 (more identity tests). The generated client under `web/src/app/api/generated/` was checked only against `api/openapi.yaml`. This is the third review. The code up to 292446ec5 was reviewed with no findings. This pass covers the final revision. `git diff 292446ec5 ef0da8140 --stat` shows that ef0da8140 only changes two test files (`MailIdentityWebTests.kt` +62, `identities.spec.ts` +97) and no production code. So this pass focused on whether the new tests are correct and useful.

Reviewed revision: ef0da81404628a73b7aca7e8916109fae8c335e9

Requirements:
- tasks/mail-identities.md (both acceptance criteria and the two recorded assumptions)
- SPEC.md §4.2 (Problem Details, ETag/If-Match), §7.1 (identities)
- docs/ARCHITECTURE.md, AGENTS.md project notes (module boundaries, change events, no secret logging), docs/QUALITY.md
- Previous review and fix report: reviews/mail-identities/review.md (previous revision), reviews/mail-identities/fixes.md
- Existing conventions in `core/web/ETags.kt`, `mail/model/MailIdentity.kt`, migration `V202610201402__mail_model.sql`, `settings/mail/*`

Git range:
- Branch: main
- HEAD: ef0da81404628a73b7aca7e8916109fae8c335e9
- Base/start: 270652780c142c1a3cc5657639d69efd1e03c241
- Commits reviewed: 6 (5 task commits and 1 merge; ad0ecb3c9 only adds a task file)

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## Status of the previous findings

- CR-1 (PATCH accepted a blank `email`) is resolved. `MailIdentityService.update` calls `checkEmail` on the trimmed value before the uniqueness check and the assignment. ef0da8140 also tests that a whitespace-only email stores nothing.
- CR-2 (the hint said the account address stays available) is resolved. `identities.html` says that new mail starts with the default identity, or with the first one. The compose fallback is a separate task, `mail-compose-identity-fallback`.
- CR-3 (no reload after a 412 or 404) is resolved. `run()` (`identities.ts:163-178`) calls `refresh()` on a 412 or 404. Tests cover a stale edit and a stale removal (the removal test was added in ef0da8140), a 404 on edit and on remove, and a failed reload after a stale change.
- CR-4 (Edit/Add enabled during a save) is resolved. Add, Edit and Remove are disabled while `saving()`. ef0da8140 adds the same check during a removal.

## Tests added in ef0da8140

Server (`MailIdentityWebTests.kt:406-465`):
- `changing an unknown identity of the account is 404 Problem Details` covers an unknown id under the right account and under an unknown account, and checks the Problem content type.
- `a rejected change stores nothing and publishes no event` checks that a 409 (duplicate), a 400 (blank email) and a 412 (stale DELETE) leave name, email, default flag and `version` unchanged, and that no UPDATED/DELETED event is published. `@RecordApplicationEvents` is on the class and the `ApplicationEvents` parameter is per test, so the counts start at zero for each test and the earlier creates do not affect them.
- `a duplicate new default does not take the flag from the current default` checks that the uniqueness check runs before the old default loses its flag. The case-insensitive duplicate (`INFO@…`) also covers the `lower(email)` rule.
- `removing the default identity leaves no default` matches the recorded behavior: no other identity becomes the default. `findAll()` reads every account, but `setUp` empties `mail_account` first, so only this test's rows exist.
- `the identity API requires login` uses `@WithAnonymousUser` on the method, which overrides the class-level `@WithCockpitUser`. It checks GET/POST/PATCH/DELETE → 401 and that nothing was stored. CSRF is supplied, so the 401 comes from authentication and not from CSRF.

Web (`identities.spec.ts:437-532`):
- Stale removal → reload → the confirmation stays open → the retry sends `If-Match: "2"`. This matches `run()`, which leaves `removing` set on an error.
- 404 on edit → reload, alert shown, the remaining identity can be edited again.
- 412 → the reload fails (503) → the load error appears with "Try again", and recovery works. This covers the `refresh()` catch path.
- 409 → no reload. `expectNone` plus `http.verify()` in `afterEach` (`:65`) would catch an extra GET. The entered value is kept. The title also says "or another error", but this test only sends a 409. The non-409/412/404 path (no reload) is covered by `keeps an identity whose removal failed and says why` (500, enforced by `afterEach` verify).
- Removal in progress → confirm, Edit and Add are disabled. A second click sends no second DELETE (enforced by `verify`). Everything is enabled again after the reload.

The assertions test what the user sees and what goes over HTTP (headers, URLs, request count), not internal details. The mocks are only `HttpTestingController` and MockMvc, so they don't hide the behavior under test. No existing test was removed or weakened (the diff only adds lines). The parent reports: `MailIdentityWebTests` 24 tests pass with ktlint clean, and the full web suite passes (175 files, 3665 tests).

## Checked without findings (unchanged since the previous pass)

- Contract: routes, operation ids, request and response fields, required fields and status codes (201/204/400/404/409/412/428) in `api/openapi.yaml` match `MailIdentityController`. The generated client matches the yaml.
- ETag/If-Match: GET, POST and PATCH return `ETags.of(version)`. PATCH and DELETE call `ETags.checkIfMatch` before any change (428 when the header is missing, 412 when it is stale).
- Account scoping: an identity of another account gives 404. An unknown account gives 404 on list and create.
- Concurrency: writes for one account are serialized by a `PESSIMISTIC_WRITE` lock on the account row. The old default is cleared and flushed before the new one is set, so the partial unique index holds.
- Change events: `MAIL_IDENTITY` CREATED/UPDATED/DELETED, including UPDATED for the identity that loses the default flag.
- No logging was added, and nothing sensitive goes into Problem messages. Backup of `mail_identity` already existed (mail-model).

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Review conclusion

No material issues found.

The parent worker still has to record the acceptance criteria with `tasks.py verify`, run `.taskcheck`, and set `reviewed_revision` in `quality.json` to ef0da81404628a73b7aca7e8916109fae8c335e9.
