# Code Review

Date: 2026-10-21

Task: mail-gtd - Mail to GTD integration

Scope: final review of the whole task at revision daa39450, committed changes only. The task commits are `git log d12d5eb4..daa39450` (base supplied by the parent). The previous round reviewed 75ac48d5 in full: `MailGtdService.kt`, `MailGtdController.kt`, the TW-1 fix from 93acb541 and the escape and inline-link tests. This round is a delta review of `git diff 75ac48d5 daa39450`:
- 89260240 changes only `tasks/notes-markdown-escapes.md`. It answers CR-2 from the previous round.
- daa39450 adds one test to `server/src/test/kotlin/ch/sonensei/cockpit/mail/gtd/MailGtdWebTests.kt` and updates `reviews/mail-gtd/{tests,fixes,review}.md`.

`git diff --stat 75ac48d5 daa39450` confirms that no production file changed after the previous review. The production findings from that round therefore still apply without changes.

Requirements:
- tasks/mail-gtd.md: acceptance criteria and recorded assumptions, in particular #6 (backslash before ASCII punctuation written as U+2216) and the `[[` -> `[ [` assumption
- tasks/notes-markdown-escapes.md (follow-up task with the root fix, as changed in 89260240)
- reviews/mail-gtd/tests.md, reviews/mail-gtd/fixes.md, the previous reviews/mail-gtd/review.md (revision 75ac48d5)
- SPEC.md §7.3 and §4.2 (through earlier rounds), AGENTS.md project notes, docs/QUALITY.md "Freshness"

Git range:
- Branch: main
- HEAD: daa39450c03185cb3bc14d3afc36a1a0ccce311a
- Base/start: d12d5eb4a2e21d90cc8289fd5a1abfc2707357f1
- Commits reviewed: 2 since the previous review (89260240, daa39450). The earlier task commits up to 75ac48d5 were covered by the previous rounds.
- Uncommitted changes: none (`git status --short` is empty)

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## Verification of the new test (daa39450)

Test: `to-note keeps escapes in the subject as the title and neutralizes them in To and every Cc name` (`MailGtdWebTests.kt:489-526`).

- Coverage gap closed: the earlier escape tests used escapes only in the From name and the body. This test also puts escapes in the subject, in two To names and in two Cc names. It confirms that the header lines built at `MailGtdService.kt:139-140` go through `neutralizeInlineLinks` (`:156`), so every recipient is neutralized, not only the first one.
- I checked the expected strings by hand against `BRACKET_PAIR` followed by `ESCAPE = \\(?=\p{Punct})` (`:191`, `:267`):
  - `Erin \[[note:…]]`: `[[` -> `[ [` gives `\[ [note`, then `\[` -> `∖[` gives `∖[ [note:…]]`. This matches the expected value, and the order means no new `[[` is created.
  - `Frank \`code\` \«quote\» C:\Users\x\`: only the backslashes before the backticks are replaced. Before `«`/`»` (non-ASCII), before `U`/`x` (letters) and before the following space, the backslash stays. This matches the expected value and the comment in the test.
  - `Dan \<ops\>` -> `Dan ∖<ops∖>`. `Ann \*Example\*` -> `Ann ∖*Example∖*`.
  - Body `Zinnia budget \# draft` -> `Zinnia budget ∖# draft`.
- Title: the subject, including `\[[task:…]]`, stays verbatim as the note title. The test also checks that the body does not repeat the subject (`shouldNotContain "Re: "`) and that the note has exactly one linked end, the mail (`linkedEnds(...).single()`). So the `[[task:…]]` in the title does not create an inline link.
- Search: the note is found through `/api/v1/search?q=zinnia&types=NOTE`. This shows that indexing the neutralized body does not throw.
- The assertions check observable API behavior (201, note fields, links, search). They do not depend on implementation details.
- Execution: I ran `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.mail.gtd.MailGtdWebTests' -Pkotlin.daemon.jvmargs=-Xmx3g` on daa39450 with a clean working tree. The XML report (`TEST-ch.sonensei.cockpit.mail.gtd.MailGtdWebTests.xml`, timestamp 2026-10-21T06:09:05Z) shows 46 tests, 0 skipped, 0 failures, 0 errors, and it includes the new test.

## Verification of CR-2 (previous round) against 89260240

- `tasks/notes-markdown-escapes.md` now has `deps: [mail-gtd]`. Its touches include `MailGtdService.kt` and `server/src/test/kotlin/ch/sonensei/cockpit/mail/gtd`. It has a new acceptance criterion: remove the U+2216 workaround in `neutralizeInlineLinks`, update the mail-gtd escape tests to expect the original text, and pass `./gradlew test --tests 'ch.sonensei.cockpit.mail.gtd.*'`. Removing the workaround is now tracked, and the board will not let that task start before mail-gtd. **CR-2 is resolved.**
- The entity and numeric reference residual risk from the previous round ("Other escape forms") is now a note in the same task, so it is tracked too.
- Note for the follow-up task (no finding here): "keeps the mail's backslash escapes verbatim" leaves a detail open. A verbatim `\*` in `bodyMd` is still a Markdown escape, so the rendered note shows `*` without the backslash. If the goal is that the note shows the mail's text exactly as written, the follow-up probably needs to double the backslash (`\\*`). The task owner should decide this and record it with `tasks.py assume`. It does not affect mail-gtd.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | `MailConnectionException` handler copied from `MailMessageController` (carried over, deferred, follow-up task exists)**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/mail/gtd/MailGtdController.kt:87-96`
    - Category: Quality
    - Problem: This handler is the same as the one in `MailMessageController`, and `MailAccountController` has a third copy.
    - Why it matters: The mapping of mail server failures to Problem Details (502/503, `errorCode`) can drift between the mail endpoints.
    - Suggested fix: Leave it to the planned task `mail-connection-errors`. No change is needed in this task.

## Resolved since the previous round

- CR-2 (removing the U+2216 workaround was not tracked) is resolved by 89260240. See above.

## Notes (no finding)

- Freshness: daa39450 changes only a test file and files under `reviews/`. Production code is the same as at 75ac48d5. Under `docs/QUALITY.md` "Freshness", the test change is a code change, so this review and the round 5 test report should both be for revision daa39450. The parent sets `reviewed_revision` accordingly.
- AC3 (Testcontainers suite) and AC4 (`OpenApiContractTest`): I ran only `MailGtdWebTests` in this round. The parent's own `tasks.py verify` evidence for those criteria is still the authoritative record.

## Review conclusion

No material issues found. The new test is correct: I checked its expected values by hand, it runs and passes on daa39450, and it closes the coverage gap for escapes in the subject, To and Cc. CR-2 is resolved by the change to `notes-markdown-escapes`. The only open P3 finding, CR-1, is deferred to `mail-connection-errors` and needs no change in this task.
