#!/usr/bin/env python3
"""Tests for a remote that keeps refusing pushes (like GitHub refusing .github/workflows/* from a token
without `workflow` scope): `release`/`hold`/`done` print PUSH FAILED and exit non-zero instead of a
traceback or a silent loop, and the runner stops starting sessions on such a task (state `blocked`),
retries only every --blocked-retry s and gives the task back once pushes work again.
Runs tasks.py and agents.py (with a scripted fake agent) against a local bare remote whose pre-receive
hook refuses commits touching .github/workflows/. Run: python3 tests/test_push_failure.py"""
import json, sys, time, unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.dont_write_bytecode = True
from test_overbudget import Board, Runner, field, sh  # noqa: E402

# like GitHub with a token lacking `workflow` scope: commits that touch .github/workflows/ are refused
WORKFLOW_HOOK = r'''#!/bin/sh
z=0000000000000000000000000000000000000000
while read old new ref; do
  [ "$new" = "$z" ] && continue
  if [ "$old" = "$z" ]; then range="$new --not --all"; else range="$old..$new"; fi
  if git log --name-only --format= $range | grep -q '^\.github/workflows/'; then
    echo "! [remote rejected] refusing to allow an OAuth App to create or update workflow without workflow scope" >&2
    exit 1
  fi
done
'''
REFUSE_ALL_HOOK = "#!/bin/sh\necho 'remote: Permission to the repo denied' >&2\nexit 1\n"

FAKE_AGENT = r'''
import json, os, subprocess, sys
prompt, mode = sys.argv[-1], os.environ.get("FAKE_MODE", "")
def tp(*a):
    p = subprocess.run([sys.executable, "tasks.py", *a], capture_output=True, text=True)
    print(p.stdout + p.stderr, flush=True)
    return p.stdout
def work(tid):
    os.makedirs(".github/workflows", exist_ok=True)
    with open(".github/workflows/ci.yml", "a") as f:
        f.write("# more\n")
    subprocess.run(["git", "add", "-A"], capture_output=True)
    subprocess.run(["git", "commit", "-qm", f"work on {tid}"], capture_output=True)
def result(text):
    print(json.dumps({"type": "result", "subtype": "success", "is_error": False, "result": text}), flush=True)
if "do NOT continue implementing" in prompt:  # over-budget review
    tid = prompt.split("on task `")[1].split("`")[0]
    if "Nearly done" in prompt:
        tp("extend", tid, "--note", "almost there")
    result("reviewed")
    sys.exit(0)
if "You already own task `" in prompt:
    tid = prompt.split("You already own task `")[1].split("`")[0]
else:
    out = tp("claim")
    tid = out.split("CLAIMED ")[1].split()[0] if "CLAIMED " in out else None
if tid:
    work(tid)
    if mode == "release":
        tp("release", tid, "--note", "cannot finish")
    elif mode == "done":
        tp("done", tid, "--force")
result("worked")
'''


def hook(tmp, script):
    h = tmp / "remote.git" / "hooks" / "pre-receive"
    h.write_text(script)
    h.chmod(0o755)
    return h


def exempt(w):
    """Every task is quality-exempt, so `done` gets as far as its push."""
    for f in (w / "tasks").glob("*.md"):
        f.write_text(f.read_text().replace("owner:", "quality: exempt\nquality_reason: test fixture\nowner:", 1))


class TasksPy(Board):
    """tasks.py alone: the agent's view of a refused push."""
    prepare = staticmethod(exempt)
    who = "x/agent-1"

    def setUp(self):
        super().setUp()
        self.tp("claim", "a", who=self.who)
        hook(self.tmp, WORKFLOW_HOOK)

    def commit_workflow(self):
        (self.w / ".github" / "workflows").mkdir(parents=True, exist_ok=True)
        (self.w / ".github" / "workflows" / "ci.yml").write_text("on: push\n")
        sh(self.w, "git", "add", "-A")
        sh(self.w, "git", "commit", "-qm", "ci")
        return sh(self.w, "git", "rev-parse", "HEAD").stdout.strip()

    def failure_file(self):
        rel = sh(self.w, "git", "rev-parse", "--git-path", "tfp-push-failed").stdout.strip()
        return json.loads((self.w / rel).read_text())

    def assert_push_failed(self, p, hint):
        out = p.stdout + p.stderr
        self.assertNotEqual(p.returncode, 0, out)
        self.assertIn("PUSH FAILED: ", out)
        self.assertIn("without workflow scope", out.split("PUSH FAILED: ")[1].splitlines()[0])  # git's error
        self.assertIn(hint, out)
        self.assertNotIn("Traceback", out)

    def assert_unchanged(self, head):
        t = self.remote_task("a")
        self.assertEqual((field(t, "status"), field(t, "owner")), ("in_progress", self.who))
        self.assertEqual(sh(self.w, "git", "rev-parse", "HEAD").stdout.strip(), head)  # the work is still here
        self.assertNotIn("wip/a", sh(self.tmp, "git", "--git-dir=remote.git", "branch").stdout)

    def test_release_with_refused_wip_push(self):
        head = self.commit_workflow()
        p = self.tp("release", "a", "--note", "giving up", who=self.who, check=False)
        self.assert_push_failed(p, "Only a human can fix it")
        self.assert_unchanged(head)
        f = self.failure_file()
        self.assertEqual((f["task"], f["cmd"]), ("a", "release"))
        self.assertIn("workflow scope", f["error"])

    def test_hold_with_refused_wip_push(self):
        head = self.commit_workflow()
        p = self.tp("hold", "a", "needs a human", who=self.who, check=False)
        self.assert_push_failed(p, "Only a human can fix it")
        self.assert_unchanged(head)
        self.assertEqual(field(self.remote_task("a"), "hold"), "")
        self.assertEqual(self.failure_file()["cmd"], "hold")

    def test_done_with_refused_push(self):
        head = self.commit_workflow()
        p = self.tp("done", "a", "--force", who=self.who, check=False)
        self.assert_push_failed(p, "Nothing changed on the board")
        self.assert_unchanged(head)
        self.assertEqual(self.failure_file()["cmd"], "done")

    def test_release_with_refused_board_push(self):
        hook(self.tmp, REFUSE_ALL_HOOK)  # nothing to save to wip/a: the board commit itself is refused
        p = self.tp("release", "a", "--note", "x", who=self.who, check=False)
        out = p.stdout + p.stderr
        self.assertNotEqual(p.returncode, 0, out)
        self.assertIn("PUSH FAILED: ", out)
        self.assertIn("Permission to the repo denied", out)
        self.assertIn("Nothing changed on the board", out)
        self.assertNotIn("Traceback", out)
        self.assertEqual(field(self.remote_task("a"), "status"), "in_progress")

    def test_release_still_works_when_pushes_do(self):
        self.commit_workflow()
        hook(self.tmp, "#!/bin/sh\nexit 0\n")
        p = self.tp("release", "a", "--note", "x", who=self.who, check=False)
        self.assertEqual(p.returncode, 0, p.stdout + p.stderr)
        self.assertIn("RELEASED a", p.stdout)
        self.assertNotIn("PUSH FAILED", p.stdout + p.stderr)


