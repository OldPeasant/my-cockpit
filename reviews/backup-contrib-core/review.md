# Code Review

Date: 2026-10-20

Task: backup-contrib-core - Backup contributors: core entities

Scope: second pass on the final task code, `git diff a8bc9af4 0524317d -- server` (commits 0bf2a50b, d11717d7, 0524317d), plus the hand-off note added to `tasks/backup-secrets.md` in 0524317d. Production: `server/src/main/kotlin/ch/sonensei/cockpit/core/backupcontrib/*`. Tests: `server/src/test/kotlin/ch/sonensei/cockpit/core/backupcontrib/CoreBackupFixtures.kt`, `CoreBackupContributorsTests.kt`, `server/src/test/kotlin/ch/sonensei/cockpit/backup/BackupWebTests.kt`. This pass also checks whether the first-pass findings (CR-1, CR-2, reviewed at 0bf2a50b) are resolved.

Requirements:
- tasks/backup-contrib-core.md acceptance criteria and its four recorded assumptions (`python tasks.py assumptions`)
- SPEC.md §10.2, §12, §14; docs/ARCHITECTURE.md; AGENTS.md project notes; docs/QUALITY.md
- reviews/backup-contrib-core/fixes.md, reviews/backup-contrib-core/tests.md
- tasks/backup-secrets.md, tasks/backup-restore.md (hand-off target and its dependency chain)

Git range:
- Branch: main
- HEAD: 0524317d
- Base/start: a8bc9af4a560dd82724d89669dac06c0d9dd5bdc
- Commits reviewed: 3 (0bf2a50b, d11717d7, 0524317d); no uncommitted changes

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## Status of the first-pass findings

- **CR-1 (P2, restored account has an unknown TOTP secret, no tracked hand-off): resolved for this task.**
    - `UserBackupContributor.import` (lines 81-97) now logs a WARN after it restores an active account. The message gives the number of unused recovery codes, and a separate message covers the lockout case with no unused codes. The messages hold no username, secret or hash, and the tests assert this. The class KDoc (lines 23-26) documents the interim limitation and names `backup-secrets` as the owner of the fix.
    - The hand-off is now in `tasks/backup-secrets.md` Notes (line 19), and that task already has a criterion for it (line 14: "the next login forces TOTP re-enrollment (test)"). Assumption #1 says the same. The task's `touches` field still does not list the folders the note names. This is CR-1 below (P3).
    - Restore has no production caller until `backup-restore`, and `backup-secrets` depends on `backup-restore`. So the remaining risk is tracked and cannot reach users before the follow-up task runs.
- **CR-2 (P2, import-only behavior and readable mirrors untested): resolved.** `CoreBackupContributorsTests` (9 tests) covers:
    - the TOTP secret is in no backup file, in any encoding;
    - after import the TOTP secret is new and decrypts through `SecretCipher`, the old secret fails, and a recovery code logs in;
    - both re-enrollment warnings;
    - an export with no user or settings writes neither file;
    - the `readable/` mirrors, their Markdown escaping, and that only pending reminders are listed.
  `server/build/test-results` shows CoreBackupContributorsTests 9/0 failures (2026-10-20T20:54Z), BackupRoundTripTest 1/0 and BackupWebTests 12/0. The reviewer read these results and did not re-run Gradle.

Checks on the fix commit that found no issue:
- `BackupWebTests` now compares the step count with `backups.exportSteps` instead of the literal `3`. It still checks the exact count of a completed job, so the assertion is not weaker.
- The SLF4J logger follows the core auth services' convention. WARN level suits an operator-relevant state that holds no secrets (AGENTS.md logging rule).
- The lockout test sets every `recovery_code.used_at`. The `@BeforeEach emptyCore()` clears that state, so it does not leak into other tests.
- The only other changes are three ktlint re-wraps in the test file.

Evidence state: `python tasks.py verify backup-contrib-core` still shows both criteria unticked. The parent worker records the evidence before `done`. This is not a finding.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | The hand-off target task does not list the code it now must change in `touches` or `deps`**
    - File: `tasks/backup-secrets.md:5-6` (note at line 19)
    - Category: Integration
    - Problem: The note says `backup-secrets` "touches core/backupcontrib and core/auth". But the task's front matter still lists only `server/.../backup` in `touches` and has `deps: [backup-restore]`, without `backup-contrib-core`. The board uses `touches` to warn about parallel edits to the same files (`tasks.py check`, the claim heads-up). So no warning appears if another task changes `core/auth` or `core/backupcontrib` while `backup-secrets` runs. Practical impact is small: this task will be on main before `backup-secrets` can start, because `backup-secrets` waits for `backup-restore`.
    - Why it matters: A note can be overlooked. The front matter is what the coordination tooling reads.
    - Suggested fix: Have the parent worker add `server/src/main/kotlin/ch/sonensei/cockpit/core/backupcontrib` and `server/src/main/kotlin/ch/sonensei/cockpit/core/auth` to `backup-secrets` `touches`, and optionally `backup-contrib-core` to its `deps`. Use the tasks tooling where it exists, and do not edit `status`/`owner`. Otherwise, record that this was left to the plan owner.

## Review conclusion

No material issues found. Both first-pass findings are resolved. The remaining P3 is a board-metadata cleanup and does not block completion.
