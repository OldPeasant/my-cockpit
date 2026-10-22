# Code Review

Date: 2026-10-22

Task: passkeys-server - Passkeys (WebAuthn) backend

Scope: `git diff fc7737af267cba0c4fcbf1b0e41c889b2232b745 1235c108058d8cddfdd5f0fb3e96166634ae0cd8` (fc7737af2 is an ancestor of HEAD). This report replaces the reviews of e111036d and 00be3bd6b.

After the rebase, the task commits are:
- e19f713c0 (implementation)
- 2e1aca37d (test-writer tests)
- 35f64b753 (fix for CR-1/TW-1)
- 3bb996573 (concurrency tests)
- 31f582ecf (review report)
- 8c2744705 (quality.json)
- 741d65149 (migration rename, formerly 00be3bd6b)
- 6589bd58e (duplicate-version test, which the coordinator called c94b045e2)
- 1235c1080 (OpenAPI regeneration)

The other commits in the range are `tasks/`-only heartbeat and assume commits from main.

`git log fc7737af2..1235c1080 -- server api web docs` lists only these task commits. So no code from main came into the range after the last review. New review work: the test in 6589bd58e and the `api/openapi.yaml` change in 1235c1080. Everything else is carried over from the 00be3bd6b and e111036d reviews because that code is unchanged.

Requirements:
- `tasks/passkeys-server.md`: acceptance criteria and recorded assumptions, including the two new ones from 2026-10-22 02:47 and 02:48 (migration renamed to V202610221005; `MigrationConventionTests` touched outside `touches`)
- `SPEC.md` §4.1, §4.2 and §10.2; `docs/ARCHITECTURE.md` (persistence conventions, Flyway); the `AGENTS.md` project notes (migration naming; generated files are regenerated, never merged by hand); `docs/QUALITY.md`
- Parent instructions: review the new test and the yaml change, and write the final report for 1235c1080

Git range:
- Branch: main
- HEAD: 1235c108058d8cddfdd5f0fb3e96166634ae0cd8
- Base/start: fc7737af267cba0c4fcbf1b0e41c889b2232b745
- Commits reviewed: 9 task commits (listed in Scope). There were no uncommitted code changes. Only the helper reports in `reviews/passkeys-server/` were modified in the working tree. This was a read-only inspection: I did not run Gradle.

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## New test (6589bd58e): `MigrationConventionTests`, "no two migrations share a Flyway version, even across module folders"

File: `server/src/test/kotlin/ch/sonensei/cockpit/core/persistence/MigrationConventionTests.kt:45-55`

- **Correct key.**
    - The test groups by `v[1] + v[2]`, which is the 12-digit timestamp plus the optional `_<n>` test suffix. This is the same version key that the existing lowest-version test uses (line 59).
    - `V202610221000` in `core/` and in `review/` would get the same key and fail the test. That is exactly the clash that broke Spring startup before 741d65149.
- **Scope of the scan.**
    - `classpath*:db/migration/**/*.sql` covers the main migrations and the test-only `core/V202610200000_1__core_test_persistence_fixture.sql`. The test fixture has its own key `202610200000_1`, so it does not collide with `202610200000`. This matches what Flyway sees in the test context.
    - The Flyway configuration fixtures under `ch/sonensei/cockpit/support/flyway/` are not under `db/migration`, so they are correctly excluded.
- **Unmatched names.** The test drops files whose name does not match (`filterKeys { it != null }`). That is fine because the first test in the class (lines 28-43) already fails for any such file.
- **Meaningful assertion.**
    - `duplicates.shouldBeEmpty()` prints the clashing paths on failure, and the clue gives a concrete fix.
    - The test does not depend on a database or Spring, so it is fast and deterministic.
    - In the current tree, no version prefix is used twice, so it passes. The parent's run at 1235c108 confirms this: 73 tests, 0 failures, including `*MigrationConventionTests`.
