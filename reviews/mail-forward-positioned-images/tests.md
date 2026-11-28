# Test Report

Date: 2026-11-28

Task: mail-forward-positioned-images (Mail: forwards drop internal positioned-image references)

Scope: Task base = measured HEAD = `4b589527d340f3fd7dae0f40b5717b480637dddf` (claim commit). The task has no commits of its own: the behaviour was already on main before the claim, in `84aaa89fc` (mail-apple-inline-images CR-1: `DraftService.respond` FORWARD -> `withoutPositionedImages`), later refined in `b47e1646c` and `a346391a4` (mail-draft-positioned-images). The parent worker set the scope to the forward path of `server/src/main/kotlin/ch/sonensei/cockpit/mail/compose/DraftService.kt` (`respond`, `withoutPositionedImages`, `positionedImagesRemoved`) and its tests in `server/src/test/kotlin/ch/sonensei/cockpit/mail/compose/DraftSendGreenMailTests.kt`. Working tree clean, no uncommitted inputs.

Requirements:
- `tasks/mail-forward-positioned-images.md` acceptance criteria
- `AGENTS.md` and `docs/ARCHITECTURE.md` ("Coverage")
- Parent worker instructions (scope, focused test command)

## Test files changed

- None. Review only: the existing tests already cover every criterion.

## Tests added or updated

Count: 0

The existing GreenMail tests (`DraftSendGreenMailTests.kt`, lines 322-399) cover each criterion against a real IMAP/SMTP round trip (forward endpoint, the stored draft on the server, its MIME parts). Reviewing `respond` (lines 298-330) and `positionedImagesRemoved` (lines 472-488) showed no meaningful untested forward behaviour, so no test was added just to raise the count.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: forwarding an Apple-style html mail with a positioned image (no Content-ID) leaves no `cid:cockpit-part-` in the quoted html, and the image is still forwarded as an attachment | `a forward drops the positioned image reference from the html and forwards the image as an attachment` (checks the API html has no `cid:cockpit-part-` / `cockpit.invalid`, the attachment list is exactly `image0.jpeg`, the stored Drafts message has the jpeg part and no html part containing `cockpit-part-`) | `cd server && ./gradlew test --tests '*DraftSendGreenMailTests*'` | Not run: Gradle is denied to this sub-agent (permission system). The parent worker must run it. |
| AC1: plain-text mail with an inline image (reply and forward) | `a reply and a forward of a plain-text mail with a positioned image quote its text without the image reference` (forward: text kept and html-escaped, no `cockpit-part-`, attachment `image0.jpeg`, stored draft clean; reply: no reference, no attachments) | same | Not run: same reason |
| AC2: real `cid:` images in forwards keep today's behaviour | `a reply drops inline cid images, a forward keeps them as inline parts` (forward html keeps `cid:logo`, stored draft has the png as an INLINE part with its Content-ID); `a forward keeps a real cid image next to a dropped positioned image` (mixed mail: the real cid `img` element kept verbatim, positioned one dropped, attachments `logo.png`, `image0.jpeg`) | same | Not run: same reason |
| AC3: a test in the compose tests; `cd server && ./gradlew check` green | The four tests above live in `server/src/test/kotlin/ch/sonensei/cockpit/mail/compose/` | `cd server && ./gradlew check` | Test presence: verified by reading the code. `check`: Not run, same reason |

## Focused test execution

Command:

`cd server && ./gradlew test --tests '*DraftSendGreenMailTests*' jacocoTestReport` (also tried as a single `gradlew -p server ...` call)

Result:

Not run: the permission system denied the Bash call for both forms. No result XML for `DraftSendGreenMailTests` exists under `server/build/test-results/test/`, so there is no earlier result to cite either.

## Full test execution

Command:

`cd server && ./gradlew check`

Result:

Not run (Gradle denied to this sub-agent). The parent worker has to run it and `.taskcheck`.

## .taskcheck

Result:

Not run (`sh scripts/check.sh` runs Gradle, which is denied here).

## Bugs found

