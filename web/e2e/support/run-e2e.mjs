// `npm run e2e [-- <playwright args>]`: brings up deploy/docker-compose.e2e.yml (server with profile e2e, fresh
// Postgres), waits for the health check, runs Playwright (chromium) and tears the stack down again.
//
// Environment:
//   E2E_BASE_URL  run against an already running stack instead (no compose up/down)
//   E2E_COMPOSE   compose command (default "docker compose"; e.g. "podman-compose")
//   E2E_PORT      host port of the server (default: a free one)
//   E2E_KEEP=1    leave the stack running after the tests (tear it down with the printed command)
import { spawn, spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { createServer } from 'node:net';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const webDir = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const composeFile = resolve(webDir, '../deploy/docker-compose.e2e.yml');
const playwrightArgs = process.argv.slice(2);
const HEALTH_TIMEOUT_MS = 180_000;

async function main() {
  if (process.env.E2E_BASE_URL) {
    return runPlaywright(process.env.E2E_BASE_URL);
  }
  const port = process.env.E2E_PORT ?? String(await freePort());
  // One project per clone, so parallel clones on the same machine get separate stacks.
  const project = `cockpit-e2e-${createHash('sha256').update(webDir).digest('hex').slice(0, 8)}`;
  const [composeCmd, ...composeBase] = (process.env.E2E_COMPOSE ?? 'docker compose').split(' ');
  const composeArgs = [...composeBase, '-p', project, '-f', composeFile];
  const env = { ...process.env, E2E_PORT: port };
  const compose = (args, options = {}) =>
    spawnSync(composeCmd, [...composeArgs, ...args], { stdio: 'inherit', env, ...options });
  const down = () => {
    if (process.env.E2E_KEEP === '1') {
      console.log(`[e2e] stack kept: ${[composeCmd, ...composeArgs, 'down', '-v'].join(' ')}`);
      return;
    }
    console.log('[e2e] tearing down the stack');
    compose(['down', '-v'], { stdio: ['ignore', 'ignore', 'inherit'] });
  };

  let interrupted = false;
  for (const signal of ['SIGINT', 'SIGTERM']) {
    process.on(signal, () => {
      interrupted = true;
    });
  }

  const started = Date.now();
  console.log(`[e2e] starting ${project} on port ${port}`);
  // A leftover stack of an aborted run would keep its old database.
  compose(['down', '-v'], { stdio: ['ignore', 'ignore', 'ignore'] });
  const up = compose(['up', '-d', '--build']);
  if (up.status !== 0 || interrupted) {
    down();
    return up.status || 1;
  }
  const baseUrl = `http://127.0.0.1:${port}`;
  try {
    await waitForHealth(baseUrl, () => interrupted);
  } catch (error) {
    console.error(`[e2e] ${error.message}`);
    compose(['logs', '--tail', '200', 'server']);
    down();
    return 1;
  }
  console.log(`[e2e] stack healthy after ${Math.round((Date.now() - started) / 1000)} s`);
  let status = 1;
  try {
    status = await runPlaywright(baseUrl);
    if (status !== 0) compose(['logs', '--tail', '100', 'server']);
  } finally {
    down();
  }
  return status;
}

function freePort() {
  return new Promise((resolvePort, reject) => {
    const server = createServer();
    server.once('error', reject);
    server.listen(0, '127.0.0.1', () => {
      const { port } = server.address();
      server.close(() => resolvePort(port));
    });
  });
}

async function waitForHealth(baseUrl, isInterrupted) {
  const deadline = Date.now() + HEALTH_TIMEOUT_MS;
  while (Date.now() < deadline) {
    if (isInterrupted()) throw new Error('interrupted');
    try {
      const response = await fetch(`${baseUrl}/actuator/health`);
      if (response.ok && (await response.json()).status === 'UP') return;
    } catch {
      // Not listening yet.
    }
    await new Promise((resolveWait) => setTimeout(resolveWait, 1000));
  }
  throw new Error(`server not healthy at ${baseUrl} after ${HEALTH_TIMEOUT_MS / 1000} s`);
}

function runPlaywright(baseUrl) {
  return new Promise((resolveRun) => {
    const child = spawn('npx', ['playwright', 'test', ...playwrightArgs], {
      cwd: webDir,
      stdio: 'inherit',
      env: { ...process.env, E2E_BASE_URL: baseUrl },
    });
    child.on('exit', (code) => resolveRun(code ?? 1));
  });
}

process.exitCode = await main();
