# Code Review

Date: 2026-10-26

Task: runner-hung-session - Runner: end a session that hangs after its final result

Scope: `git diff f7162b92907a8dbfb1646f0b6f2e8ea1a4475c6a..c64755cbd207378f26a3c955e89262f324382af3 -- agents.py tests/test_hung_session.py`. This is a re-review of the final revision. Since the previous review (commit c5b455ec9), the only code change is c64755cbd, which adds 3 unit tests to `SettledUnit` in tests/test_hung_session.py. The `reviews/` files are out of scope, except that tests.md was read for context.

Requirements:
- tasks/runner-hung-session.md (acceptance criteria 1-3 and the recorded ASSUMPTION about background-task detection)
- AGENTS.md (task protocol, Evidence, quality gate)
- docs/QUALITY.md (review artifact contract)
- docs/ARCHITECTURE.md (`tests/` runner tests run separately from `scripts/check.sh`)
- Existing agents.py and tests/test_hung_session.py conventions

Git range:
- Branch: main
- HEAD: c64755cbd207378f26a3c955e89262f324382af3
- Base/start: f7162b92907a8dbfb1646f0b6f2e8ea1a4475c6a (`git merge-base HEAD origin/main`)
- Commits reviewed: 5 (7f77368c8 implementation, 28da0dd64 tests, 6b3d5c526 fixes, c5b455ec9 review only, c64755cbd tests). The working tree was clean.

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## What was checked in this revision

- **agents.py has not changed since the last review.** `git diff 6b3d5c526 HEAD -- agents.py` is empty. 6b3d5c526 is the last commit that touches agents.py. The earlier review of this code, with CR-1, CR-2 and TW-1 confirmed fixed and the acceptance criteria checked, still applies.
- **New test `test_task_tool_alias_counts_like_agent`.** It checks the `("Agent", "Task")` branch in `Settled.feed` (agents.py:246-248). A `Task` launch acknowledgement keeps the session unsettled until a `task-notification` result arrives. The test checks behavior through `idle()` and does not depend on implementation details.
- **New test `test_odd_content_blocks_are_skipped_and_textless_agent_result_is_not_background`.** It covers the `isinstance(c, dict)` skip (agents.py:243-244). It also covers `first_text(None)` returning `""`, so a tool_result with no content is not counted as background. If either guard regressed, the test would fail with an AttributeError or with a wrong result.
- **New test `test_tool_result_for_unknown_tool_use_is_ignored`.** It covers the `tool_use_id in self.tools` guard (agents.py:250). A launch acknowledgement for an id that was never opened does not increment `background`.
- The three tests are deterministic. They use a fixed `now` and make no sleeps or subprocess calls. They match the style of the existing `SettledUnit` tests. No existing test was weakened, removed or skipped.
- tests.md names commits f9778f9f2 and 632e53f47 as the coverage baseline and fix commit. Neither is on the current branch history, probably because of a rebase. `git diff f9778f9f2 HEAD -- agents.py` shows the same 93+/6- change as the task diff, so the measured changed lines are still the task's lines. This is a note about the report's wording, not a code finding.

## Commands run

- `python -m unittest tests.test_hung_session.Options tests.test_hung_session.SettledUnit` -> Ran 23 tests in 0.005s, OK
- `python -m unittest tests.test_hung_session` -> Ran 30 tests in 138.210s, OK
- `git diff 6b3d5c526 HEAD -- agents.py` -> empty (agents.py unchanged since the reviewed fix commit)
- I did not run `sh scripts/check.sh` in this review. tests.md says it passed ("== all checks passed", exit 0, 250 s). The parent worker must record the `.taskcheck` evidence.

## Review conclusion

No material issues found.
