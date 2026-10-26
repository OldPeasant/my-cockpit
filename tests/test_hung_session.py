#!/usr/bin/env python3
"""Tests for a session that hangs after its final result (`claude -p` staying alive and idle after
background sub-agents): the runner ends it --result-grace s after its last output, as a normal exit (no
back-off, its final result in the log line, the next task is claimed), any output restarts the grace
period, and a session without a final result or with a tool still running is left to --timeout.
Runs tasks.py and agents.py (with a scripted fake agent) against a local bare remote in a temp dir.
Run: python3 tests/test_hung_session.py"""
import json, re, sys, time, unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.dont_write_bytecode = True
from test_overbudget import SRC, Runner  # noqa: E402
from test_push_failure import exempt  # noqa: E402

sys.path.insert(0, str(SRC))
from agents import Settled  # noqa: E402

FAKE_AGENT = r'''
import json, os, subprocess, sys, time
mode, marker = os.environ.get("FAKE_MODE", ""), os.path.join("..", "late-marker")
def tp(*a):
    p = subprocess.run([sys.executable, "tasks.py", *a], capture_output=True, text=True)
    print(p.stdout + p.stderr, flush=True)
    return p.stdout
def emit(**ev):
    print(json.dumps(ev), flush=True)
def result(text, **kw):
    emit(type="result", subtype="success", is_error=False, result=text, **kw)
out = tp("claim")
tid = out.split("CLAIMED ")[1].split()[0] if "CLAIMED " in out else None
if mode == "noresult":  # works (silently) forever: only --timeout may end it
    print("thinking...", flush=True)
    time.sleep(600)
if mode == "tool":  # a result, then a new turn whose tool runs (silently) for long
    result("first turn")
    emit(type="assistant", message={"content": [{"type": "tool_use", "id": "t1", "name": "Bash",
                                                 "input": {"command": "sleep 600"}}]})
    time.sleep(600)
if tid:
    tp("done", tid, "--force")
result(f"finished {tid}")
if mode == "late":  # output keeps coming for a while after the result: each line restarts the grace period
    for i in range(15):
        time.sleep(1)
        print(f"late output {i}", flush=True)
    emit(type="assistant", message={"content": [{"type": "text", "text": "notified"}]})
    result(f"notified {tid}", origin={"kind": "task-notification"})
    open(marker, "a").write(f"{tid}\n")
time.sleep(600)  # the hang: no exit after the final result
'''

GRACE, TIMEOUT = 3, 20
ENDED = re.compile(r"session ended: idle (\d+)s after final result: (.*)")


class Hung(Runner):
    fake_agent, budget, timeout = FAKE_AGENT, 10, TIMEOUT
    prepare = staticmethod(exempt)

    @property
    def extra_args(self):
        return ("--result-grace", str(GRACE), "--timeout", str(self.timeout))

    def before_start(self):
        self.marker = self.clone.parent / "late-marker"  # the fake agent runs in the clone

    def ended(self):
        return ENDED.findall(self.log.read_text())


class EndsAfterGrace(Hung):
    def test_hung_session_ends_after_grace_as_normal_exit_and_runner_continues(self):
        t0 = time.time()
        self.proc.wait(timeout=2 * TIMEOUT)  # exits once every task is done
        took = time.time() - t0
        log = self.log.read_text()
        self.assertIn("all tasks done", log)
        ended = self.ended()
        self.assertEqual([r for _, r in ended], ["finished a", "finished b"], log)  # final result in the log line
        self.assertTrue(all(GRACE <= int(n) < TIMEOUT for n, _ in ended), log)
        self.assertNotIn("timed out", log)
        self.assertLess(took, 2 * TIMEOUT, log)  # both sessions ended long before --timeout would have
        self.assertNotIn("backoff", log)
        self.assertNotIn("retrying", log)  # no back-off
        self.assertNotIn("failed sessions", log)  # not counted as failures
        self.assertEqual(self.state("a")["status"], "done")
        self.assertEqual(self.state("b")["status"], "done")  # claimed the next task after the hang