- **Outside `touches`.** The file is outside `touches`, and that is recorded as an assumption (02:48). The change only adds lines to a test class and one import. It does not change existing tests, so it has a low risk of conflicting with other tasks.

## OpenAPI regeneration (1235c1080)

- **Tag order only.** The diff `00be3bd6b..1235c1080 -- api/openapi.yaml` only reorders entries in the top-level `tags:` list.
    - The removed set (Bookmarks, Files, Contacts, Capture, Setup, Projects, Mail accounts, Search) equals the added set.
    - Every description is unchanged.
    - No path, operation or schema changed, and the `Passkeys` tag keeps its place.
- **Whole task diff.** Over the full task diff (`fc7737af2..1235c1080`), the YAML changes are:
    - this tag reordering plus the `Passkeys` tag;
    - the passkey paths (`@@ -403,0 +406,238`);
    - the passkey component schemas.

  Nothing from main was removed.
- **Regenerated, not edited by hand.** The file was regenerated rather than merged by hand, as `AGENTS.md` and `api/README.md` require. The parent reports that `npm --prefix web run api:gen` produces no diff in the generated web client. That fits a change limited to tag order, because the client generator groups by tag name and not by tag position.
- **Contract test.** The parent reports that `OpenApiContractTest` passes at 1235c108. At 00be3bd6b, the full `./gradlew -p server check` ran 4129 tests and only this test failed, on tag order. The other 4128 tests passed. That includes Spring context startup with the renamed migration and every passkey test. Between 00be3bd6b and 1235c108, only the YAML file and one test file changed, so the rest of that run is still valid for the final revision.

## Migration rename (741d65149, formerly 00be3bd6b; carried over)

- The rename is a pure rename (100%).
- `core/V202610221005__core_auth_passkey.sql` follows the `<module>/V<yyyyMMddHHmm>__<module>_<desc>.sql` convention. No version prefix is used twice, and the new test now guards this.
- No code or configuration refers to the old file name. The migration only depends on `app_user` (`core/V202610201000`), and `spring.flyway.out-of-order=true` is set.
- The old version was never on main, so no applied migration was edited.
- The previous CR-1 (P3) is resolved: the assumption from 02:47 records the new version. The older assumption line still names V202610221000, and that is fine because it is a historical entry and the newer line supersedes it.

## Merge with main (carried over)

- `SecurityConfiguration.kt`, `LoginService.kt`, `DeviceTokenService.kt` and all `core/auth/passkey/**` files are identical to the revision reviewed at e111036d.
- Main changed nothing under `core/` between the old and the new base. So the passkey changes to the shared auth classes still sit on the same main code they were written against.

## Carried over from the review of e111036d (code unchanged)

- **CR-1 of the first review is resolved.** `PasskeyService.verifyAssertion` takes the account lock (`users.findFirstForUpdate()`) before it loads the passkey.
- **Concurrency tests:**
    - The sign-counter and replay tests are correct.
    - Every wait is bounded, and the latch and executor are always cleaned up.
- **Ceremonies:**
    - Each challenge is 32 random bytes from `SecureRandom`.
    - Each ceremony is single use, expires after 5 minutes, and is kept in a bounded store.
    - A registration ceremony is bound to its owner.
    - The RP ID and origin come from the stored ceremony.
- **WebAuthn verification:**
    - User verification and user presence are required, and attestation is `none`.
    - The sign counter is checked and persisted, and the user handle must match the owner.
- **TOTP re-enrollment:**
    - Registration is refused while re-enrollment is required.
    - A passkey web login gets a re-enroll-only session.
    - A passkey token request gets 403 `TotpEnrollmentRequired`.
    - A restore wipes `auth_passkey`.
- **Errors and logging:** errors are Problem Details, and no secrets are logged.
- **Public endpoints and CSRF:** only the authentication-options, login and token endpoints are public, and CSRF still applies to the web login.

## Review conclusion

No material issues found.
