import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NonNullableFormBuilder } from '@angular/forms';
import { MailServerResponse } from '../../../api/generated';
import { Protocol, ServerForm, serverForm, serverValue } from './account-form';
import { ServerFields } from './server-fields';

const tick = (): Promise<void> => new Promise((resolve) => setTimeout(resolve));

describe('ServerFields', () => {
  let fixture: ComponentFixture<ServerFields>;
  let root: HTMLElement;
  let form: ServerForm;

  async function setup(protocol: Protocol, server?: MailServerResponse): Promise<void> {
    form = serverForm(TestBed.inject(NonNullableFormBuilder), protocol, server);
    fixture = TestBed.createComponent(ServerFields);
    fixture.componentRef.setInput('form', form);
    fixture.componentRef.setInput('protocol', protocol);
    root = fixture.nativeElement as HTMLElement;
    await settle();
  }

  async function settle(): Promise<void> {
    await tick();
    fixture.detectChanges();
    await fixture.whenStable();
  }

  function port(): HTMLInputElement {
    return root.querySelector<HTMLInputElement>('[formControlName="port"]')!;
  }

  async function chooseSecurity(label: string): Promise<void> {
    root.querySelector<HTMLElement>('mat-select')!.click();
    await settle();
    const option = [...document.querySelectorAll<HTMLElement>('mat-option')].find(
      (o) => o.textContent?.trim() === label,
    );
    if (!option) throw new Error(`No option "${label}"`);
    option.click();
    await settle();
  }

  it('starts IMAP with SSL/TLS on 993 and SMTP with STARTTLS on 587', async () => {
    await setup('imap');
    expect(form.getRawValue()).toEqual({ host: '', port: 993, security: 'SSL_TLS' });
    expect(root.querySelector('legend')?.textContent).toContain('Incoming mail (IMAP)');

    TestBed.resetTestingModule();
    await setup('smtp');
    expect(form.getRawValue()).toEqual({ host: '', port: 587, security: 'STARTTLS' });
    expect(root.querySelector('legend')?.textContent).toContain('Outgoing mail (SMTP)');
  });

  it.each([
    ['imap', 'STARTTLS', '143'],
    ['imap', 'None (unencrypted)', '143'],
    ['smtp', 'SSL/TLS', '465'],
    ['smtp', 'None (unencrypted)', '25'],
  ] as const)('moves the usual %s port along to %s', async (protocol, security, expected) => {
    await setup(protocol);

    await chooseSecurity(security);

    expect(port().value).toBe(expected);
  });

  it('moves a usual port back when the security goes back', async () => {
    await setup('imap');

    await chooseSecurity('STARTTLS');
    await chooseSecurity('SSL/TLS');

    expect(form.getRawValue().port).toBe(993);
  });

  it('keeps an unusual port when the security changes', async () => {
    await setup('smtp', { host: 'smtp.mail.example.test', port: 2525, security: 'STARTTLS' });

    await chooseSecurity('SSL/TLS');

    expect(port().value).toBe('2525');
    expect(form.getRawValue().security).toBe('SSL_TLS');
  });

  it('keeps a port the user typed', async () => {
    await setup('imap');
    port().value = '10993';
    port().dispatchEvent(new Event('input'));

    await chooseSecurity('STARTTLS');

    expect(port().value).toBe('10993');
  });

  it.each([
    ['0', false],
    ['1', true],
    ['65535', true],
    ['65536', false],
    ['', false],
  ])('checks the port %j (valid: %s)', async (value, valid) => {
    await setup('imap', { host: 'imap.mail.example.test', port: 993, security: 'SSL_TLS' });

    port().value = value;
    port().dispatchEvent(new Event('input'));
    form.markAllAsTouched();
    await settle();

    expect(form.valid).toBe(valid);
    expect(root.textContent?.includes('1 to 65535.')).toBe(!valid);
  });

  it('needs a host and sends it trimmed', async () => {
    await setup('smtp');
    form.markAllAsTouched();
    await settle();
    expect(root.textContent).toContain('Enter the server name.');

    const host = root.querySelector<HTMLInputElement>('[formControlName="host"]')!;
    host.value = '  smtp.mail.example.test ';
    host.dispatchEvent(new Event('input'));
    await settle();

    expect(root.textContent).not.toContain('Enter the server name.');
    expect(serverValue(form)).toEqual({
      host: 'smtp.mail.example.test',
      port: 587,
      security: 'STARTTLS',
    });
  });
});
