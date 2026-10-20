# Dashboard

A local web app for the task board: everything `tasks.py` and `agents.py` can do, with buttons.
Each team member runs it on their own laptop, in their own clone of the working repo.

```bash
python dashboard/server.py          # opens http://127.0.0.1:8765/
```

Needs only Python 3 and git, like the rest of TFP: the built app is committed in `app/`.
Options: `--port`, `--interval` (seconds between fetches, default 20), `--ref`, `--repo`, `--no-browser`.

## How it works
- **Reading:** the server fetches origin every 20 s and reads the board exactly like `tasks.py watch`
  (it imports `tasks.py` and `board.py`), plus this laptop's agents (`../<repo>-agents/`).
- **Acting:** every button runs the real command (`python tasks.py claim <id>`, `python agents.py start 2`, ...)
  in this clone, one at a time, and shows it with its output (bottom right). So the protocol's rules,
  races and refusals are exactly the CLI's. Editing or creating a task file goes through `taskedit.py`,
  which never touches protocol fields (status, owner, lease, ...) and pushes only that file.
- **Safety:** it listens on 127.0.0.1 only, and every API call needs a random token embedded in the page.

## Changing the app
The Angular source is not copied into this repo; it lives in `dashboard/web/` of the upstream TFP repo (https://github.com/OldPeasant/tfp). There, with Node 22+:

```bash
cd dashboard/web && npm install
npm run build                        # writes ../app (commit it)
# live reload while developing:
TFP_DASHBOARD_TOKEN=dev python dashboard/server.py --no-browser &
npm start                            # then open http://localhost:4200/?token=dev
```

Tests for the server: `python dashboard/test_dashboard.py`.
