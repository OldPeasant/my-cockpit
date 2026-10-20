// Fails when src/app/api/generated/ differs from what `npm run api:gen` would generate from
// ../api/openapi.yaml (run by `npm run check`). Fix: `npm run api:gen` and commit the result.
import { execFileSync } from 'node:child_process';
import { mkdtempSync, readdirSync, readFileSync, rmSync, statSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, relative } from 'node:path';

const committed = 'src/app/api/generated';
const fresh = mkdtempSync(join(tmpdir(), 'api-gen-check-'));

function files(dir) {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    return statSync(path).isDirectory() ? files(path) : [path];
  });
}

function contents(dir) {
  const map = new Map();
  for (const path of files(dir)) {
    map.set(relative(dir, path).split('\\').join('/'), readFileSync(path, 'utf8'));
  }
  return map;
}

try {
  execFileSync(process.execPath, ['node_modules/ng-openapi-gen/lib/index.js', '-o', fresh], {
    stdio: 'inherit',
  });
  const expected = contents(fresh);
  const actual = contents(committed);
  const diffs = [...new Set([...expected.keys(), ...actual.keys()])]
    .sort()
    .filter((file) => expected.get(file) !== actual.get(file))
    .map((file) =>
      !actual.has(file)
        ? `missing: ${file}`
        : !expected.has(file)
          ? `stale: ${file}`
          : `changed: ${file}`,
    );
  if (diffs.length > 0) {
    console.error(`${committed} is out of date with ../api/openapi.yaml:`);
    for (const diff of diffs) console.error(`  ${diff}`);
    console.error('Run `npm run api:gen` and commit the result.');
    process.exitCode = 1;
  } else {
    console.log(`${committed} is up to date.`);
  }
} finally {
  rmSync(fresh, { recursive: true, force: true });
}