None found by code review. Reading the code: `respond` sends every non-null sanitized html of a FORWARD through `withoutPositionedImages`. That function removes only `img` elements whose `cid:` value is a `MimeContent.partImageId` (plus their bare wrapper `div`) and returns the html unchanged when there are none, so real `cid:` images stay and `forwardedAttachments` still carries every original attachment. This matches the criteria. It is not confirmed by a test run in this session.

## Coverage

Status: unavailable. The server coverage command (`cd server && ./gradlew test jacocoTestReport`, ARCHITECTURE.md "Coverage") could not be run because the permission system denies Gradle to this sub-agent.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured, and N/A in any case: the task changes no production lines (empty diff `4b589527d..HEAD`). An empty diff is not evidence of coverage.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `mail-forward-positioned-images`. Base and HEAD are both `4b589527d340f3fd7dae0f40b5717b480637dddf`, with no task-owned commits. The parent worker set the file scope explicitly: the forward path of `DraftService.kt`, implemented earlier in `84aaa89fc`, `b47e1646c` and `a346391a4`.
- Source/test snapshot identifier (including uncommitted inputs): clean tree at `4b589527d`. sha256 `DraftService.kt` = `ac9d80e5334f4ab9073be232b9e4fc6a42fe1fc6ef15ecf3e252c7575836ddf8`, `DraftSendGreenMailTests.kt` = `a56ed9a4a3bbbbe5fd3275e6d1468a29bdf4a6db58475222f4aca73380596c39`.
- Exact included files; renamed/deleted/excluded files and reasons: included `server/src/main/kotlin/ch/sonensei/cockpit/mail/compose/DraftService.kt` (forward path). Nothing renamed, deleted or excluded.
- Provider/version, command, working directory, and test selection: JaCoCo (Gradle `jacoco` plugin), `./gradlew test --tests '*DraftSendGreenMailTests*' jacocoTestReport` in `server/`. Attempted and denied, never executed.
- Run start/completion time and exit status: no run. The attempt was at about 2026-11-28T12:19+01:00 and was denied before execution.
- Generated report paths and report hashes/run identifier: none generated. `server/build/reports/jacoco/test/jacocoTestReport.xml` exists but is from 2026-11-28 11:31:04 +0100, before this session, from a run with unknown test selection. It is stale and deliberately not used.
- Evidence outputs are fresh and inputs stayed unchanged: no fresh output exists. No inputs were changed (no files edited apart from this report).
- Final run after latest test additions and production fixes: no. No tests were added and no fixes were made, but no coverage run was possible.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/compose/DraftService.kt` | not measured | not measured | not measured | not measured |

No aggregate: the only scoped file is unmeasured.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `DraftService.kt:485`, else branch (positioned `img` not inside a bare single-child `div`, so only the `img` is removed) | Defensive. `MimeContent` always writes positioned images inside a bare `div`, so a forward would only reach this branch with unusual html. | No test added: not reachable through the documented forward inputs. Branch hits were not measured. |
| `DraftService.kt:473` / `:481`, early `null` returns (no `cid:` at all, or only real `cid:` images) | AC2 (real `cid:` images unchanged) | Covered on paper by `a reply drops inline cid images, a forward keeps them as inline parts` (only real cid, so it reaches line 481). Hits were not measured. |

Follow-up: the parent worker should run `cd server && ./gradlew test --tests '*DraftSendGreenMailTests*' jacocoTestReport` and read the `ch/sonensei/cockpit/mail/compose` / `DraftService.kt` counters (INSTRUCTION, BRANCH, LINE, METHOD) in `server/build/reports/jacoco/test/jacocoTestReport.xml`.

## Unable to test

- Running the focused tests, `./gradlew check`, `.taskcheck` and JaCoCo coverage: Gradle is denied to this sub-agent. Follow-up needed: the parent worker runs them and records the results with `tasks.py verify`.

## Summary

No additional tests were necessary for the reviewed scope. Every acceptance criterion maps to an existing GreenMail test in `DraftSendGreenMailTests.kt`, and no production defects were found by code review. Execution and coverage are still open: the focused tests, `./gradlew check` and coverage could not be run in this sub-agent (Gradle denied), so the parent worker must run them before treating the criteria as verified.
