# Code Review

Date: 2026-11-01

Task: mail-account-label-email - Mail screens label accounts by email address, not the sender name

Scope: full task diff 801926989..2970e9f79 (base = merge-base with origin/main; task commits 659e0fa26 and
2970e9f79); committed changes only. Re-review after fixes: the delta since the previous review (659e0fa26..2970e9f79)
was checked in addition to the full diff.

Requirements:
- tasks/mail-account-label-email.md acceptance criteria and the recorded assumption (Android `MailAccount.name`
  stays the display name, computed `label` is shown and sorted by)
- AGENTS.md project notes (conventions, touches)
- reviews/mail-account-label-email/fixes.md (disposition of the previous findings)
- Existing code conventions in `web/src/app/features/mail/list` and `android/.../cockpit/mail/list`

Git range:
- Branch: main
- HEAD: 2970e9f79
- Base/start: 801926989627716b85b51517689cdba4b3a39e74
- Commits reviewed: 2

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## Verification notes

- Criterion 1 (web): `mail-folder-tree.ts` heading text and `aria-label` use `emailAddress`; `accountName` and
  `accountOf` in `mail-list-page.ts` use `emailAddress`. No other `displayName` use remains in
  `web/src/app/features/mail/list` production code.
- Criterion 2 (Android): `MailAccount.label` = `address.ifBlank { name }`; the `MailRow` chip and the
  `MailboxPicker` account title display `label`; `MailRepository.accounts()` sorts by `label`. No other account
  `name` use remains in `mail/list` production code. The computed property instead of a mapped field is covered by
  the recorded assumption.
- Criterion 3: `toAccount()` still maps `name` from `displayName`; compose, identities and Settings files are not
  touched by the diff.
- Criterion 4: web fixtures in both specs give the two accounts the same display name ("Ann Example") with distinct
  addresses. The new test `tells accounts with the same sender name apart by email address` asserts distinct chips
  in "All inboxes", that the sender name appears nowhere on the page, and the heading account line after switching
  to the second account's folder (same `params.next` + `flush` pattern as the existing test at line 406). Android
  tests (`MailModelsTest`, `MailRepositoryTest`, both Compose screen tests) assert the addresses. Gradle and
  `npm run check` were not run by this reviewer (sandbox); the parent reports Android BUILD SUCCESSFUL on 659e0fa26
  (2970e9f79 changes only a KDoc comment on Android) and `npm run check` green, with a rerun on HEAD pending. The
  parent must record the HEAD rerun as evidence.
- Previous CR-1 (stale KDoc on `MailRepository.accounts()`): fixed in 2970e9f79.
- Previous CR-2 (folder tree heading `title` repeats the address): kept on purpose, as fixes.md explains (tooltip
  for truncated long addresses). The previous review already allowed that, so it is not re-raised.
- All changed files are within the task's `touches`.

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
