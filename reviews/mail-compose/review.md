# Code Review

Date: 2026-10-21

Task: mail-compose - Drafts and sending

Scope: the whole task diff `git diff 3d685bd261f1b53e3de3b8e5129a0fa3f369e230..556ba961345b396d4eb82ccbde49b37602e9d4ac`, limited to `server/src/main/kotlin/ch/sonensei/cockpit/mail/compose`, its tests, `FilesService.open`, `api/openapi.yaml` and `web/src/app/api/generated/`. This is the final re-review (round 4). The previous round reviewed the code up to the round-2 fixes (`cfa00bcc`, at that time `fed63698` before the rebase) and found 0 findings. This round focuses on what changed since then: `git diff cfa00bcc..HEAD -- server api web`. Only committed changes were reviewed. The working tree was clean.

Requirements:
- `tasks/mail-compose.md`: acceptance criteria 1-4 and all ASSUMPTION notes (10:47 and 11:53)
- SPEC.md §7.3 and §4.2, AGENTS.md project notes, `docs/QUALITY.md`
- The previous `reviews/mail-compose/review.md` (round 3), `reviews/mail-compose/fixes.md`, `reviews/mail-compose/tests.md`
- Parent instructions (final re-review of the changes since the previous review; Gradle not rerun)

Git range:
- Branch: main
- HEAD: 556ba961345b396d4eb82ccbde49b37602e9d4ac
- Base/start: 3d685bd261f1b53e3de3b8e5129a0fa3f369e230
- Commits reviewed: 11 task commits (cbe6405c..ab7dd97d), the merge 7512426e and the report commit 556ba961. The new focus is ab7dd97d.

Read: `git diff cfa00bcc..HEAD` for `server`, `api` and `web`. The only change there is in `server/src/test/kotlin/ch/sonensei/cockpit/mail/compose/DraftMimeTests.kt`: 34 added lines, which are 3 new tests and 1 helper. I also read the helpers those tests use (`composed`, `roundTrip`, `raw`) and the coverage and summary sections of `tests.md`. The merge 7512426e and the commit 556ba961 change no production code, `api/openapi.yaml` or generated client files; they only touch `tasks/` and `reviews/`. So the production code, the OpenAPI document and the generated web client are byte-identical to what round 3 reviewed.

## Changes since the previous review

- `DraftMimeTests`: there are 3 new tests (CRLF in a display name, a subject, or an attachment file name must not inject a `Bcc:` header). They put into practice the header-injection suggestion from the round-3 notes. They check the parsed Bcc recipients after a round trip and the raw top-level header lines. These assertions are about behavior, use fictitious `.test` addresses, and do not weaken any existing test. According to `tests.md`, all 141 compose unit tests pass, including these 3.
- No regression is possible from this change, because it touches only tests.

## Whole-task verdict against the acceptance criteria

1. Drafts API, SMTP send, attachments and Sent copy: implemented as reviewed in rounds 1-3. Drafts live on the IMAP Drafts folder, keep a stable id through the Message-ID, and use the UID as their ETag. Sending uses XOAUTH2 for OAuth accounts and builds HTML with a plain-text alternative. Identity is checked against the account. Attachments come from upload or from Files through `FilesService.open`. The Sent copy is appended only for `IMAP_SMTP` accounts. All of this matches the ASSUMPTION notes.
2. Reply, reply-all, forward and correspondents: also as reviewed. Replies set quoting, `Re:`/`Fwd:`, In-Reply-To and References. Forwards carry the attachments. The correspondents endpoint excludes Drafts, Trash and Spam and the user's own addresses. The earlier CR-5 (missing index) stays deferred by the 11:53 assumption.
3. GreenMail tests: `tests.md` reports 30 of 30 `external` integration tests passing. They cover a received attachment, the Sent append and draft replacement.
4. OpenAPI: `tests.md` reports that `OpenApiContractTest` passes without the flag. `api/openapi.yaml` and the generated client have not changed since the reviewed revision.

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Notes (not findings)

- `a display name with CRLF...` and the other new tests check only the top-level header lines of the message. That is the right check for the message envelope. A CRLF in an attachment file name would land in the part headers, which these tests do not inspect. This is harmless: part headers do not affect who receives the message, and production code strips client paths from upload file names and RFC 2047-encodes them.
- The parent worker must still record evidence with `tasks.py verify` for criteria 1-4, run `.taskcheck` in full, and set `reviewed_revision` in `quality.json` to the final hash.

## Review conclusion

No material issues found.
