// Regenerates the raster web icons from the brand SVGs (see README.md "Generating the icons").
// Renders with the Chromium of the web part's Playwright, writes the ICO container itself (PNG entries).
// Usage, from the repository root: node docs/brand/generate-icons.mjs
import { readFileSync, writeFileSync, copyFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const brand = dirname(fileURLToPath(import.meta.url));
const root = join(brand, '..', '..');
const pub = join(root, 'web', 'public');
const { chromium } = createRequire(join(root, 'web', 'package.json'))('playwright');

const svg = (name) => readFileSync(join(brand, name), 'utf8');

async function render(page, source, size) {
  await page.setViewportSize({ width: size, height: size });
  await page.setContent(
    `<html><body style="margin:0;background:transparent">` +
      source.replace('<svg ', `<svg width="${size}" height="${size}" style="display:block" `) +
      `</body></html>`,
  );
  return page.screenshot({ omitBackground: true, clip: { x: 0, y: 0, width: size, height: size } });
}

// ICO with PNG-compressed entries (supported by every browser since Windows Vista era).
function ico(entries) {
  const header = Buffer.alloc(6 + 16 * entries.length);
  header.writeUInt16LE(0, 0);
  header.writeUInt16LE(1, 2);
  header.writeUInt16LE(entries.length, 4);
  let offset = header.length;
  entries.forEach(({ size, png }, i) => {
    const at = 6 + 16 * i;
    header.writeUInt8(size >= 256 ? 0 : size, at);
    header.writeUInt8(size >= 256 ? 0 : size, at + 1);
    header.writeUInt8(0, at + 2);
    header.writeUInt8(0, at + 3);
    header.writeUInt16LE(1, at + 4);
    header.writeUInt16LE(32, at + 6);
    header.writeUInt32LE(png.length, at + 8);
    header.writeUInt32LE(offset, at + 12);
    offset += png.length;
  });
  return Buffer.concat([header, ...entries.map((e) => e.png)]);
}

const browser = await chromium.launch();
try {
  // Light colour scheme: the ICO cannot switch, and the ink mark reads on the usual light tab strip.
  const page = await browser.newPage({ colorScheme: 'light' });
  const mark = svg('mark.svg');
  const entries = [];
  for (const size of [16, 32, 48]) entries.push({ size, png: await render(page, mark, size) });
  writeFileSync(join(pub, 'favicon.ico'), ico(entries));
  // iOS rounds the corners itself and fills transparent pixels black, so the touch icon is a full square.
  const touch = svg('app-icon.svg').replace(' rx="14"', '');
  writeFileSync(join(pub, 'apple-touch-icon.png'), await render(page, touch, 180));
  copyFileSync(join(brand, 'mark.svg'), join(pub, 'favicon.svg'));
} finally {
  await browser.close();
}
console.log('wrote web/public/favicon.svg, favicon.ico (16, 32, 48), apple-touch-icon.png (180)');
