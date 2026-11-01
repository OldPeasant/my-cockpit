# Code Review

Date: 2026-11-01

Task: and-mail-read-spam - Android mail read view: Spam button

Scope: `git diff b846f408b..9ec36835c` (Android changes only; the board file changes in `tasks/` were not reviewed as code). This is a re-review of the final revision. The production code was reviewed at 6d90f103a (same content as cff798fd8). This pass reviews the test-only commit 9ec36835c and confirms that the production code did not change.

Requirements:
- tasks/and-mail-read-spam.md acceptance criteria and recorded assumptions
- SPEC.md, docs/ARCHITECTURE.md, AGENTS.md project notes
- Server contract: `server/.../mail/api/MailActionService.kt` (MailAction.SPAM, MailActionError.NO_ROLE_FOLDER), `api/openapi.yaml` mail actions

Git range:
- Branch: main
- HEAD: 9ec36835c49fb34a994b1a220960ac8417d296ae
- Base/start: b846f408b7aeac5aa1bcd071d2697f5cb002b841
- Commits reviewed: 2 (6d90f103a implementation, 9ec36835c tests)

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## Production code unchanged since the earlier review

`git diff --stat 6d90f103a..9ec36835c -- android/app/src/main` is empty. Commit 9ec36835c touches only four files under
`android/app/src/test/.../mail/read/`: MailReadRepositoryTest, MailReadScreenTest, MailReadViewModelTest, and the new
MailReadSpamGermanTest. The findings and criterion checks from the earlier review still apply. There is one finding,
CR-1, which is unchanged.

## Review of the tests added in 9ec36835c

I checked each new assertion against the current production code and string resources. Gradle was not run, at the
parent's request.

- MailReadRepositoryTest, `only the result for the mail acted on counts, and a success carries no error code`:
  - An answer that has a result only for another id maps to `Ok(MailActionOutcome(false))`. This matches
    `firstOrNull { it.id == id.toString() }` returning null.
  - A success that carries an `errorCode` maps to `MailActionOutcome(true)` with a null code. This matches
    `takeIf { result.ok.not() }`.
  - A 400 maps to `Failed(400)`. This matches `mailCall`, which treats only status 500 and above as offline.
  - The request bodies for ARCHIVE and DELETE are asserted.
  - The `trimMargin()` plus `replace("\n", "")` construction produces valid JSON: the first line has no margin and the
    trailing blank line is dropped.
- MailReadScreenTest:
  - Spam without a code shows only `mail_spam_failed`.
  - NO_ROLE_FOLDER for ARCHIVE and DELETE names the Archive and Trash folders. This matches `roleFolder` and the
    `"$failed $reason"` join.
  - The button order is checked through `boundsInRoot.left`. ARCHIVE comes before DELETE, and DELETE before SPAM, which
    matches the Toolbar order at MailReadScreen.kt:169-175. The toolbar is a plain row, so the comparison is sound
    under LTR.
  - In the busy state, the spam button is disabled and a click records no callback. This duplicates the existing
    busy-state test only partly, and the explicit TalkBack-label lookup adds value.
- MailReadViewModelTest, the new running-spam test:
  - While the call is pending, `busy` is true. A second SPAM tap and a DELETE tap are ignored, because `run` returns
    early when busy. `coVerify` counts are 1 and 0.
  - An `Offline` result gives `ActionFailed(SPAM, null)` with `removed` false and `busy` false. This matches `act`,
    where an outcome that is not `Ok` becomes null and so has no error code.
  - `CompletableDeferred` and `assertFalse` are already imported.
- MailReadSpamGermanTest (new, `@Config(qualifiers = "de")`):
  - The expected texts match `values-de/strings_mail.xml`: "Als Spam verschieben", the `mail_spam_failed` text plus
    "Dieses Konto hat keinen Ordner „Spam“." from `%1$s` with `mail_folder_spam`, and "Fehler des Servers: %1$s".
  - Using a locale qualifier on the class follows existing project usage (the `en-rUS` calendar tests).
  - It calls `MailReadContent` with the public parameters (`zone`, `body`) and the default `MailReadCallbacks()`, the
    same way MailReadScreenTest does.
  - The test data is fictitious (example.org addresses).

The tests assert observable behavior (texts, enabled state, request bodies, state fields), not implementation details.
The mocks stand in only for the repository and network boundary. No existing test was weakened, deleted or skipped.

## Verification of acceptance criteria (carried over, still valid)

1. `MailListAction.SPAM("SPAM")` was added. The toolbar shows a Spam button after Archive and Delete
   (MailReadScreen.kt:175), with `Icons.Outlined.Report`, the TalkBack label `R.string.mail_spam` and the tag
   `mail-read-action:SPAM`. It is disabled while busy. The order and the busy state now have their own tests.
2. Success closes the view through `removed = true`. A failure keeps the view open and shows the server's `errorCode`.
   NO_ROLE_FOLDER becomes "This account has no Spam folder.". The repository, ViewModel, Compose and route tests cover
   both paths, including offline and failures without a code.
3. The new strings exist in `values` and `values-de`. The German strings are now exercised by a test.
4. Tests exist on all three layers, as listed above. I could not re-run Gradle. The parent must record evidence for the
   full `testDebugUnitTest` run, or for `.taskcheck`, on this revision.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | Two repositories now build the same mail action request** (unchanged from the earlier review; accepted, no fix in this task)
    - File: `android/app/src/main/java/ch/sonensei/cockpit/mail/read/MailReadRepository.kt:75`
    - Category: Quality
    - Problem: `MailReadRepository.act` repeats the request and result lookup of the private `MailRepository.act`
      (`mail/list/MailRepository.kt`, `MailActionRequestDto(action, listOf(id))` plus `firstOrNull { it.id == id }`).
      The only difference is that it keeps `errorCode`.
    - Why it matters: A future change to the action call has to be made in two places. The task deliberately limits
      `mail/list` changes to the enum, so this is acceptable for now.
    - Suggested fix: In a later task, change `MailRepository.act` to return `MailActionOutcome` and have
      `MailReadRepository` reuse it.

## Review conclusion

No material issues found.
