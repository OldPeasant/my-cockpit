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

from test_push_race import counted, gh006_hook, protected_race_hook, race_hook, races, refusing_hook  # noqa: E402

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

    def test_protected_branch_race_gh006_is_retried(self):
        """GitHub answers a lost race on a protected branch with GH006 instead of `fetch first`: still a race."""
        protected_race_hook(self.tmp, "new: c", 2)
        out = self.tp("new", "c", "Fix clock", "--body", ".tmp/c.md", who="agent-1").stdout
        self.assertIn("pushed it", out)
        self.assertEqual(races(self.tmp), 2)
        self.assertIn("tasks/c.md", self.remote_files())
        self.assertEqual(sh(self.w, "git", "rev-list", "--count", "HEAD", "--", "tasks/c.md").stdout.strip(), "1")
        self.assertEqual(sh(self.w, "git", "log", "--format=%s", "-1").stdout.strip(), "work on a")

    def test_transient_gh006_without_race_is_retried(self):
        gh006_hook(self.tmp, 1)
        out = self.tp("new", "c", "Fix clock", "--body", ".tmp/c.md", who="agent-1").stdout
        self.assertIn("pushed it", out)
        self.assertEqual(counted(self.tmp, "refusals"), 1)
        self.assertIn("tasks/c.md", self.remote_files())

    def test_losing_every_race_creates_nothing(self):
        race_hook(self.tmp, "new: c", 99)
        p =self.tp("new", "c", "Fix clock", "--body", ".tmp/c.md", who="agent-1", check=False)
        self.assertNotEqual(p.returncode, 0)
        self.assertIn("PUSH FAILED", p.stderr)
        self.assertIn("lost the push race 5 times", p.stderr)  # agents.py retries on RACE_HINT
        self.assertIn("NOT created", p.stderr)
        self.assertEqual(races(self.tmp), 5)
        self.assertNotIn("tasks/c.md", self.remote_files())
        self.assertFalse((self.w / "tasks" / "c.md").exists())
        # the clone caught up with the other agents' pushes, but kept its own work and gained no task commit
        self.assertEqual(sh(self.w, "git", "log", "--format=%s", "-1").stdout.strip(), "work on a")
        self.assertEqual(sh(self.w, "git", "rev-list", "--count", "HEAD", "--", "tasks/c.md").stdout.strip(), "0")
        self.assertEqual(sh(self.w, "git", "status", "--porcelain").stdout.count("tasks/"), 0)

    def test_creators_uncommitted_and_staged_work_survives(self):
        # main moved meanwhile, so `new` has to catch up (rebase) before publishing
        other = self.other_clone()
        (other / "other.txt").write_text("from agent-2\n")
        sh(other, "git", "add", "other.txt")
        sh(other, "git", "commit", "-qm", "other work")
        sh(other, "git", "push", "-q", "origin", "main")
        (self.w / "work-a.txt").write_text("unfinished\nmore, not committed\n")
        (self.w / "staged.txt").write_text("staged, not committed\n")
        sh(self.w, "git", "add", "staged.txt")
        (self.w / "scratch.txt").write_text("untracked\n")
        out = self.tp("new", "c", "Fix clock", "--body", ".tmp/c.md", who="agent-1").stdout
        self.assertIn("pushed it", out)
        files = self.remote_files()
        self.assertIn("tasks/c.md", files)
        for f in ("staged.txt", "scratch.txt", "work-a.txt"):
            self.assertNotIn(f, files)
        self.assertEqual((self.w / "work-a.txt").read_text(), "unfinished\nmore, not committed\n")
        self.assertEqual((self.w / "staged.txt").read_text(), "staged, not committed\n")
        self.assertEqual((self.w / "scratch.txt").read_text(), "untracked\n")
        self.assertTrue((self.w / "other.txt").exists())
        self.assertEqual(sh(self.w, "git", "log", "--format=%s", "-3").stdout.split("\n")[:3],
                         ["work on a", "new: c by agent-1", "other work"])
        status = sh(self.w, "git", "status", "--porcelain").stdout
        self.assertNotIn("tasks/c.md", status)
        self.assertIn("A  staged.txt", status.splitlines())  # still staged
        self.assertIn(" M work-a.txt", status.splitlines())

    def test_quality_exempt_goes_through_the_published_path(self):
        out = self.tp("new", "c", "Plan the clock", "--quality-exempt", "planning only", "--body", ".tmp/c.md",
                      who="agent-1").stdout
        self.assertIn("pushed it", out)
        text = self.remote_task("c")
        self.assertEqual(field(text, "quality"), "exempt")
        self.assertEqual(field(text, "quality_reason"), "planning only")
        self.assertIn("the spec passes on any date", text)

    def test_empty_quality_exempt_reason_creates_nothing(self):
        p = self.tp("new", "c", "Plan", "--quality-exempt", "  ", who="agent-1", check=False)
        self.assertNotEqual(p.returncode, 0)
        self.assertIn("needs a reason", p.stderr)
        self.assertNotIn("tasks/c.md", self.remote_files())
        self.assertFalse((self.w / "tasks" / "c.md").exists())

    def test_without_body_the_skeleton_is_published(self):
        self.tp("new", "c", "Fix clock", "--prio", "1", who="agent-1")
        text = self.remote_task("c")
        self.assertEqual(field(text, "prio"), "1")
        self.assertIn("## Goal", text)
        self.assertIn("## Acceptance criteria", text)

    def test_existing_local_file_is_refused_without_push(self):
        (self.w / "tasks" / "c.md").write_text("hand-written\n")
        before = sh(self.tmp, "git", "--git-dir=remote.git", "rev-parse", "main").stdout
        p = self.tp("new", "c", "Fix clock", who="agent-1", check=False)
        self.assertNotEqual(p.returncode, 0)
        self.assertIn("exists", p.stderr)
        self.assertEqual((self.w / "tasks" / "c.md").read_text(), "hand-written\n")
        self.assertEqual(sh(self.tmp, "git", "--git-dir=remote.git", "rev-parse", "main").stdout, before)

    def test_creator_finishes_its_task_without_conflict_on_the_new_file(self):
        """Criterion 2: the creator's later `done`-style push of its work carries no second copy of the file."""
        self.tp("new", "c", "Fix clock", "--body", ".tmp/c.md", who="agent-1")
        sh(self.w, "git", "pull", "-q", "--rebase")
        sh(self.w, "git", "push", "-q", "origin", "HEAD:main")
        self.assertIn("work-a.txt", self.remote_files())
        self.assertEqual(sh(self.tmp, "git", "--git-dir=remote.git", "rev-list", "--count", "main", "--",
                            "tasks/c.md").stdout.strip(), "1")


