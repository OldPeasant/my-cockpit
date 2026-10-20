"""board.py - HTML dashboard for the task board. Stdlib only. Used by `tasks.py watch` / `tasks.py graph`.

Everything comes from git, so any clone on any machine shows the same picture:
  tasks       tasks/*.md at a ref (origin/main for watch)
  history     claim/done/release/redo/heartbeat commits on that ref -> durations, liveness, activity
  agents      refs/heads/status/<host>/<agent>: one force-pushed commit holding status.json, written by
              agents.py every few seconds (what each agent is doing right now); never touches main
  repos       refs/heads/sync-status: sync.json from `tasks.py repos sync` (when each original repo was
              last updated, and any problem)
"""
import html, json, re, time, urllib.parse

OFFLINE_AFTER = 180  # s without a status push before an agent counts as offline
EVENT = re.compile(r"^(claim|done|release|heartbeat|redo|hold|unhold|split|extend|drop|undrop|assume|confirm|reject|freeze|unfreeze): (\S+)(?: attempt (\d+))?(?: by (\S+))?"
                   r"(?:.*\(takeover from (\S+)\))?")  # e.g. "redo: auth attempt 2 by lapA/agent-1"


# ---------- data ----------
def tasks_at(T, ref):
    ts = {}
    for name in T.git("ls-tree", "--name-only", f"{ref}:tasks", check=False).stdout.split():
        if name.endswith(".md"):
            t = T.parse_text(T.git("show", f"{ref}:tasks/{name}").stdout, name[:-3])
            ts[t["id"]] = t
    return T.analyze(ts)


def history(T, ref, ts):
    """Adds claimed_at, done_at, seen_at (last claim/heartbeat), takeovers, releases to each task.
    Returns the activity feed (newest first, heartbeats left out)."""
    # --first-parent: main's own line only, not the histories imported from other repos
    out = T.git("log", ref, "--first-parent", "-n4000", "--format=%ct%x09%an%x09%s", check=False).stdout.splitlines()
    for t in ts.values():
        t.update(claimed_at=None, done_at=None, seen_at=None, takeovers=0, releases=0)
    feed = []
    for line in reversed(out):  # oldest first
        try:
            at, author, subj = line.split("\t", 2)
        except ValueError:
            continue
        at = int(at)
        m = EVENT.match(subj)
        if not m:
            feed.append(dict(at=at, kind="commit", who=author, task=None, text=subj))
            continue
        kind, tid, attempt, who, prev = m.groups()
        t = ts.get(tid)
        if kind in ("freeze", "unfreeze"):
            feed.append(dict(at=at, kind=kind, who=who or author, task=None, text=subj))
            continue
        if t is None:
            continue
        who = who or author
        if kind in ("claim", "heartbeat"):
            t["seen_at"] = at
        if kind == "claim":
            t.update(claimed_at=at, done_at=None)
            t["takeovers"] += bool(prev)
        elif kind == "done":
            t["done_at"] = at
        elif kind == "release":
            t["releases"] += 1
        if kind != "heartbeat":
            feed.append(dict(at=at, kind="takeover" if prev else kind, who=who, task=tid, prev=prev, text=subj,
                             attempt=attempt))
    return feed[::-1]


def agents(T):
    r = T.remote() or "origin"
    out = []
    for ref in T.git("for-each-ref", "--format=%(refname)", f"refs/remotes/{r}/status/", check=False).stdout.split():
        try:
            out.append(json.loads(T.git("show", f"{ref}:status.json").stdout))
        except ValueError:
            pass
    return out


def sync_state(T):
    r = T.remote() or "origin"
    p = T.git("show", f"refs/remotes/{r}/sync-status:sync.json", check=False)
    try:
        return json.loads(p.stdout) if p.returncode == 0 else None
    except ValueError:
        return None