class OutputResetsGrace(Hung):
    mode, timeout = "late", 60  # room for the 15 s of late output
    tasks = (("a", "Task A", "1"),)

    def test_output_after_result_restarts_grace_period(self):
        self.proc.wait(timeout=60)
        log = self.log.read_text()
        # 15 s of output after the first result (grace 3 s): only ended after the last of it
        self.assertTrue(self.marker.exists(), log)
        self.assertEqual(self.ended()[0][1], "notified a", log)
        self.assertNotIn("timed out", log)


class NoResultNotEnded(Hung):
    mode = "noresult"
    tasks = (("a", "Task A", "1"),)

    def test_session_without_result_runs_until_timeout(self):
        self.wait_for("timeout", lambda: "timed out after" in self.log.read_text(), timeout=3 * TIMEOUT)
        self.assertEqual(self.ended(), [], self.log.read_text())


class RunningToolNotEnded(Hung):
    mode = "tool"
    tasks = (("a", "Task A", "1"),)

    def test_session_running_a_tool_after_a_result_runs_until_timeout(self):
        self.wait_for("timeout", lambda: "timed out after" in self.log.read_text(), timeout=3 * TIMEOUT)
        self.assertEqual(self.ended(), [], self.log.read_text())


def ev(**kw):
    return json.dumps(kw)


def tool_use(tid, name, **inp):
    return ev(type="assistant", message={"content": [{"type": "tool_use", "id": tid, "name": name, "input": inp}]})


def tool_result(tid, text):
    return ev(type="user", message={"content": [{"type": "tool_result", "tool_use_id": tid, "content": text}]})


def result(**kw):
    return ev(type="result", subtype="success", is_error=False, result="ok", **kw)


class SettledUnit(unittest.TestCase):
    """The rule itself, without a runner: when is a session quiet after its final result?"""

    def feed(self, *lines):
        s = Settled()
        for line in lines:
            s.feed(line)
        return s

    def quiet(self, s):
        return s.idle(now=s.last + 100) > 0

    def test_result_then_silence_is_settled(self):
        self.assertTrue(self.quiet(self.feed(tool_use("t", "Bash"), tool_result("t", "x"), result())))

    def test_idle_counts_from_last_output(self):
        s = self.feed(result(), "plain text")
        self.assertEqual(s.idle(now=s.last + 7), 7)

    def test_no_result_or_failed_result_is_not_settled(self):
        self.assertFalse(self.quiet(self.feed(tool_use("t", "Bash"), tool_result("t", "x"))))
        self.assertFalse(self.quiet(self.feed(ev(type="result", subtype="error_max_turns", is_error=True))))

    def test_new_turn_after_result_unsettles_until_next_result(self):
        s = self.feed(result(), ev(type="assistant", message={"content": [{"type": "text", "text": "hm"}]}))
        self.assertFalse(self.quiet(s))
        s.feed(result(origin={"kind": "task-notification"}))
        self.assertTrue(self.quiet(s))

    def test_open_tool_call_is_not_settled(self):
        self.assertFalse(self.quiet(self.feed(result(), tool_use("t", "Bash"), result())))

    def test_background_agents_keep_it_unsettled_until_their_notifications(self):
        s = self.feed(tool_use("a1", "Agent", prompt="tests"), tool_result("a1", "Async agent launched. agentId: x"),
                      tool_use("a2", "Agent", prompt="review"), tool_result("a2", "Async agent launched. agentId: y"),
                      result())
        self.assertFalse(self.quiet(s))
        s.feed(result(origin={"kind": "task-notification"}))
        self.assertFalse(self.quiet(s))
        s.feed(result(origin={"kind": "task-notification"}))
        self.assertTrue(self.quiet(s))

    def test_foreground_agent_and_background_bash(self):
        s = self.feed(tool_use("a", "Agent", prompt="x", run_in_background=False), tool_result("a", "done"),
                      result())
        self.assertTrue(self.quiet(s))
        s = self.feed(tool_use("b", "Bash", command="make", run_in_background=True),
                      tool_result("b", "Command running in background with ID: b1"), result())
        self.assertFalse(self.quiet(s))

    def test_finished_agent_without_background_hint_is_not_background(self):
        self.assertTrue(self.quiet(self.feed(tool_use("a", "Agent", prompt="x"), tool_result("a", "all fine"),
                                             result())))


if __name__ == "__main__":
    unittest.main()