class NoRemote(unittest.TestCase):
    def setUp(self):
        import tempfile
        tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, tmp, True)
        sh(tmp, "git", "init", "-q", "-b", "main", "w")
        self.w = w = tmp / "w"
        sh(w, "git", "config", "user.email", "h@t")
        sh(w, "git", "config", "user.name", "human")
        shutil.copy(Path(__file__).resolve().parent.parent / "tasks.py", w / "tasks.py")
        self.env = {k: v for k, v in os.environ.items() if not k.startswith("TASKS_") and k != "TASK_OWNER"}
        self.env["TASKS_ROOT"] = str(w)

    def test_without_remote_it_is_a_local_commit(self):
        w = self.w
        out = sh(w, sys.executable, "tasks.py", "new", "x", "Something", env=self.env).stdout
        self.assertIn("created tasks/x.md", out)
        self.assertEqual(sh(w, "git", "log", "--name-only", "--format=%s").stdout.split(),
                         ["new:", "x", "by", "human", "tasks/x.md"])

    def test_without_remote_other_staged_changes_stay_out_of_the_commit(self):
        w = self.w
        (w / "staged.txt").write_text("staged\n")
        sh(w, "git", "add", "staged.txt")
        sh(w, sys.executable, "tasks.py", "new", "x", "Something", env=self.env)
        self.assertEqual(sh(w, "git", "show", "--name-only", "--format=", "HEAD").stdout.split(), ["tasks/x.md"])
        self.assertIn("A  staged.txt", sh(w, "git", "status", "--porcelain").stdout.splitlines())


if __name__ == "__main__":
    unittest.main()