class PushRunner(Runner):
    fake_agent = FAKE_AGENT
    prepare = staticmethod(exempt)
    retry = 1800

    @property
    def extra_args(self):
        return ("--blocked-retry", str(self.retry))

    def before_start(self):
        hook(self.tmp, WORKFLOW_HOOK)

    def sessions(self):
        return self.log.read_text().count("session start")

    def status(self):
        return json.loads((self.clone.parent / "logs" / "agent-1.status.json").read_text())

    def assert_blocked_without_new_sessions(self, n):
        self.wait_for("blocked status", lambda: self.status()["state"] == "blocked")
        s = self.status()
        self.assertEqual(s["task"], "a")
        self.assertIn("workflow scope", s["error"])
        time.sleep(8)  # several idle rounds of the main loop
        self.assertEqual(self.sessions(), n, self.log.read_text())
        self.assertEqual(self.status()["state"], "blocked")
        a = self.state("a")
        self.assertEqual((a["status"], a["owner"]), ("in_progress", "box/agent-1"))


class ReleaseRefused(PushRunner):
    mode, budget = "release", 10

    def test_failed_release_in_a_session_blocks_at_once(self):
        self.wait_for("BLOCKED log", lambda: "BLOCKED on a" in self.log.read_text())
        self.assert_blocked_without_new_sessions(1)
        self.assertNotIn("released a", self.log.read_text())


class ReviewReleaseRefused(PushRunner):
    mode, budget = "", 1

    def test_force_release_after_review_logs_failed_and_blocks(self):
        # claim, review (extend), work, review (nothing decided) -> force_release fails
        self.wait_for("release FAILED log", lambda: "release FAILED for a" in self.log.read_text(), timeout=120)
        log = self.log.read_text()
        self.assertNotIn("released a for another agent", log)
        self.assertIn("BLOCKED on a", log)
        self.assert_blocked_without_new_sessions(4)


class DoneRefused(PushRunner):
    mode, budget = "done", 10

    def test_same_done_push_error_three_sessions_in_a_row_blocks(self):
        self.wait_for("BLOCKED log", lambda: "BLOCKED on a" in self.log.read_text(), timeout=120)
        log = self.log.read_text()
        for n in (1, 2, 3):
            self.assertIn(f"({n}/3 sessions in a row)", log)
        self.assert_blocked_without_new_sessions(3)


class RetryAfterFix(PushRunner):
    mode, budget, retry = "release", 10, 5

    def test_retries_then_hands_task_back_once_pushes_work(self):
        h = self.tmp / "remote.git" / "hooks" / "pre-receive"
        self.wait_for("BLOCKED log", lambda: "BLOCKED on a" in self.log.read_text())
        self.wait_for("a retry", lambda: "retrying to release a" in self.log.read_text(), timeout=30)
        self.assertEqual(self.sessions(), 1)
        h.unlink()  # a human fixed the token
        self.wait_for("a handed back", lambda: "pushes had failed" in self.state("a")["text"], timeout=60)
        log = self.log.read_text()
        self.assertIn("released a for another agent", log.split("retrying to release a")[-1])
        self.assertIn("handed back by box/agent-1", self.state("a")["text"])
        self.assertEqual(self.state("a")["hold"], "")  # (the runner may claim it again now: pushes work)
        wip = sh(self.tmp, "git", "--git-dir=remote.git", "ls-tree", "-r", "--name-only", "wip/a").stdout
        self.assertIn(".github/workflows/ci.yml", wip)


if __name__ == "__main__":
    unittest.main()
