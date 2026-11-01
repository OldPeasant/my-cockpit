# Fix Report

Date: 2026-11-01

Task: mail-account-label-email - Mail screens label accounts by email address, not the sender name

Scope: current task; findings CR-1 and CR-2 from `reviews/mail-account-label-email/review.md`

## Fixed findings

### CR-1 — Stale KDoc on MailRepository.accounts()

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/mail/list/MailRepository.kt`

Problem:
The KDoc said "Every account by name", but `accounts()` now sorts by `label` (the email address), not `name`.

Fix:
Comment changed to "Every account by its label (email address), with its selectable folders." Comment-only change; no
behavior change.

Validation:
- Re-read the file: the comment now matches `sortedBy { it.label.lowercase() }` — Pass
- Gradle (ktlint) not run by the fixer (Gradle cannot run in this sub-agent sandbox); the parent worker runs ktlint.

## Not fixed

### CR-2 — Folder tree heading tooltip now duplicates its text

Status:
Invalid (kept deliberately; the review allows keeping it)

Reason:
The heading `.account-name` is ellipsized (`text-overflow: ellipsis` in
`web/src/app/features/mail/list/mail-folder-tree.scss`), so the `[title]` binding in `mail-folder-tree.ts` shows long
email addresses in full when the visible text is truncated. The parent worker decided to keep it; no code change.

## Validation summary

Focused tests:
- None needed for a comment-only change.

Full test suite:
- Not run by the fixer (Gradle unavailable in this sandbox); the parent worker runs it.

Lint/type-check/build:
- Android ktlint: not run by the fixer; the parent worker runs it.

`.taskcheck`:
- Not run (parent worker runs it).

Remaining unresolved findings:
0

## Final status

All validated findings fixed; validation (ktlint, `.taskcheck`) is left to the parent worker.
