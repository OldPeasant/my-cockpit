#!/usr/bin/env python3
"""Tests for two runner bugs: a runner whose clone is behind main (an older tasks.py without what agents.py
needs) must not crash, and pushes that only lost races with other agents (non-fast-forward, `cannot lock
ref`, a transient `remote rejected ... (failed)`) must not be reported as refused (PUSH FAILED, the
push-failure record that blocks the task), while a remote that really refuses (auth, hook) still is.
Runs tasks.py and agents.py against a local bare remote. Run: python3 tests/test_push_race.py"""
import shutil, sys, time, unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.dont_write_bytecode = True
from test_overbudget import SRC, Board, field, sh  # noqa: E402
from test_push_failure import FAKE_AGENT, PushRunner, hook  # noqa: E402

sys.path.insert(0, str(SRC))
import tasks  # noqa: E402


class Classify(unittest.TestCase):
    def test_lost_races(self):
        for err in (" ! [rejected]        main -> main (fetch first) | error: failed to push some refs",
                    " ! [rejected]        main -> main (non-fast-forward)",
                    "remote: error: cannot lock ref 'refs/heads/main': Unable to create 'main.lock': File exists. | "
                    " ! [remote rejected] main -> main (reference already exists)",
                    " ! [remote rejected] main -> main (failed)",
                    " ! [remote rejected] main -> main (failed to update ref)",
                    " ! [rejected]        main -> main (stale info)"):
            self.assertTrue(tasks.lost_race(err), err)

    def test_refusals(self):
        for err in (" ! [remote rejected] main -> main (pre-receive hook declined)",
                    "remote: Permission to o/r.git denied to bot. | fatal: unable to access: The requested URL "
                    "returned error: 403",
                    "fatal: Authentication failed for 'https://example.test/r.git/'",
                    " ! [remote rejected] main -> main (protected branch hook declined)",
                    " ! [remote rejected] main -> main (refusing to allow an OAuth App to create or update workflow "
                    "`.github/workflows/ci.yml` without `workflow` scope)",
                    # a hook that happens to mention a race word is still a hook refusal
                    "remote: fetch first, then try | ! [remote rejected] main -> main (pre-receive hook declined)",
                    "fatal: Could not read from remote repository.",  # unknown: treated as refused, as before
                    ""):
            self.assertFalse(tasks.lost_race(err), err)


class Races(Board):
    """tasks.py alone against a remote whose branch is locked by another push in progress."""
    who = "x/agent-1"

    def setUp(self):
        super().setUp()
        self.tp("claim", "a", who=self.who)
        self.refs = self.tmp / "remote.git" / "refs" / "heads"

    def lock(self, ref):
        f = self.refs / f"{ref}.lock"
        f.parent.mkdir(parents=True, exist_ok=True)
        f.write_text("")
        return f

    def failure_file(self):
        rel = sh(self.w, "git", "rev-parse", "--git-path", "tfp-push-failed").stdout.strip()
        return self.w / rel

    def assert_lost_race(self, p):
        out = p.stdout + p.stderr
        self.assertNotEqual(p.returncode, 0, out)
        self.assertIn("BUSY: ", out)
        self.assertIn("lost the push race 5 times", out)
        self.assertIn("try again", out)
        self.assertNotIn("PUSH FAILED", out)
        self.assertNotIn("Only a human", out)
        self.assertNotIn("Traceback", out)
        self.assertFalse(self.failure_file().exists(), "a lost race must not block the task")
        t = self.remote_task("a")
        self.assertEqual((field(t, "status"), field(t, "owner")), ("in_progress", self.who))

    def test_board_push_that_loses_every_race_is_not_refused(self):
        lock = self.lock("main")
        self.assert_lost_race(self.tp("release", "a", "--note", "x", who=self.who, check=False))
        lock.unlink()  # the other push finished: trying again works
        p = self.tp("release", "a", "--note", "x", who=self.who, check=False)
        self.assertEqual(p.returncode, 0, p.stdout + p.stderr)
        self.assertIn("RELEASED a", p.stdout)

    def test_wip_push_that_loses_every_race_is_not_refused(self):
        (self.w / "work.txt").write_text("work\n")
        sh(self.w, "git", "add", "-A")
        sh(self.w, "git", "commit", "-qm", "work")
        lock = self.lock("wip/a")
        self.assert_lost_race(self.tp("release", "a", "--note", "x", who=self.who, check=False))
        self.assertTrue((self.w / "work.txt").exists())  # the work is still here
        lock.unlink()
        p = self.tp("release", "a", "--note", "x", who=self.who, check=False)
        self.assertEqual(p.returncode, 0, p.stdout + p.stderr)
        wip = sh(self.tmp, "git", "--git-dir=remote.git", "ls-tree", "--name-only", "wip/a").stdout
        self.assertIn("work.txt", wip)

    def test_hook_refusal_with_race_words_is_still_refused(self):
        hook(self.tmp, "#!/bin/sh\necho 'cannot lock ref: branch is protected, fetch first' >&2\nexit 1\n")
        p = self.tp("release", "a", "--note", "x", who=self.who, check=False)
        out = p.stdout + p.stderr
        self.assertNotEqual(p.returncode, 0, out)
        self.assertIn("PUSH FAILED: ", out)
        self.assertIn("Only a human can fix it", out)
        self.assertNotIn("BUSY", out)
        self.assertTrue(self.failure_file().exists())


class BehindClone(PushRunner):
    """The agent's clone is on an older main whose tasks.py lacks PUSH_FAILURE (which agents.py reads after
    every session); the human's checkout (agents.py) is newer. The runner must keep working."""
    fake_agent, mode, budget = FAKE_AGENT, "release", 10

    def prepare(self, w):
        super().prepare(w)
        f = w / "tasks.py"
        old = "\n".join(l for l in f.read_text().splitlines() if not l.startswith("PUSH_FAILURE ="))
        f.write_text(old + "\n")

    def before_start(self):  # the clone exists (old main); then the human pushes the new tasks.py
        shutil.copy(SRC / "tasks.py", self.w / "tasks.py")
        sh(self.w, "git", "commit", "-qam", "new tasks.py")
        sh(self.w, "git", "push", "-q")
        clone_tp = (self.clone / "tasks.py").read_text()
        self.assertNotIn("\nPUSH_FAILURE =", clone_tp)

    def test_runner_on_a_clone_behind_main_does_not_crash(self):
        self.wait_for("2 sessions", lambda: self.sessions() >= 2)
        time.sleep(2)
        log = self.log.read_text()
        self.assertNotIn("Traceback", log)
        self.assertNotIn("AttributeError", log)
        self.assertIsNone(self.proc.poll(), log)  # still running


if __name__ == "__main__":
    unittest.main()
