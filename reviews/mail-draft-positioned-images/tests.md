# Test Report

Date: 2026-11-28

Task: mail-draft-positioned-images - Mail: drafts from other clients must not show positioned-image references

Scope: base 114ec5a2089e430bd5b0ac99db0a2907fc605a84 .. HEAD fd0cb62f1 (task commits 8b122312a, 2b736f9f5, c379bca47, fd0cb62f1; the last touches reviews/ only). Production change: `server/src/main/kotlin/ch/sonensei/cockpit/mail/compose/DraftService.kt` only. Working tree clean before this report (no uncommitted source or test inputs).

Requirements:
- `tasks/mail-draft-positioned-images.md` acceptance criteria and recorded assumption
- `docs/ARCHITECTURE.md` "Coverage"
- Parent instructions (minimal additions, roughly 12 minutes budget)

## Test files changed

- None in this round. The task commits already add 13 draft tests to `server/src/test/kotlin/ch/sonensei/cockpit/mail/compose/DraftSendGreenMailTests.kt` (written by the implementer and an earlier test-writer round).

## Tests added or updated

Count: 0

Reason: the existing tests already cover every acceptance criterion and the branches that matter (HTML draft, plain-text draft, html-only text blocks without a text part, real cid image next to a positioned one, real cid image alone, no cid at all, formatted html, styled div html through open and save, text-blocks html with a text alternative, two positioned images, a draft that is only a positioned image). Coverage (below) shows the remaining uncovered branches are defensive paths that `MimeContent` does not produce; no meaningful gap was found that justified a new test.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: `DraftService.view` removes positioned-image references from draft HTML; the image stays a draft attachment | `an html draft from another client with a positioned image opens, saves and sends without the image reference`; `a draft with a real cid image and a positioned image keeps the cid image and drops only the positioned one`; `a draft with a text alternative and formatted html plus a positioned image keeps its html`; `a draft with a text alternative and styled div html plus a positioned image keeps its html through open and save`; `a draft that is only a positioned image opens without an image reference` | `cd server && ./gradlew integrationTest --tests '*DraftSendGreenMailTests' --rerun` | Pass |
| AC2: a plain-text-only draft with an inline image does not become a broken HTML draft | `a plain-text draft from another client with a positioned image stays plain text through open, save and send`; `a draft with a text alternative and html of only text blocks plus a positioned image opens as plain text`; `a plain-text draft with two positioned images opens as plain text and keeps both images`; `an html-only draft of text blocks with a positioned image keeps its html, it has no plain text to fall back to` | same | Pass |
| AC3: GreenMail test appends such a draft to Drafts, opens, saves and sends it; neither view nor sent message contains `cockpit-part-` | the two "opens, saves and sends" / "through open, save and send" tests above | same | Pass |
| AC3: `cd server && ./gradlew check` green | not run by the test-writer (parent runs `.taskcheck`; no test changes in this round) | none | Not run: outside the time budget, parent owns final validation |
| Unchanged behaviour: drafts without positioned images keep their HTML | `a draft with a real cid image and no positioned image opens with its html unchanged`; `an html draft without any cid reference opens with its html unchanged` | same | Pass |

## Focused test execution

Command:

`cd server && ./gradlew integrationTest --tests '*DraftSendGreenMailTests' --rerun`

Result:

Pass - BUILD SUCCESSFUL in 1m 21s; `build/test-results/integrationTest/TEST-ch.sonensei.cockpit.mail.compose.DraftSendGreenMailTests.xml`: tests=29, failures=0, errors=0, skipped=0, timestamp 2026-11-28T11:13:55Z.

## Full test execution

Command:

`cd server && ./gradlew check`

Result:

Not run (time budget; no test or production file changed in this round). The parent records `./gradlew check` and `.taskcheck`.

## .taskcheck

Result:

Not run (parent runs it before `done`).

## Bugs found

None.

## Coverage

Status: verified (for the GreenMail integration suite that exercises this change).

