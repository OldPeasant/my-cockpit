#!/usr/bin/env python3
"""Tests for a session that hangs after its final result (`claude -p` staying alive and idle after
background sub-agents): the runner ends it --result-grace s after its last output, as a normal exit (no
back-off, its final result in the log line, the next task is claimed), any output restarts the grace
period, and a session without a final result or with a tool still running is left to --timeout.
Runs tasks.py and agents.py (with a scripted fake agent) against a local bare remote in a temp dir.
Run: python3 tests/test_hung_session.py"""
import contextlib, io, json, re, sys, time, unittest
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


FAKE_AGENT_2 = r'''
import json, os, subprocess, sys, time
prompt, mode = sys.argv[-1], os.environ.get("FAKE_MODE", "")
def tp(*a):
    p = subprocess.run([sys.executable, "tasks.py", *a], capture_output=True, text=True)
    print(p.stdout + p.stderr, flush=True)
    return p.stdout
def emit(**ev):
    print(json.dumps(ev), flush=True)
def result(text, **kw):
    emit(type="result", subtype="success", is_error=False, result=text, **kw)
if "You already own task `" in prompt:  # second session on the same task: finish it, then hang again
    tid = prompt.split("You already own task `")[1].split("`")[0]
    tp("done", tid, "--force")
    result(f"resumed and finished {tid}")
    time.sleep(600)
out = tp("claim")
tid = out.split("CLAIMED ")[1].split()[0] if "CLAIMED " in out else None
if mode == "bg":  # a background sub-agent whose completion notification never comes
    emit(type="assistant", message={"content": [{"type": "tool_use", "id": "ag1", "name": "Agent",
                                                 "input": {"prompt": "write tests"}}]})
    emit(type="user", message={"content": [{"type": "tool_result", "tool_use_id": "ag1",
                                            "content": "Async agent launched successfully. agentId: fake-1"}]})
elif mode == "junk":  # JSON lines a custom --cmd (or a future stream-json event) may print
    emit(type="system", message="retrying the request")
if mode != "nodone" and tid:
    tp("done", tid, "--force")
result(f"first session {tid}")
time.sleep(600)
'''


class Hung2(Hung):
    fake_agent = FAKE_AGENT_2
    tasks = (("a", "Task A", "1"),)


class EndedSessionResumesUnfinishedTask(Hung2):
    """Criterion 2: after an early end the runner goes on with the actual task state. The task is still ours
    and in progress, so the next session resumes it (no back-off, not a failure)."""
    mode = "nodone"

    def test_unfinished_task_is_resumed_after_an_early_end(self):
        self.proc.wait(timeout=2 * TIMEOUT)
        log = self.log.read_text()
        self.assertIn("all tasks done", log)
        self.assertEqual([r for _, r in self.ended()], ["first session a", "resumed and finished a"], log)
        self.assertIn("session start: resume a", log)
        self.assertNotIn("timed out", log)
        self.assertNotIn("retrying", log)
        self.assertNotIn("failed sessions", log)
        self.assertEqual(self.state("a")["status"], "done")


class PendingBackgroundAgentNotEnded(Hung2):
    """The incident's setup: a background sub-agent was launched and has not reported back yet. The final
    result is printed, but the session must not be ended early: only --timeout may end it."""
    mode = "bg"

    def test_session_with_pending_background_agent_runs_until_timeout(self):
        # the task is done, so the runner exits right after the timeout kill: wait for the exit, then read the log
        self.proc.wait(timeout=3 * TIMEOUT)
        log = self.log.read_text()
        self.assertIn(f"session killed, timed out after {TIMEOUT}s", log)
        self.assertEqual(self.ended(), [], log)


class UnusualJsonLineDoesNotCrashRunner(Hung2):
    """A JSON line whose `message` is not an object (structured logging of a custom --cmd) must not crash
    the runner: describe() treats such lines as unknown events, Settled must too."""
    mode = "junk"

    def test_runner_survives_json_line_with_string_message(self):
        self.proc.wait(timeout=2 * TIMEOUT)
        log = self.log.read_text()
        self.assertNotIn("Traceback", log)
        self.assertEqual([r for _, r in self.ended()], ["first session a"], log)
        self.assertIn("all tasks done", log)


