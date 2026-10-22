import { expect, test } from '../support/fixtures';

/**
 * SPEC.md §8.3 contacts: a vCard file imported in the Contacts view becomes a contact with its
 * fields, and its birthday shows up in the calendar as a yearly all-day event (the virtual
 * Birthdays calendar). The export of the contact is a vCard again.
 */
test.describe('contacts', () => {
  test('import a vCard; the birthday appears in the calendar', async ({ loggedIn: page }) => {
    const run = Date.now().toString(36);
    const family = `Testperson${run}`;
    const name = `Rosalind ${family}`;
    const vcard = [
      'BEGIN:VCARD',
      'VERSION:3.0',
      `FN:${name}`,
      `N:${family};Rosalind;;;`,
      'ORG:Example Labs',
      'TITLE:Researcher',
      `EMAIL;TYPE=work:rosalind.${run}@example.org`,
      'TEL;TYPE=cell:+41 44 000 00 00',
      'BDAY:1990-03-14',
      'END:VCARD',
      '',
    ].join('\r\n');

    await page.goto('/contacts');
    await expect(page.getByRole('heading', { name: 'Contacts', level: 1 })).toBeVisible();

    // Import through the button's file chooser.
    const chooser = page.waitForEvent('filechooser');
    await page.getByRole('button', { name: 'Import vCard' }).click();
    await (
      await chooser
    ).setFiles({
      name: `friends-${run}.vcf`,
      mimeType: 'text/vcard',
      buffer: Buffer.from(vcard, 'utf-8'),
    });
    await expect(page.getByRole('status').filter({ hasText: 'imported' })).toContainText(
      `friends-${run}.vcf: 1 contact imported.`,
    );

    // The new contact is in the list and found by the search.
    await page.getByRole('searchbox', { name: 'Search contacts' }).fill(family);
    await expect(page).toHaveURL(new RegExp(`q=${family}`));
    const row = page.locator('.contact', { hasText: name });
    await expect(row).toHaveCount(1);
    await expect(row).toContainText('Researcher, Example Labs');
    await expect(row.locator('.monogram')).toHaveText('RT');

    // Its detail shows the fields, the related sections and the vCard export.
    await row.getByRole('link').click();
    await expect(page.getByRole('heading', { name, level: 1 })).toBeVisible();
    await expect(page.locator('.sync-status')).toHaveText('Kept in the cockpit only');
    await expect(page.locator('dd.email')).toContainText(`rosalind.${run}@example.org`);
    await expect(page.locator('dd.phone')).toContainText('+41 44 000 00 00');
    await expect(page.locator('dd.birthday')).toHaveText('March 14, 1990');
    await expect(page.getByRole('heading', { name: 'Waiting for' })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Upcoming' })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Recent mail' })).toBeVisible();
    const exportHref = await page.getByRole('link', { name: 'Export vCard' }).getAttribute('href');
    const exported = await page.request.get(exportHref!);
    expect(exported.ok()).toBe(true);
    const text = await exported.text();
    expect(text).toContain(`FN:${name}`);
    expect(text).toMatch(/BDAY[^:]*:1990-?03-?14/);

    // The birthday is a yearly all-day event of the Birthdays calendar.
    await page.goto('/calendar?view=month&date=2027-03-01');
    const birthday = page
      .locator('.app-calendar [data-date="2027-03-14"]')
      .locator('.app-event', { hasText: name });
    await expect(birthday).toHaveCount(1);
    await expect(birthday).toContainText(`${name} (37)`);
    await expect(
      page.locator('.app-calendar [data-date="2027-03-15"] .app-event', { hasText: name }),
    ).toHaveCount(0);
  });
});