Metric: whole-file coverage of task-changed production files, measured from the focused `integrationTest` run (the GreenMail tests are tagged `external` and are not part of `./gradlew test`, so the documented `test jacocoTestReport` would not include them).
Changed-line coverage: approximated over the executable lines of the added or modified functions (`withoutPositionedImages`, `positionedImagesRemoved`, `isBare`, `draftHtml`, `isPlainTextRendering`, and the `view` call at line 755), read from the JaCoCo XML line counters: lines 28/29 (96.6%), branches 42/48 (87.5%). This is function-level, not a strict intersection with diff lines.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: mail-draft-positioned-images; base 114ec5a2089e430bd5b0ac99db0a2907fc605a84; HEAD fd0cb62f1; commits and production file from the parent and `git diff --stat base..HEAD`.
- Source/test snapshot identifier (including uncommitted inputs): clean tree at fd0cb62f1; sha256 DraftService.kt ac9d80e5334f4ab9073be232b9e4fc6a42fe1fc6ef15ecf3e252c7575836ddf8; sha256 DraftSendGreenMailTests.kt a56ed9a4a3bbbbe5fd3275e6d1468a29bdf4a6db58475222f4aca73380596c39.
- Exact included files; renamed/deleted/excluded files and reasons: included `server/src/main/kotlin/ch/sonensei/cockpit/mail/compose/DraftService.kt`; excluded the test file and `reviews/` (not production); no renames or deletions.
- Provider/version, command, working directory, and test selection: JaCoCo via the Gradle `jacoco` plugin (project default version). Run 1: `./gradlew integrationTest --tests '*DraftSendGreenMailTests' --rerun` in `server/` (writes `build/jacoco/integrationTest.exec`). Run 2: `./gradlew -I ../.tmp/jacoco-it.init.gradle.kts jacocoIntegrationTestReport -q` in `server/`, a measurement-only Gradle init script in the Git-ignored `.tmp/` that registers a `JacocoReport` over `integrationTest.exec` and the main source set; no project build or coverage configuration was changed.
- Run start/completion time and exit status: tests ran 2026-11-28 12:13:49 to 12:15:09 +0100 (exec file timestamps), exit 0; report task exit 0.
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/integrationTestTw/report.xml`, sha256 37914b1e7d098ca46b4f2f99b29f44e0c2cb3eabd8cff39bc16eeaeb2c73ed18; execution data `server/build/jacoco/integrationTest.exec` written 12:15:09 by run 1 (`--rerun` forces a fresh run).
- Evidence outputs are fresh and inputs stayed unchanged: yes; the tree was clean and no files changed between the runs.
- Final run after latest test additions and production fixes: yes (HEAD includes the CR-1/CR-2 fix c379bca47; no later code change).

Values below use covered/total (percentage).

| Changed file | Instructions | Branches | Methods | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/compose/DraftService.kt` | 2550/3115 (81.9%) | 197/306 (64.4%) | 88/94 (93.6%) | 351/382 (91.9%) |

Whole-file numbers include pre-existing code of DraftService exercised only by this one GreenMail class; other DraftService paths are covered by other test classes that were not part of this run. No scoped file is unmeasured.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `DraftService.kt:485` (2 instructions, 3 of 6 branches: `parent == null`, and the `img.remove()` fallback when the image is not the only child of a bare div element) | Defensive: `MimeContent` always writes each positioned image alone in a bare div element, and the sanitizer keeps that shape; an img element in a parsed body always has a parent | Justified gap; not reachable from a real stored draft without a different `MimeContent` rendering |
| `DraftService.kt:523` (`else -> false`, a non-text, non-element node such as an HTML comment at body level) | Sanitizer strips comments before `draftHtml` runs | Justified gap |
| `DraftService.kt:520`, `522` (1 branch each: node types other than text or element inside a text block) | Same as above: only comments or similar nodes, removed by the sanitizer | Justified gap |
| `DraftService.kt:479` (1 branch of the `cid:` / part-image filter) | Short-circuit combination; both positive and negative outcomes are tested (real cid kept, positioned dropped) | Justified gap |

## Unable to test

- `cd server && ./gradlew check` and `.taskcheck` were not run by the test-writer because of the time budget; the parent worker runs them for the final validation.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. No additional tests were necessary for the reviewed scope.
