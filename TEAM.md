# Get your agents working

The project is set up already. Your laptop just adds more AI agents that pick tasks off the shared
board and build them. Allow 10 minutes. Works on Linux, macOS and Windows.

## 1. Install (once)
| | Linux | macOS | Windows (PowerShell) |
|---|---|---|---|
| git | `sudo apt install git` / `sudo dnf install git` | `xcode-select --install` | [Git for Windows](https://git-scm.com/download/win) |
| Python 3 | usually installed | installed with the above | [python.org](https://www.python.org/downloads/): tick "Add python.exe to PATH" |
| Claude Code | `curl -fsSL https://claude.ai/install.sh \| bash` | same as Linux | `irm https://claude.ai/install.ps1 \| iex` |

Then:
- Run `claude` once and log in with **your own** Claude subscription.
- Ensure the official **frontend-design** plugin is installed and available in Claude Code. If it is already installed, continue.
  If it is missing, install or enable it before starting TFP workers.
  Frontend/UI tasks may rely on this plugin.
- Check that `ANTHROPIC_API_KEY` is **not** set, otherwise you pay per API call. Linux/macOS:
  `echo $ANTHROPIC_API_KEY`. PowerShell: `echo $env:ANTHROPIC_API_KEY`. Both should print nothing.
- Install **the project's own tools** (Node, JDK, ...): see "Install" in `docs/ARCHITECTURE.md` of the
  repo. Your agents run the tests on your laptop.
- Get access to the working repo on GitLab (ask the lead), and set up git to clone it (SSH key or token).

Below, `python` means `python3` on Linux/macOS.

## 2. Start
```bash
git clone https://github.com/OldPeasant/my-cockpit.git my-cockpit
cd my-cockpit
python agents.py start 2 --host <yourname>     # 2 agents; your name tells laptops apart
```
Agents run in the background in `../my-cockpit-agents/`, one clone each. You can close the terminal,
but the laptop must stay awake.

## 3. Watch
Dashboard: board, your agents, start/stop buttons. It runs on your laptop and manages your clone:
```bash
python dashboard/server.py     # opens http://127.0.0.1:8765/ (agents started there inherit this shell's env)
```
Or on the command line:
```bash
python tasks.py watch           # then open the printed board.html link in a browser
python agents.py status         # your agents: alive? working on what?
python agents.py logs 1         # follow agent-1's log (Ctrl-C to stop following)
```

## 4. Stop and resume
```bash
python agents.py stop --finish                 # leaving soon: no new tasks, finish the current ones
python agents.py status                        # all 'stopped'? then shut down, nobody waits on you
python agents.py start 2 --host <yourname>     # later: they pick up new tasks again
```
Need to go right now? `python agents.py stop` ends the sessions immediately; your agents' tasks stay
claimed until you `start` again, or for 20 min, after which another agent takes them over.
If your laptop dies, nothing is lost: after 20 min another agent takes over your agents' tasks.

## Good to know
- **Usage limits:** all your agents share your subscription's limit. When it runs out, they hand
  their tasks to other laptops and resume by themselves when it resets. Nothing to do. If it happens
  often, run 1 agent instead of 2. "paused" on the dashboard means sessions keep failing for
  another reason (e.g. logged out: run `claude` and log in again). Check `python agents.py logs <n>`.
- **Don't** edit files in `../my-cockpit-agents/`, push to main, or change `tasks/` by hand.
- **Working on something yourself?** `python tasks.py claim <id>` ... `python tasks.py done <id>`.
  See AGENTS.md for the rules.
- **"Needs a human"** at the top of the dashboard: an agent got stuck and is waiting for a decision.
  The lead handles those.
- **Problems:** check `python agents.py logs <n>`, then ask the lead.

## The morning after: review and change the plan
Leave the agents running while you do this. Everything goes through a Claude session in your own clone
of the working repo (not `../my-cockpit-agents/`); Claude runs the `tasks.py` commands below for you.

1. **Look (15 min).** `git pull`, then `python tasks.py status`, the dashboard, and the app itself.
   `python tasks.py assumptions` lists every decision the agents took without a human that nobody has
   reviewed yet, newest first. That's the fastest way to spot a wrong turn. `python tasks.py confirm <id> <n>`
   the right ones (Confirm in the dashboard), so the list only holds what still needs a look; reject a wrong one
   with `python tasks.py reject <id> <n> --note "<why>"` and fix it with step 2 or 3.
2. **Small fix?** Add a task for it (`python tasks.py new ...`), commit, push. That's all.
3. **Plan changes?** Stop the affected work first, then replan:
   - Big change: `python tasks.py freeze "new plan for X, back at 10:00"`. Agents claim nothing new
     until `unfreeze`. Tasks that are already running carry on.
   - Tasks already running the wrong way: `python tasks.py hold <id> "<why>" --force`, or `drop <id>
     --note "<why>" --force` if they aren't wanted at all. Their agent stops within ~5 min, and the work so
     far stays on `wip/<id>`.
   - Tasks not started yet: edit them, or `drop` / `hold` them.
   - Work that's already done: it's on main. Add a task that changes or removes it.
4. **Replan** with Claude: update the spec docs, rewrite the task files, add new tasks, fix the deps of
   tasks that depended on dropped ones. `python tasks.py check` must say `ok`. Commit and push.
5. **Release:** `python tasks.py unhold <id>` for the held tasks you rewrote (the next agent continues
   from `wip/<id>`, with the new spec), then `python tasks.py unfreeze`. Agents pick up the new plan
   within a minute.

Expect one race: an agent may finish (`done`) a task right before you hold or drop it. Then that task
is done, and you fix it with a follow-up task like any other done work.