def repos_panel(sync, now):
    if not sync or not sync.get("repos"):
        return ""
    pill = {"pushed": "ok", "up to date": "ok", "check failed": "warn", "conflict": "bad", "error": "bad"}
    rows = []
    for p, s in sorted(sync["repos"].items()):
        url = re.sub(r"^.*[:/]([^/:]+/[^/]+?)(\.git)?$", r"\1", s.get("url", ""))
        pushed = (f'updated <b>{ago(s["pushed_at"], now)}</b> <span class="sub">@ {e(s.get("commit", "")[:9])}</span>'
                  if s.get("pushed_at") else '<span class="sub">never updated</span>')
        rows.append(f'<tr><td><b>{e(p)}/</b></td><td class="sub">{e(url)} ({e(s.get("branch", ""))})</td><td>{pushed}</td>'
                    f'<td><span class="pill p-{pill.get(s.get("result"), "nostatus")}">{e(s.get("result", "?"))}</span></td>'
                    f'<td class="sub">{e(s.get("message", ""))}</td></tr>')
    bad = sync.get("result") in ("check failed", "problem")
    return (f'<h2>Original repos</h2><div class="panel" style="overflow-x:auto"><table>'
            f'<tr><th>Folder</th><th>Repo</th><th>Last update</th><th>Status</th><th></th></tr>{"".join(rows)}</table></div>'
            f'<div class="sub" style="margin-top:6px{";color:var(--bad)" if bad else ""}">last sync run {ago(sync.get("run_at"), now)}: '
            f'{e(sync.get("result"))}{" · " + e(sync["message"]) if sync.get("message") else ""}</div>')


def attempt_branches(T):
    """{task id: [(n, "wip/<id>-attemptN"), ...]}: abandoned attempts saved by `tasks.py redo`."""
    r = T.remote() or "origin"
    out = {}
    for ref in T.git("for-each-ref", "--format=%(refname:short)", f"refs/remotes/{r}/wip/", check=False).stdout.split():
        m = re.match(rf"{re.escape(r)}/(wip/(.+)-attempt(\d+))$", ref)
        if m:
            out.setdefault(m.group(2), []).append((int(m.group(3)), m.group(1)))
    return {k: sorted(v) for k, v in out.items()}


def compare_url(T, base, branch):
    """Web link showing what `branch` changed relative to `base`, for GitLab and GitHub remotes."""
    url = T.git("remote", "get-url", T.remote() or "origin", check=False).stdout.strip()
    m = re.match(r"^(?:(?:https?|ssh)://)?(?:[^@/]+@)?([^/:]+)(?::\d+)?[:/](.+?)(?:\.git)?/?$", url)
    if not m or url.startswith(("/", ".", "file:")) or re.match(r"^[A-Za-z]:[\\/]", url):
        return None
    host, path = m.groups()
    q = lambda s: urllib.parse.quote(s, safe="/")
    if host == "github.com":
        return f"https://{host}/{path}/compare/{q(base)}...{q(branch)}"
    if "gitlab" in host:
        return f"https://{host}/{path}/-/compare/{q(base)}...{q(branch)}"
    return None


def critical_path(ts):
    """Length of the longest chain of unfinished tasks (a lower bound on remaining 'rounds')."""
    memo = {}

    def depth(i, stack=()):
        if i in stack:
            return 0
        if i not in memo:
            memo[i] = 1 + max([depth(d, stack + (i,)) for d in ts[i]["deps"]
                               if d in ts and ts[d]["status"] not in ("done", "dropped")], default=0)
        return memo[i]
    return max([depth(i) for i, t in ts.items() if t["status"] not in ("done", "dropped")], default=0)


# ---------- formatting ----------
def e(s):
    return html.escape(str(s if s is not None else ""))


def ago(at, now):
    if not at:
        return "-"
    s = max(0, int(now - at))
    return f"{s}s ago" if s < 60 else f"{s // 60} min ago" if s < 3600 else f"{s // 3600} h {s % 3600 // 60} min ago"


def dur(s):
    s = int(s)
    return f"{s}s" if s < 60 else f"{s // 60} min" if s < 3600 else f"{s // 3600} h {s % 3600 // 60:02d} min"


def clip(s, n):
    return s if len(s) <= n else s[:n - 1] + "\u2026"


def stamp(at):
    return time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(at)) if at else ""


def health(T, t, now):
    """ok / warn / bad for an in_progress task: how much of its current lease has already passed."""
    if t["status"] != "in_progress":
        return ""
    end = T.lease_end(t)
    if t["stale"]:
        return "bad"
    if end is None or not t["seen_at"] or end <= t["seen_at"]:
        return "ok"  # no lease (human claim)
    used = (now - t["seen_at"]) / (end - t["seen_at"])
    return "ok" if used < 0.4 else "warn" if used < 0.8 else "bad"