class Options(unittest.TestCase):
    """--result-grace defaults to 60 s for `run` and `start`."""

    def parsed(self, argv, fn):
        import agents
        got = {}
        old_fn, old_argv = getattr(agents, fn), sys.argv
        setattr(agents, fn, lambda a: got.setdefault("a", a))
        sys.argv = ["agents.py", *argv]
        try:
            agents.main()
        finally:
            setattr(agents, fn, old_fn)
            sys.argv = old_argv
        return got["a"]

    def test_run_default_grace_is_60(self):
        a = self.parsed(["run", "agent-1", ".", "--owner", "box/agent-1", "--cmd", "x"], "cmd_run")
        self.assertEqual(a.result_grace, 60)

    def test_run_grace_option(self):
        a = self.parsed(["run", "agent-1", ".", "--owner", "box/agent-1", "--cmd", "x", "--result-grace", "5"],
                        "cmd_run")
        self.assertEqual(a.result_grace, 5)

    def test_start_default_grace_is_60(self):
        self.assertEqual(self.parsed(["start", "1"], "cmd_start").result_grace, 60)

    def test_grace_below_1_is_rejected(self):
        # 0 would end every session at its first poll (CR-1)
        run = ["run", "agent-1", ".", "--owner", "box/agent-1", "--cmd", "x", "--result-grace"]
        for argv, fn in [(run + ["0"], "cmd_run"), (run + ["-5"], "cmd_run"),
                         (["start", "1", "--result-grace", "0"], "cmd_start")]:
            with self.subTest(argv=argv), contextlib.redirect_stderr(io.StringIO()), \
                    self.assertRaises(SystemExit) as cm:
                self.parsed(argv, fn)
            self.assertEqual(cm.exception.code, 2)


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
        return (s.idle(now=s.last + 100) or 0) > 0

    def test_result_then_silence_is_settled(self):
        self.assertTrue(self.quiet(self.feed(tool_use("t", "Bash"), tool_result("t", "x"), result())))

    def test_unsettled_idle_is_none_so_no_grace_ends_it(self):
        # an unsettled session must never satisfy `idle >= grace`, whatever the grace (CR-1)
        for s in (Settled(), self.feed(result(), tool_use("t", "Bash"))):
            self.assertIsNone(s.idle(now=s.last + 1000))

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

    def test_error_result_after_success_unsettles(self):
        s = self.feed(result(), ev(type="result", subtype="error_during_execution", is_error=True))
        self.assertFalse(self.quiet(s))

    def test_extra_notification_does_not_go_below_zero(self):
        # a notification without a known background task must not hide a later real one
        s = self.feed(result(origin={"kind": "task-notification"}),
                      tool_use("a1", "Agent", prompt="x"), tool_result("a1", "Async agent launched. agentId: z"),
                      result())
        self.assertFalse(self.quiet(s))

    def test_blank_lines_do_not_restart_quiet_time(self):
        s = self.feed(result())
        last = s.last
        s.feed("   ")
        s.feed("")
        self.assertEqual(s.last, last)

    def test_non_object_json_only_restarts_quiet_time(self):
        s = self.feed(result(), "[1, 2]", "42")
        self.assertTrue(self.quiet(s))

    def test_json_with_non_object_message_or_origin_does_not_raise(self):
        # describe() ignores such lines; Settled runs inside the runner's drain(), so raising there kills the runner
        s = Settled()
        s.feed(ev(type="system", message="retrying the request"))
        s.feed(ev(type="result", subtype="success", result="ok", origin="cli"))
        self.assertTrue(self.quiet(s))

    def test_foreground_agent_report_mentioning_async_or_background_is_not_background(self):
        # a finished sub-agent's report may talk about async code or background tasks (CR-2)
        report = "Review done. Found an async race in the background job; agentId: abc (resume hint)."
        for content in (report, [{"type": "text", "text": report}],
                        [{"type": "text", "text": "Summary"}, {"type": "text", "text": "Async agent launched"}]):
            with self.subTest(content=content):
                s = self.feed(tool_use("a", "Agent", prompt="review"), tool_result("a", content), result())
                self.assertTrue(self.quiet(s))

    def test_launch_acknowledgement_in_block_list_is_background(self):
        launched = [{"type": "text", "text": "Async agent launched successfully. agentId: q"}]
        self.assertFalse(self.quiet(self.feed(tool_use("a", "Agent", prompt="x"), tool_result("a", launched),
                                              result())))

    def test_finished_agent_without_background_hint_is_not_background(self):
        self.assertTrue(self.quiet(self.feed(tool_use("a", "Agent", prompt="x"), tool_result("a", "all fine"),
                                             result())))


if __name__ == "__main__":
    unittest.main()
