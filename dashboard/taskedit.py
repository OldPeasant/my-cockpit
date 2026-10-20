#!/usr/bin/env python3
"""Create or edit a task file from the dashboard: python dashboard/taskedit.py <id> [--create] < task.md

The new text comes on stdin. Fields that belong to the protocol (status, owner, lease, attempts, hold, ...)
are never taken from it: a new task starts as todo without an owner, an edited one keeps the values it has
on main. Like tasks.py, it commits only the task file on top of origin/main and pushes it, so unfinished
local commits never reach main; a lost push race is retried on the fresh state.

Only tasks nobody works on can be edited (todo, on hold, dropped): an agent reads its task file once, when
it claims it. To change a running task, put it on hold first (README "Changing the plan").
"""
import argparse, os, re, sys
from pathlib import Path

PROTOCOL = ("status", "owner", "lease_until", "attempts", "releases", "hold", "extended", "parent", "subtasks")


def split_front(text):
    m = re.match(r"---\n(.*?)\n---\n?(.*)", text.replace("\r\n", "\n"), re.S)
    if not m:
        sys.exit("REFUSED: the text must start with a front matter block (--- ... ---)")
    fields = []
    for line in m.group(1).splitlines():
        if ":" in line:
            k, v = line.split(":", 1)
            fields.append((k.strip(), v.strip()))
    return fields, m.group(2)


def compose(fields, body):
    return "---\n" + "".join(f"{k}: {v}\n" if v else f"{k}:\n" for k, v in fields) + "---\n" + body.lstrip("\n")


def main():
    p = argparse.ArgumentParser()
    p.add_argument("id")
    p.add_argument("--create", action="store_true")
    a = p.parse_args()
    root = Path(os.environ.get("TASKS_ROOT", ".")).resolve()
    sys.path.insert(0, str(root))
    sys.dont_write_bytecode = True
    import tasks as T

    if not re.match(r"^[a-z0-9][a-z0-9-]*$", a.id):
        sys.exit(f"REFUSED: '{a.id}' is not a valid id (a-z, 0-9, -)")
    wanted, body = split_front(sys.stdin.read())
    if not dict(wanted).get("title", "").strip():
        sys.exit("REFUSED: the task needs a title")
    T.no_unfinished_merge()
    path = T.DIR / f"{a.id}.md"
    for _ in range(5):
        T.sync()
        ts = T.load()
        cur = ts.get(a.id)
        if a.create and cur:
            sys.exit(f"REFUSED: a task '{a.id}' exists already: pick another id")
        if not a.create and not cur:
            sys.exit(f"REFUSED: no such task: {a.id}")
        if cur and cur["status"] == "in_progress":
            sys.exit(f"REFUSED: {a.id} is in progress (owner: {cur['owner']}). The agent read the task when it "
                     f"claimed it and won't see changes: put it on hold first, edit it, then take it off hold.")
        if cur and cur["status"] == "done":
            sys.exit(f"REFUSED: {a.id} is done: its code is on main. Add a new task that changes it.")
        if a.create:
            keep = {"status": "todo", "owner": ""}
        else:
            old, _ = split_front(path.read_text(encoding="utf-8"))
            keep = {k: v for k, v in old if k in PROTOCOL}
        fields = [(k, v) for k, v in wanted if k not in PROTOCOL]
        fields[1:1] = [(k, v) for k, v in keep.items()]  # right after the title, as tasks.py writes them
        if not any(k == "deps" for k, _ in fields):
            fields.append(("deps", "[]"))
        text = compose(fields, body)
        after = T.parse_text(text, a.id, path)
        before = set(T.problems(ts))
        new = [x for x in T.problems({**ts, a.id: after}) if x not in before]
        if new:
            sys.exit("REFUSED: the board would have problems:\n  " + "\n  ".join(new))
        if cur and path.read_text(encoding="utf-8") == text:
            return print(f"no changes to {a.id}")
        T.write(path, text)
        msg = f"{'new' if a.create else 'edit'} task: {a.id} by {T.me()}"
        if not T.upstream():
            T.git("add", "--", str(path))
            T.git("commit", "-qm", msg, "--", str(path))
            return print(f"{'CREATED' if a.create else 'SAVED'} {a.id} (committed locally: this branch has no upstream)")
        if T.publish([str(path)], msg):
            return print(f"{'CREATED' if a.create else 'SAVED'} {a.id}: pushed to {T.upstream()}")
        print("someone pushed in between: trying again on the latest main")
    sys.exit("could not push after 5 attempts (network/auth problem?)")


if __name__ == "__main__":
    main()