def state_of(t):
    return ("dropped" if t["status"] == "dropped" else "hold" if t.get("hold") or t.get("orphaned")
            else "stale" if t["stale"] else t["status"])


# ---------- page ----------
CSS = """
:root{--bg:#f6f7f9;--panel:#fff;--ink:#1f2328;--muted:#656d76;--line:#d8dee4;--todo:#eaeef2;--prog:#fff1b8;
--done:#d3f5dc;--stale:#ffd8d3;--ok:#1a7f37;--warn:#9a6700;--bad:#cf222e;--edge:#8c959f;--mono:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace}
@media (prefers-color-scheme:dark){:root{--bg:#0d1117;--panel:#161b22;--ink:#e6edf3;--muted:#8d96a0;--line:#30363d;
--todo:#262c36;--prog:#4d3d0f;--done:#16402a;--stale:#5a1e1e;--ok:#3fb950;--warn:#d29922;--bad:#f85149;--edge:#6e7681}}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--ink);font:14px/1.45 system-ui,-apple-system,Segoe UI,sans-serif}
main{max-width:1400px;margin:0 auto;padding:20px 16px 40px}h1{font-size:20px;margin:0}h2{font-size:15px;margin:28px 0 10px}
.sub{color:var(--muted);font-size:12.5px}.panel{background:var(--panel);border:1px solid var(--line);border-radius:10px}
.bar{display:flex;height:10px;border-radius:5px;overflow:hidden;background:var(--todo);margin:14px 0 8px}
.bar i{display:block}.b-done{background:var(--ok)}.b-prog{background:var(--warn)}.b-stale{background:var(--bad)}
.stats{display:flex;flex-wrap:wrap;gap:6px 18px;font-size:13px}.stats b{font-variant-numeric:tabular-nums}
.agents{display:grid;grid-template-columns:repeat(auto-fill,minmax(300px,1fr));gap:12px}
.agent{padding:12px 14px}.agent .top{display:flex;justify-content:space-between;align-items:center;gap:8px}
.who{font-weight:600}.who span{color:var(--muted);font-weight:400}.task{margin-top:6px}
.doing{margin-top:8px;font:12.5px var(--mono);background:var(--bg);border-radius:6px;padding:6px 8px;
overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.meta{margin-top:6px;color:var(--muted);font-size:12.5px}
.pill{font-size:11.5px;padding:1px 8px;border-radius:10px;border:1px solid currentColor;white-space:nowrap}
.p-working,.p-ok{color:var(--ok)}.p-waiting,.p-stopped,.p-finished,.p-nostatus{color:var(--muted)}.p-backoff,.p-limited,.p-paused,.p-warn{color:var(--warn)}
.p-offline,.p-bad,.p-blocked{color:var(--bad)}.dot{display:inline-block;width:8px;height:8px;border-radius:50%;margin-right:5px}
.d-ok{background:var(--ok)}.d-warn{background:var(--warn)}.d-bad{background:var(--bad)}
.graph{overflow-x:auto;padding:8px}svg text{fill:var(--ink);font:12px system-ui,sans-serif}svg .m{fill:var(--muted)}
svg .todo{fill:var(--todo)}svg .in_progress{fill:var(--prog)}svg .done{fill:var(--done)}svg .stale,svg .hold{fill:var(--stale)}svg .dropped{fill:var(--bg);stroke-dasharray:4 3}
svg rect{stroke:var(--edge)}svg rect.ready{stroke:var(--ink)}svg rect.critical{stroke:var(--bad)}svg path{stroke:var(--edge);fill:none}
svg .h-ok{stroke:var(--ok)}svg .h-warn{stroke:var(--warn)}svg .h-bad{stroke:var(--bad)}
table{width:100%;border-collapse:collapse;font-size:13px}th,td{text-align:left;padding:7px 10px;border-bottom:1px solid var(--line);vertical-align:top}
th{color:var(--muted);font-weight:500;font-size:12px}tr:last-child td{border-bottom:0}.num{font-variant-numeric:tabular-nums;white-space:nowrap}
.st{display:inline-block;padding:0 7px;border-radius:4px;font-size:12px}.s-todo{background:var(--todo)}.s-in_progress{background:var(--prog)}
.s-done{background:var(--done)}.s-stale,.s-hold{background:var(--stale)}.s-dropped{color:var(--muted);text-decoration:line-through}
.frozen{border-color:var(--warn);padding:10px 14px;margin-top:14px}.frozen code{font:12px var(--mono)}
.assume li{list-style:none;padding:8px 14px;border-bottom:1px solid var(--line)}.assume ul{margin:0;padding:0}
.assume li:last-child{border-bottom:0}.assume summary{cursor:pointer;padding:10px 14px}
.human{border-color:var(--bad)}.human li{list-style:none;padding:8px 14px;border-bottom:1px solid var(--line)}
.human ul{margin:0;padding:0}.human li:last-child{border-bottom:0}.human code{font:12px var(--mono)}.dep-done{color:var(--muted);text-decoration:line-through}
.cols{display:grid;grid-template-columns:minmax(0,3fr) minmax(0,2fr);gap:16px}@media(max-width:900px){.cols{grid-template-columns:1fr}}
.feed{list-style:none;margin:0;padding:4px 0}.feed li{padding:6px 12px;border-bottom:1px solid var(--line);font-size:13px}
.feed li:last-child{border-bottom:0}.feed .t{color:var(--muted);font-size:12px;float:right;margin-left:8px}
.k{font-weight:600}.k-done{color:var(--ok)}.k-takeover,.k-release,.k-redo{color:var(--bad)}.k-claim,.k-freeze,.k-assume{color:var(--warn)}.k-drop{color:var(--muted)}.k-commit{color:var(--muted);font-weight:400}
.empty{color:var(--muted);padding:12px 14px}a{color:inherit}.dep-tip{text-decoration:underline dotted;cursor:help}
"""


