import { createHash } from 'node:crypto';
import { writeFile } from 'node:fs/promises';
import { APIRequestContext } from '@playwright/test';
import { expect, test } from '../support/fixtures';

const MB = 1024 * 1024;

/**
 * SPEC.md §8.2 uploads and previews: in a folder of its own, the test uploads several files through
 * the picker (one of 60 MB, which goes through the resumable chunked upload), checks that the server
 * stored them with the right content, then opens a PDF in the preview and sees pdf.js render a page.
 */
test.describe('Files upload and preview', () => {
  test('multi-file upload with a 60 MB file, PDF preview', async ({ loggedIn: page }, info) => {
    test.setTimeout(180_000);
    const run = Date.now().toString(36);
    const folder = await post<{ id: string }>(page.request, '/api/v1/folders', {
      name: `E2E upload ${run}`,
    });

    // 60 MB of fictitious bytes, too large for a Playwright buffer: written to the test's output dir.
    const big = Buffer.alloc(60 * MB);
    for (let i = 0; i < big.length; i += 4) big.writeUInt32LE(Math.imul(i, 2654435761) >>> 0, i);
    const bigSha = createHash('sha256').update(big).digest('hex');
    // Playwright takes either paths or buffers in one call: all three files go to disk.
    const paths = [
      info.outputPath('archive.bin'),
      info.outputPath('invoice.pdf'),
      info.outputPath('readme.md'),
    ];
    await writeFile(paths[0], big);
    await writeFile(paths[1], pdf(run));
    await writeFile(paths[2], '# Fictitious\n');

    await page.goto(`/files/${folder.id}`);
    await expect(page.getByRole('heading', { level: 1 })).toHaveText(`E2E upload ${run}`);

    await page.getByLabel('Files to upload').setInputFiles(paths);

    const uploads = page.locator('app-upload-panel');
    await expect(uploads.locator('.job')).toHaveCount(3);
    await expect(uploads.locator('.job[data-state="done"]')).toHaveCount(3, { timeout: 150_000 });
    await expect(uploads.getByRole('heading')).toHaveText('Uploads finished');
    await expect(page.locator('.entries .entry .name')).toHaveText([
      'archive.bin',
      'invoice.pdf',
      'readme.md',
    ]);

    // The server has the 60 MB file with exactly the bytes sent.
    const listing = await get<{
      items: { file?: { name: string; size: number; sha256: string } }[];
    }>(page.request, `/api/v1/drive?folderId=${folder.id}`);
    const stored = listing.items.find((item) => item.file?.name === 'archive.bin')?.file;
    expect(stored).toMatchObject({ size: 60 * MB, sha256: bigSha });

    // The PDF opens in the preview and pdf.js draws its page.
    await page.getByRole('button', { name: 'Preview invoice.pdf' }).click();
    const preview = page.getByRole('dialog', { name: 'invoice.pdf' });
    await expect(preview.getByText('Page 1 of 1')).toBeVisible();
    const canvas = preview.locator('canvas[data-rendered="1"]');
    await expect(canvas).toBeVisible();
    // Something (the page's text) is drawn: the canvas is not one plain colour.
    const colours = await canvas.evaluate((el: HTMLCanvasElement) => {
      const ctx = el.getContext('2d')!;
      const data = ctx.getImageData(0, 0, el.width, el.height).data;
      const seen = new Set<number>();
      for (let i = 0; i < data.length && seen.size < 3; i += 4 * 97) {
        seen.add((data[i] << 16) | (data[i + 1] << 8) | data[i + 2]);
      }
      return seen.size;
    });
    expect(colours).toBeGreaterThan(1);

    // Next goes to the Markdown file, rendered as text.
    await preview.getByRole('button', { name: 'Next file' }).click();
    await expect(
      page.getByRole('dialog', { name: 'readme.md' }).getByRole('heading', { name: 'Fictitious' }),
    ).toBeVisible();
    await page.keyboard.press('Escape');
    await expect(page.getByRole('dialog')).toHaveCount(0);
  });
});

/** A one-page PDF that shows a line of text (offsets in the xref table computed). */
function pdf(run: string): string {
  const stream = `BT /F1 36 Tf 72 700 Td (Fictitious invoice ${run}) Tj ET`;
  const objects = [
    '<< /Type /Catalog /Pages 2 0 R >>',
    '<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
    '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>',
    `<< /Length ${stream.length} >>\nstream\n${stream}\nendstream`,
    '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>',
  ];
  let out = '%PDF-1.4\n';
  const offsets: number[] = [];
  objects.forEach((body, i) => {
    offsets.push(out.length);
    out += `${i + 1} 0 obj\n${body}\nendobj\n`;
  });
  const xref = out.length;
  out += `xref\n0 ${objects.length + 1}\n0000000000 65535 f \n`;
  for (const offset of offsets) out += `${String(offset).padStart(10, '0')} 00000 n \n`;
  out += `trailer\n<< /Size ${objects.length + 1} /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`;
  return out;
}

async function xsrf(request: APIRequestContext): Promise<Record<string, string>> {
  const cookie = (await request.storageState()).cookies.find((c) => c.name === 'XSRF-TOKEN');
  expect(cookie, 'XSRF-TOKEN cookie').toBeDefined();
  return { 'X-XSRF-TOKEN': cookie!.value };
}

async function post<T>(request: APIRequestContext, url: string, data: object): Promise<T> {
  const response = await request.post(url, { headers: await xsrf(request), data });
  expect(response.ok(), `POST ${url}: ${response.status()}`).toBe(true);
  return (await response.json()) as T;
}

async function get<T>(request: APIRequestContext, url: string): Promise<T> {
  const response = await request.get(url);
  expect(response.ok(), `GET ${url}: ${response.status()}`).toBe(true);
  return (await response.json()) as T;
}
