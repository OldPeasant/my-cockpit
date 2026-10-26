# Fix Report

Date: 2026-10-26

Task: runner-hung-session - Runner: end a session that hangs after its final result

Scope: current task (agents.py `Settled`, poll loop, argparse; tests/test_hung_session.py)

## Fixed findings

### CR-1 — `--result-grace 0` (or negative) kills every session at its first poll

Files changed:
- `agents.py`
- `tests/test_hung_session.py`

Problem:
`Settled.idle()` returned 0 when the session was not settled, so `idle() >= result_grace` was always true for a grace of 0 or less. Every session was killed at the first poll and logged as a clean exit.

Fix:
`idle()` now returns `None` when the session is not settled. The poll loop ends the session only when `idle is not None and idle >= result_grace`. `--result-grace` uses a new small argparse type `positive` that rejects values < 1, for both `run` and `start`, and the help text says ">= 1". The test helper `SettledUnit.quiet` now treats `None` as not quiet.

Validation:
- New `Options.test_grace_below_1_is_rejected` (0 and -5 for `run`, 0 for `start` exit with code 2) — Pass
- New `SettledUnit.test_unsettled_idle_is_none_so_no_grace_ends_it` — Pass
- `python -m unittest tests.test_hung_session` — Pass (27 tests)

### CR-2 — Background heuristic over-counts foreground Agent reports

Files changed:
- `agents.py`
- `tests/test_hung_session.py`

Problem:
`BACKGROUND_HINT` (`background|async|agentId`) was searched anywhere in the serialized tool_result. A foreground sub-agent's full report that mentioned "async" or "background" was counted as a pending background task that never settled.

Fix:
`BACKGROUND_HINT` is now anchored with `re.match`: `\s*(async agent launched|command running in background)`, case-insensitive. It is applied only to the first text of the tool_result content, which can be a string or a list of text blocks (new helper `first_text`). The existing launch texts ("Async agent launched successfully. agentId: fake-1", "Command running in background with ID: b1") still count.

Validation:
- New `SettledUnit.test_foreground_agent_report_mentioning_async_or_background_is_not_background` (string, block list, launch phrase only in a later block) — Pass
- New `SettledUnit.test_launch_acknowledgement_in_block_list_is_background` — Pass
- Existing `PendingBackgroundAgentNotEnded` runner test and background unit tests — Pass

### TW-1 — `Settled.feed` raises AttributeError on non-object `message`/`origin`

Files changed:
- `agents.py`

Problem:
`(ev.get("message") or {}).get(...)` and `(ev.get("origin") or {}).get(...)` raised AttributeError for a string value (for example `{"type":"system","message":"retrying"}`). This crashed the runner inside drain().

Fix:
Added `isinstance(..., dict)` guards for `message` and `origin`.

Validation:
- `SettledUnit.test_json_with_non_object_message_or_origin_does_not_raise` — Pass
- `UnusualJsonLineDoesNotCrashRunner` — Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `python -m unittest tests.test_hung_session.Options tests.test_hung_session.SettledUnit` — Pass (20 tests)
- `python -m unittest tests.test_hung_session` — Pass (27 tests, 140 s)

Full test suite:
- `python -m unittest tests.test_overbudget tests.test_push_failure` (the other agents.py runner tests) — Pass (40 tests, 430 s)
- Other tests/ suites (tasks.py-focused) were not run because these fixes do not touch tasks.py.

Lint/type-check/build:
- `python -m py_compile agents.py` — Pass

`.taskcheck`:
- `sh scripts/check.sh` — Pass ("all checks passed")

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