def svg(ts, now, T):
    W, H, GX, GY = 196, 54, 48, 14
    cols = {}
    for t in ts.values():
        cols.setdefault(t["level"], []).append(t)
    order = {"in_progress": 0, "todo": 1, "done": 2, "dropped": 3}
    pos = {}
    for lv, items in cols.items():
        items.sort(key=lambda t: (order.get(t["status"], 3), -t["dependents"], t["id"]))
        for r, t in enumerate(items):
            pos[t["id"]] = (10 + lv * (W + GX), 10 + r * (H + GY))
    width = 20 + (max(cols, default=0) + 1) * (W + GX) - GX
    height = 20 + max((len(v) for v in cols.values()), default=1) * (H + GY) - GY
    s = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}">',
         '<defs><marker id="a" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="6" markerHeight="6" orient="auto">'
         '<path d="M0 0L10 5L0 10z" style="fill:var(--edge);stroke:none"/></marker></defs>']
    for t in ts.values():
        for d in t["deps"]:
            if d in pos:
                x1, y1 = pos[d][0] + W, pos[d][1] + H / 2
                x2, y2 = pos[t["id"]][0], pos[t["id"]][1] + H / 2
                s.append(f'<path d="M{x1} {y1} C{x1 + GX / 2} {y1} {x2 - GX / 2} {y2} {x2 - 2} {y2}" marker-end="url(#a)"/>')
    for t in ts.values():
        x, y = pos[t["id"]]
        h = health(T, t, now)
        st_ = state_of(t)
        cls = state_of(t) + (" critical" if t["ready"] and t["dependents"] >= 3 else " ready" if t["ready"] else "")
        width_ = 1 + min(t["dependents"], 5) * 0.5
        # the fill color already says the status; spend the space on who has it
        line2 = ("dropped" if t["status"] == "dropped" else "needs a human" if st_ == "hold" else
                 t["owner"] if t["status"] == "in_progress" and not t["stale"] else
                 f'{state_of(t)} · {t["owner"]}' if t["owner"] and t["status"] != "todo" else
                 "todo · ready" if t["ready"] else "todo")
        tip = f'{t["id"]}: {t["title"]}\nunblocks {t["dependents"]}' + (f'\nowner {t["owner"]}' if t["owner"] else "")
        s.append(f'<g><title>{e(tip)}</title><rect class="{cls}" x="{x}" y="{y}" width="{W}" height="{H}" rx="8" stroke-width="{width_}"/>'
                 + (f'<rect class="h-{h}" x="{x}" y="{y}" width="5" height="{H}" rx="2" style="stroke-width:0;fill:var(--{h})"/>' if h else "")
                 + f'<text x="{x + 12}" y="{y + 21}" font-weight="600">{e(clip(t["title"], 27))}</text>'
                 f'<text class="m" x="{x + 12}" y="{y + 40}">{e(clip(line2, 30))}</text></g>')
    s.append("</svg>")
    return "".join(s)


