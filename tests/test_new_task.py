#!/usr/bin/env python3
"""Tests for `tasks.py new` while working on another task: the new task file reaches main at once in its own
commit (the creator's work stays on wip/<id>), another clone can claim it right away, the creator's clone has
it once and replays its work on top, a lost push race is retried, a refused push creates nothing, and without
a remote it is a local commit. Runs tasks.py against a local bare remote. Run: python3 tests/test_new_task.py"""
import os, shutil, sys, unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.dont_write_bytecode = True
from test_overbudget import Board, field, sh  # noqa: E402

from test_push_race import race_hook, races, refusing_hook  # noqa: E402

BODY = "## Goal\nFix the clock.\n\n## Acceptance criteria\n- [ ] the spec passes on any date\n\n## Notes\n"


class NewTask(Board):
    def setUp(self):
        super().setUp()
        self.claim_and_work()

    def claim_and_work(self):
        """agent-1 claims `a`, commits work, and the runner backs it up to wip/a."""
        self.assertIn("CLAIMED", self.tp("claim", "a", who="agent-1").stdout)
        (self.w / "work-a.txt").write_text("unfinished\n")
        sh(self.w, "git", "add", "work-a.txt")
        sh(self.w, "git", "commit", "-qm", "work on a")
        sh(self.w, "git", "push", "-q", "-f", "origin", "HEAD:refs/heads/wip/a")
        (self.w / ".tmp").mkdir(exist_ok=True)
        (self.w / ".tmp" / "c.md").write_text(BODY)

    def remote_files(self, ref="main"):
        return sh(self.tmp, "git", "--git-dir=remote.git", "ls-tree", "-r", "--name-only", ref).stdout.split()

    def other_clone(self, name="agent-2"):
        sh(self.tmp, "git", "clone", "-q", "remote.git", name)
        c = self.tmp / name
        self.config(c, name)
        return c

    def tp_in(self, clone, *a, who, check=True):
        env = {k: v for k, v in os.environ.items() if k not in ("TASK_OWNER", "TASK_AVOID", "TASK_LEASE")
               and not k.startswith("TASKS_")}
        env.update(TASKS_ROOT=str(clone), TASK_OWNER=who)
        return sh(clone, sys.executable, "tasks.py", *a, env=env, check=check)

    def test_new_task_reaches_main_alone_while_work_stays_on_wip(self):
        out = self.tp("new", "c", "Fix clock", "--deps", "b", "--touches", "web", "--body", ".tmp/c.md",
                      who="agent-1").stdout
        self.assertIn("created tasks/c.md and pushed it", out)
        files = self.remote_files()
        self.assertIn("tasks/c.md", files)
        self.assertNotIn("work-a.txt", files)  # the creator's unfinished work did not reach main
        self.assertIn("work-a.txt", self.remote_files("wip/a"))
        log = sh(self.tmp, "git", "--git-dir=remote.git", "log", "-1", "--name-only", "--format=%s", "main").stdout
        self.assertEqual(log.split(), ["new:", "c", "by", "agent-1", "tasks/c.md"])  # its own commit, only the file
        text = self.remote_task("c")
        self.assertEqual(field(text, "status"), "todo")
        self.assertEqual(field(text, "deps"), "[b]")
        self.assertEqual(field(text, "touches"), "[web]")
        self.assertIn("the spec passes on any date", text)
        # the creator's clone: the file once, committed on main, its work replayed on top, nothing to merge
        self.assertEqual(sh(self.w, "git", "status", "--porcelain", "--untracked-files=no").stdout, "")
        self.assertEqual(sh(self.w, "git", "log", "--format=%s", "-2").stdout.split("\n")[:2],
                         ["work on a", "new: c by agent-1"])
        self.assertEqual(sh(self.w, "git", "rev-list", "--count", "HEAD", "--", "tasks/c.md").stdout.strip(), "1")
        self.assertEqual(sh(self.w, "git", "rev-list", "--count", "HEAD..origin/main").stdout.strip(), "0")
        self.assertTrue((self.w / "work-a.txt").exists())

    def test_another_clone_can_claim_it_right_away(self):
        self.tp("new", "c", "Fix clock", "--body", ".tmp/c.md", who="agent-1")
        other = self.other_clone()
        out = self.tp_in(other, "claim", "c", who="agent-2").stdout
        self.assertIn("CLAIMED c as agent-2", out)
        self.assertEqual(field(self.remote_task("c"), "owner"), "agent-2")
        # and the creator still pulls cleanly afterwards
        sh(self.w, "git", "pull", "-q", "--rebase")
        self.assertEqual(field((self.w / "tasks" / "c.md").read_text(), "owner"), "agent-2")

    def test_push_race_with_another_agent_is_retried(self):
        race_hook(self.tmp, "new: c", 2)
        out = self.tp("new", "c", "Fix clock", "--body", ".tmp/c.md", who="agent-1").stdout
        self.assertIn("pushed it", out)
        self.assertEqual(races(self.tmp), 2)
        self.assertIn("tasks/c.md", self.remote_files())
        self.assertEqual(sh(self.w, "git", "log", "--format=%s", "-1").stdout.strip(), "work on a")

    def test_same_id_created_by_another_agent_first(self):
        other = self.other_clone()
        self.tp_in(other, "new", "c", "Their clock", who="agent-2")
        p = self.tp("new", "c", "Fix clock", "--body", ".tmp/c.md", who="agent-1", check=False)
        self.assertNotEqual(p.returncode, 0)
        self.assertIn("exists", p.stderr)
        self.assertEqual(field(self.remote_task("c"), "title"), "Their clock")

    def test_refused_push_creates_nothing_and_says_how_to_retry(self):
        refusing_hook(self.tmp, "refs/heads/main")
        head = sh(self.w, "git", "rev-parse", "HEAD").stdout
        p = self.tp("new", "c", "Fix clock", "--body", ".tmp/c.md", who="agent-1", check=False)
        self.assertNotEqual(p.returncode, 0)
        self.assertIn("PUSH FAILED", p.stderr)
        self.assertIn("NOT created", p.stderr)
        self.assertIn("python tasks.py new c 'Fix clock' --body .tmp/c.md", p.stderr)
        self.assertFalse((self.w / "tasks" / "c.md").exists())
        self.assertNotIn("tasks/c.md", self.remote_files())
        self.assertEqual(sh(self.w, "git", "rev-parse", "HEAD").stdout, head)
        self.assertEqual(sh(self.w, "git", "status", "--porcelain").stdout.count("tasks/"), 0)

    def test_offline_creates_nothing(self):
        sh(self.w, "git", "remote", "set-url", "origin", str(self.tmp / "gone.git"))
        p = self.tp("new", "c", "Fix clock", "--body", ".tmp/c.md", who="agent-1", check=False)
        self.assertNotEqual(p.returncode, 0)
        self.assertIn("NOT created", p.stderr)
        self.assertIn("Retry", p.stderr)
        self.assertFalse((self.w / "tasks" / "c.md").exists())

    def test_missing_body_file(self):
        p = self.tp("new", "c", "Fix clock", "--body", ".tmp/nope.md", who="agent-1", check=False)
        self.assertIn("--body: cannot read", p.stderr)
        self.assertNotIn("tasks/c.md", self.remote_files())


class NoRemote(unittest.TestCase):
    def test_without_remote_it_is_a_local_commit(self):
        import tempfile
        tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, tmp, True)
        sh(tmp, "git", "init", "-q", "-b", "main", "w")
        w = tmp / "w"
        sh(w, "git", "config", "user.email", "h@t")
        sh(w, "git", "config", "user.name", "human")
        shutil.copy(Path(__file__).resolve().parent.parent / "tasks.py", w / "tasks.py")
        env = {k: v for k, v in os.environ.items() if not k.startswith("TASKS_") and k != "TASK_OWNER"}
        env["TASKS_ROOT"] = str(w)
        out = sh(w, sys.executable, "tasks.py", "new", "x", "Something", env=env).stdout
        self.assertIn("created tasks/x.md", out)
        self.assertEqual(sh(w, "git", "log", "--name-only", "--format=%s").stdout.split(),
                         ["new:", "x", "by", "human", "tasks/x.md"])


if __name__ == "__main__":
    unittest.main()
