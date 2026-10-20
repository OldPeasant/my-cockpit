# Running the agents and the dashboard (this laptop)

How to keep the TFP agents and the dashboard running on the lead's Linux laptop (Fedora, Podman).
Teammates' laptops: see `TEAM.md`.

## What keeps running

| Process | How it runs | Survives closing Claude Code / the terminal | Survives logout | Survives reboot |
|---|---|---|---|---|
| Agents (`agents.py run agent-N`) | detached runners under the systemd user manager | yes | yes (lingering enabled) | no |
| Dashboard | systemd user service `cockpit-dashboard` | yes | yes | no |

- **Sleep pauses everything.** If the laptop sleeps for more than 20 minutes, the agents' task leases expire.
  Agents on other laptops then take those tasks over, which is safe.
- **Lingering** keeps user processes alive after logout. It was enabled once with `loginctl enable-linger $USER`.
  Check it with `loginctl show-user $USER -p Linger`.

## Dashboard

http://127.0.0.1:8765/ (localhost only; the page carries its own access token).

```sh
cd /home/eric/work/my-cockpit
systemd-run --user --unit=cockpit-dashboard -p KillMode=process --working-directory=$PWD \
  --setenv=ANDROID_HOME=$HOME/Android/Sdk --setenv=TESTCONTAINERS_RYUK_DISABLED=true \
  --setenv=DOCKER_HOST=$DOCKER_HOST --setenv=PATH="$PATH" python3 dashboard/server.py --no-browser
```

| Task | Command |
|---|---|
| Is it running? | `systemctl --user status cockpit-dashboard` |
| Log | `journalctl --user -u cockpit-dashboard` |
| Stop (agents keep running) | `systemctl --user stop cockpit-dashboard` |
| Restart | stop it, then run the `systemd-run` command again |

- `KillMode=process`: by default systemd stops every process in the unit when it stops. With this setting it
  stops only the dashboard, so agents started from it keep running.
- Agents started from the dashboard inherit its environment. That is why the command sets `ANDROID_HOME`,
  `DOCKER_HOST` (Podman socket) and `TESTCONTAINERS_RYUK_DISABLED`.
- Don't start it with `nohup … &` from Claude Code's `!` prompt. That background job ends with the one-off shell,
  and `!` commands see a private `/tmp`.

## Agents

Start them from the dashboard, or on the command line from the repo root:

```sh
export ANDROID_HOME=$HOME/Android/Sdk TESTCONTAINERS_RYUK_DISABLED=true   # DOCKER_HOST is set in the profile
python3 agents.py start 2                 # agent-1 and agent-2
python3 agents.py start 2 --first 4       # adds agent-4 and agent-5 next to running ones
python3 agents.py status                  # alive? working on what?
python3 agents.py logs 1                  # follow agent-1
python3 agents.py stop --finish           # no new tasks, finish the current ones
python3 agents.py stop                    # stop now; their tasks resume from wip/<task> later
```

- `start N` numbers agents from 1, or from `--first`. Numbers that already run are skipped with
  `already running`, so add agents with `--first <next free number>`.
- `start` refuses with `unpushed commits` or `uncommitted changes`: agents clone from GitHub, so push first.
  Claude only commits; you push:
  `for i in 1 2 3; do git pull -q --rebase && git push && break; sleep 3; done`
- One subscription sustains about 2 agents. With more, they pause for usage limits more often; the dashboard
  shows them as "usage limit".

## After a reboot

1. `cd /home/eric/work/my-cockpit && git pull`
2. Start the dashboard (the `systemd-run` command above).
3. Start the agents from the dashboard, or with `python3 agents.py start <n>` as above. Tasks they held carry on
   from their `wip/` branches; tasks whose lease expired may already have been taken over by other laptops.

## Prerequisites the agents' check needs

These must be in place before agents start: Podman socket running (`systemctl --user is-active podman.socket`),
Android SDK in `~/Android/Sdk`, Node 24 (nvm), JDK 21. Everything else is in `docs/ARCHITECTURE.md` "Install".