def render(T, ts, feed, live, ref_label, now, refresh=None, sync=None, tries=None, base="main", frozen=None):
    n = {k: sum(1 for t in ts.values() if state_of(t) == k)
         for k in ("todo", "in_progress", "stale", "done", "hold", "dropped")}
    live_n = len(ts) - n["dropped"]  # dropped tasks are not part of the plan any more
    total = max(live_n, 1)
    ready = sum(1 for t in ts.values() if t["ready"] and t["status"] == "todo")
    cp = critical_path(ts)
    bar = "".join(f'<i class="b-{c}" style="width:{100 * n[k] / total:.2f}%"></i>'
                  for k, c in (("done", "done"), ("in_progress", "prog"), ("stale", "stale"), ("hold", "stale")))

    # agents: live status branches, plus owners of active tasks that have none (humans, old runners)
    cards, owners = [], {a.get("owner") for a in live}
    active = {t["owner"]: t for t in ts.values() if t["status"] == "in_progress"}
    finished = {}
    for t in ts.values():
        if t["status"] == "done" and t["owner"]:
            finished[t["owner"]] = finished.get(t["owner"], 0) + 1
    rows = [dict(a, live=True) for a in live] + [dict(owner=o, live=False) for o in active if o not in owners]
    for a in sorted(rows, key=lambda a: a.get("owner", "")):
        owner = a.get("owner", "?")
        host, _, name = owner.rpartition("/")
        t = active.get(owner)
        if not a["live"]:
            state = "nostatus"
        elif a.get("state") in ("stopped", "finished", "blocked"):
            state = a["state"]
        elif now - a.get("updated", 0) > OFFLINE_AFTER:
            state = "offline"
        else:
            state = a.get("state", "working")
        if state in ("stopped", "finished") and not t and not finished.get(owner):
            continue  # old agent that never did anything
        task = (f'<b>{e(t["id"])}</b> · {e(t["title"])}' if t else '<span class="sub">no task</span>')
        meta = []
        if t and t.get("claimed_at"):
            meta.append(f'on it for {dur(now - t["claimed_at"])}')
        if t and T.lease_end(t) is not None and t.get("seen_at"):  # humans have no lease, so no heartbeat
            meta.append(f'<span class="dot d-{health(T, t, now)}"></span>heartbeat {ago(t["seen_at"], now)}')
        if t:
            if t["attempts"] > 1:
                meta.append(f'attempt {t["attempts"]}')
        meta.append(f'{finished.get(owner, 0)} done')
        if state == "offline":
            meta.insert(0, f'last seen {ago(a.get("updated"), now)}')
        doing = a.get("doing") if a["live"] and state not in ("stopped", "finished", "offline") else None
        doing_html = (f'<div class="doing" title="{e(doing)}">{e(doing)}</div>'
                      f'<div class="meta">updated {ago(a.get("doing_at") or a.get("updated"), now)}</div>' if doing else "")
        label = {"nostatus": "no live status", "limited": "usage limit"}.get(state, state)
        cards.append(f'<div class="panel agent"><div class="top"><div class="who">{e(name or owner)}{f" <span>@ {e(host)}</span>" if host else ""}</div>'
                     f'<span class="pill p-{state}">{e(label)}</span></div><div class="task">{task}</div>{doing_html}'
                     f'<div class="meta">{" · ".join(m for m in meta if m)}</div></div>')

    # task table: what needs attention first
    rank = {"hold": -1, "stale": 0, "in_progress": 1, "todo": 2, "done": 3, "dropped": 4}
    trs = []
    for t in sorted(ts.values(), key=lambda t: (rank[state_of(t)], not t["ready"], -t["dependents"], t["id"])):
        st = state_of(t)
        if t["status"] == "done" and t.get("claimed_at") and t.get("done_at"):
            time_ = dur(t["done_at"] - t["claimed_at"])
        elif t["status"] == "in_progress" and t.get("claimed_at"):
            time_ = dur(now - t["claimed_at"]) + " so far"
        else:
            time_ = ""
        deps = " ".join(f'<span class="{"dep-done" if ts.get(d, {}).get("status") == "done" else ""}">{e(d)}</span>' for d in t["deps"])
        saved = (tries or {}).get(t["id"], [])
        links = []
        for n_, br in saved:
            u, tip = compare_url(T, base, br), f"git diff origin/{base}...origin/{br}"
            links.append(f'<a href="{e(u)}" title="{e(tip)}">#{n_}</a>' if u else f'<span title="{e(tip)}" class="dep-tip">#{n_}</span>')
        extra = [f'part of {e(t["parent"])}' if t.get("parent") else "",
                 f'split into {e(t["subtasks"])}' if t.get("subtasks") else "",
                 (f'{t["attempts"]} attempts' + (f' (abandoned: {" ".join(links)})' if links else ""))
                 if t["attempts"] > 1 or links else "",
                 f'{t["takeovers"]} takeover{"s" * (t["takeovers"] > 1)}' if t.get("takeovers") else "",
                 f'{t["releases"]} release{"s" * (t["releases"] > 1)}' if t.get("releases") else ""]
        label = ("needs human" if st == "hold" else st.replace("_", " ")) + (" · ready" if t["ready"] and t["status"] == "todo" else "")
        trs.append(f'<tr><td><b>{e(t["id"])}</b><div class="sub">{e(t["title"])}</div></td>'
                   f'<td><span class="st s-{st}">{e(label)}</span></td><td>{e(t["owner"]) if t["status"] != "todo" else ""}</td>'
                   f'<td class="num">{t["dependents"] or ""}</td><td>{deps}</td><td class="num">{time_}</td>'
                   f'<td class="sub">{", ".join(x for x in extra if x)}</td></tr>')

    held = [t for t in ts.values() if state_of(t) == "hold"]
    human = ("" if not held else
             '<h2 style="color:var(--bad)">Needs a human</h2><div class="panel human"><ul>' + "".join(
                 f'<li><b>{e(t["id"])}</b> · {e(t["title"])}<div>{e(t["hold"] or "depends on dropped " + ", ".join(t["orphaned"]))}</div>'
                 f'<div class="sub">notes in <code>tasks/{e(t["id"])}.md</code> · when resolved: <code>'
                 + (f'python tasks.py unhold {e(t["id"])}' if t["hold"] else "edit its deps or drop it")
                 + f'</code>{" · blocks " + str(t["dependents"]) + " task" + "s" * (t["dependents"] != 1) if t["dependents"] else ""}'
                 f'</div></li>' for t in held) + "</ul></div>")
    freeze = (f'<div class="panel frozen"><b>Board frozen</b>: no new claims while the plan changes. {e(frozen)}'
              f'<div class="sub">running tasks carry on · when done: <code>python tasks.py unfreeze</code></div></div>'
              if frozen else "")
    assumed = sorted(((w, t["id"], who, txt) for t in ts.values() for w, who, txt in T.assumptions(t)), reverse=True)
    assume = ("" if not assumed else
              f'<h2>Assumptions to review ({len(assumed)})</h2><details class="panel assume"><summary>Decisions agents '
              f'took without a human, newest first</summary><ul>' + "".join(
                  f'<li><b>{e(i)}</b> <span class="sub">· {e(who)} · {e(w)}</span><div>{e(txt)}</div></li>'
                  for w, i, who, txt in assumed) + "</ul></details>")
    verbs = {"claim": "claimed", "takeover": "took over", "done": "finished", "release": "released",
             "redo": "restarted", "commit": "", "hold": "put on hold", "unhold": "took off hold",
             "split": "split", "extend": "extended", "drop": "dropped", "undrop": "undropped",
             "assume": "recorded an assumption on", "confirm": "confirmed an assumption on",
             "reject": "rejected an assumption on", "freeze": "froze the board", "unfreeze": "unfroze the board"}
    items = []
    for ev in feed[:60]:
        if ev["kind"] == "commit":
            body = f'<span class="k k-commit">{e(ev["who"])}</span> {e(ev["text"][:90])}'
        else:
            body = (f'<span class="k k-{ev["kind"]}">{e(ev["who"])}</span> {verbs[ev["kind"]]} <b>{e(ev["task"] or "")}</b>'
                    + (f' from {e(ev["prev"])}' if ev.get("prev") else "")
                    + (f' <span class="sub">(attempt {e(ev["attempt"])})</span>' if ev.get("attempt") else ""))
        items.append(f'<li><span class="t" title="{stamp(ev["at"])}">{ago(ev["at"], now)}</span>{body}</li>')

    reload_js = (f"<script>try{{scrollTo(0,+sessionStorage.getItem('y')||0)}}catch(_){{}}"
                 f"setTimeout(()=>{{try{{sessionStorage.setItem('y',scrollY)}}catch(_){{}}location.reload()}},{refresh * 1000})</script>"
                 if refresh else "")
    note = f"refreshes every {refresh}s while <code>tasks.py watch</code> runs" if refresh else "snapshot (run <code>tasks.py watch</code> for live updates)"
    return f"""<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Task board</title><style>{CSS}</style></head><body><main>
<h1>Task board</h1><div class="sub">{e(ref_label)} · generated {time.strftime("%H:%M:%S", time.localtime(now))} · {note}</div>
<div class="bar">{bar}</div>
<div class="stats"><span><b>{n["done"]}/{live_n}</b> done</span><span><b>{n["in_progress"]}</b> in progress</span>
{f'<span style="color:var(--bad)"><b>{n["stale"]}</b> stale</span>' if n["stale"] else ""}{f'<span style="color:var(--bad)"><b>{n["hold"]}</b> need a human</span>' if n["hold"] else ""}<span><b>{n["todo"]}</b> todo ({ready} ready)</span>
{f'<span class="sub">{n["dropped"]} dropped</span>' if n["dropped"] else ""}<span>longest remaining chain: <b>{cp}</b> task{"s" * (cp != 1)}</span></div>
{freeze}{human}{assume}
<h2>Agents</h2><div class="agents">{"".join(cards) or '<div class="panel empty">No agents have reported yet.</div>'}</div>
{repos_panel(sync, now)}
<h2>Dependencies</h2><div class="panel graph">{svg(ts, now, T)}</div>
<div class="sub" style="margin-top:6px">Arrows point from a task to what it unblocks. Thicker border = unblocks more; red border = ready and blocking 3+.
Left stripe on active tasks = heartbeat: green fresh, amber aging, red stale.</div>
<div class="cols"><div><h2>Tasks</h2><div class="panel" style="overflow-x:auto"><table>
<tr><th>Task</th><th>Status</th><th>Owner</th><th>Unblocks</th><th>Depends on</th><th>Time</th><th></th></tr>{"".join(trs)}</table></div></div>
<div><h2>Activity</h2><div class="panel"><ul class="feed">{"".join(items) or '<li class="empty">Nothing yet.</li>'}</ul></div></div></div>
</main>{reload_js}</body></html>"""


def write(T, out, ref, ts=None, refresh=None):
    """Render the board for `ref` (tasks from `ts` if given, e.g. the working tree) into `out`."""
    now = time.time()
    ts = ts if ts is not None else tasks_at(T, ref)
    feed = history(T, ref, ts)
    head = T.git("rev-parse", "--short", ref, check=False).stdout.strip()
    tmp = out.with_suffix(".tmp")
    base = T.git("rev-parse", "--abbrev-ref", ref if ref != "HEAD" else "HEAD", check=False).stdout.strip()
    base = base.split("/", 1)[1] if ref != "HEAD" and "/" in base else base
    tmp.write_text(render(T, ts, feed, agents(T), f"{ref} @ {head}", now, refresh, sync_state(T),
                          attempt_branches(T), base or "main", T.frozen(ref if ref != "HEAD" else None)))
    tmp.replace(out)  # atomic: the browser never reloads a half-written file
    return ts
