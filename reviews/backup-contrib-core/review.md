# Code Review

Date: 2026-10-20

Task: backup-contrib-core - Backup contributors: core entities

Scope: final pass on the whole task diff, `git diff 307fdf1a..d3620d37` (commits a86c38ad, f63fdb92, 061a0337, d3620d37). The only code change since the second pass is d3620d37: two new tests and one extended assertion in `server/src/test/kotlin/ch/sonensei/cockpit/core/backupcontrib/CoreBackupContributorsTests.kt`, plus new `deps`/`touches` in the front matter of `tasks/backup-secrets.md`. Production code under `server/src/main/kotlin/ch/sonensei/cockpit/core/backupcontrib/` is unchanged since the second pass. This pass re-read it in full. The second pass ran before a rebase and quoted the old hashes a8bc9af4..0524317d. The content is the same as a86c38ad..061a0337.

Requirements:
- tasks/backup-contrib-core.md acceptance criteria and its four recorded assumptions
- SPEC.md §10.2, §14; docs/ARCHITECTURE.md; AGENTS.md project notes; docs/QUALITY.md
- reviews/backup-contrib-core/fixes.md, reviews/backup-contrib-core/tests.md (uncommitted, current)
- tasks/backup-secrets.md (hand-off target)

Git range:
- Branch: main
- HEAD: d3620d37ee0129ecdc6c4605053fe0997f370a8d
- Base/start: 307fdf1a0bfeda40f78324f448462dffe1a8aa26
- Commits reviewed: 4. The only uncommitted change is `reviews/backup-contrib-core/tests.md`, which is outside the code scope.

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## Status of earlier findings

- **First pass CR-1 (P2, restored account gets an unknown TOTP secret and no tracked hand-off): resolved** in 061a0337, as confirmed in the second pass.
    - `UserBackupContributor.import` (lines 81-97) logs a WARN with no secrets after restoring a confirmed account.
    - The KDoc and assumption #1 document the interim limitation.
    - `backup-secrets` owns the forced re-enrollment.
- **First pass CR-2 (P2, import-only behavior and readable mirrors untested): resolved** in f63fdb92/061a0337.
- **Second pass CR-1 (P3, `backup-secrets` front matter does not list the code it must change): resolved** in d3620d37.
    - `tasks/backup-secrets.md` now has `deps: [backup-restore,backup-contrib-core]`.
    - Its `touches` now lists the main and test folders of `core/backupcontrib` and `core/auth`.
    - The edit leaves `status`/`owner` alone. The dependency is acyclic: this task depends only on `backup-core` and `reminders-core`.

## Checks on d3620d37

- The new test `importing an active owner warns with the number of unused recovery codes` uses up exactly one recovery code. It asserts the exact rendered message `sign in with a recovery code (N-1 unused) and re-enroll TOTP`. This matches the SLF4J template at `UserBackupContributor.kt:92-94` and tests the count logic at line 81.
- The new test `importing an owner who never confirmed TOTP logs no re-enrollment warning` covers the `totpConfirmedAt == null` branch (line 81). It also checks that the restored account stays inactive, so setup is still required.
- The lockout test now also asserts that only the lockout message is logged and that the username does not appear. This pins the `if/else` at lines 85-96.
- `OutputCaptureExtension` captures output per test method, so the negative `shouldNotContain` assertions cannot pass or fail because of another test's logs. `@BeforeEach emptyCore()` resets the `recovery_code` changes.
- Test results in `server/build/test-results/test` were written after the commit (21:07Z): CoreBackupContributorsTests 11/0 failures (21:13Z), BackupRoundTripTest 1/0 and BackupWebTests 12/0 (21:10Z). These match tests.md. The reviewer read the results and did not re-run Gradle.

## Acceptance criteria (whole task)

- **Criterion 1, the four contributors with readable sections: met.**
    - The user contributor writes `data/user.json` with the password hash and recovery code hashes.
    - The TOTP secret is never written. It is deferred to the secrets mechanism, as assumption #1 records. The test checks that no file contains the secret in plain text, encrypted, Base64 or hex form.
    - The settings, links and reminders contributors write `data/settings.json`, `data/links.jsonl` and `data/reminders.jsonl`.
    - The `readable/settings.md`, `links.md` and `reminders.md` mirrors escape Markdown table cells. The reminders mirror lists only pending reminders.
    - Rows go through JDBC, so ids, timestamps and versions survive exactly (assumption #4).
    - Settings declares a dependency on user (`dependsOn`).
- **Criterion 2, round trip with this module's `BackupFixture`s: covered by tests.**
    - `CoreBackupFixtures.kt` seeds a confirmed user with a used recovery code, settings with mixed JSON types and Unicode, manual and sync links with tricky labels, and a fired and a pending reminder.
    - BackupRoundTripTest passes in the recorded results.
    - Recording the evidence with `tasks.py verify` is the parent worker's job and is not a finding.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Review conclusion

No material issues found. All earlier findings (first pass CR-1 and CR-2, second pass CR-1) are resolved. The final revision d3620d37 only adds tests and task metadata, and it meets the acceptance criteria.
