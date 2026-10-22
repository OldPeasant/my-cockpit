import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ContactRequest } from '../../api/generated';
import { ContactForm } from './contact-form';
import { contact } from './testing';

const ID = '0192f0c4-0000-7000-8000-000000000001';

describe('ContactForm', () => {
  let fixture: ComponentFixture<ContactForm>;
  let root: HTMLElement;
  let saved: ContactRequest[];
  let cancelled: number;

  async function create(inputs: Record<string, unknown> = {}): Promise<void> {
    fixture = TestBed.createComponent(ContactForm);
    for (const [name, value] of Object.entries(inputs)) fixture.componentRef.setInput(name, value);
    saved = [];
    cancelled = 0;
    fixture.componentInstance.save.subscribe((request) => saved.push(request));
    fixture.componentInstance.cancelled.subscribe(() => cancelled++);
    root = fixture.nativeElement as HTMLElement;
    await render();
  }

  async function render(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
  }

  async function click(selector: string, index = 0): Promise<void> {
    (root.querySelectorAll(selector)[index] as HTMLButtonElement).click();
    await render();
  }

  async function type(selector: string, value: string, index = 0): Promise<void> {
    const input = root.querySelectorAll(selector)[index] as HTMLInputElement;
    input.value = value;
    input.dispatchEvent(new Event('input'));
    await render();
  }

  async function submit(): Promise<void> {
    (root.querySelector('form') as HTMLFormElement).dispatchEvent(new Event('submit'));
    await render();
  }

  function values(selector: string): string[] {
    return [...root.querySelectorAll(selector)].map((el) => (el as HTMLInputElement).value);
  }

  function text(el: Element | null): string {
    return el?.textContent?.replace(/\s+/g, ' ').trim() ?? '';
  }

  it('adds email and phone rows with labels and removes one in the middle', async () => {
    await create();
    expect(text(root.querySelector('button.save'))).toBe('Create contact');
    await type('input[name="givenName"]', 'Grace');
    await click('.add-email');
    await click('.add-email');
    await click('.add-email');
    await type('.emails .value', 'one@example.org', 0);
    await type('.emails .value', 'two@example.org', 1);
    await type('.emails .value', 'three@example.org', 2);
    await type('.emails .row-label', 'home', 2);
    await click('.emails .remove', 1);
    expect(values('.emails .value')).toEqual(['one@example.org', 'three@example.org']);
    expect(values('.emails .row-label')).toEqual(['', 'home']);

    await click('.add-phone');
    await type('.phones .value', '+41 44 000 00 00');
    await type('.phones .row-label', 'mobile');
    await submit();
    expect(saved).toEqual([
      {
        givenName: 'Grace',
        emails: [{ address: 'one@example.org' }, { address: 'three@example.org', label: 'home' }],
        phones: [{ number: '+41 44 000 00 00', label: 'mobile' }],
      },
    ]);
  });

  it('edits every part of an address row and leaves out an empty one', async () => {
    await create();
    await type('input[name="organization"]', 'Example AG');
    await click('.add-address');
    await click('.add-address');
    expect(root.querySelectorAll('.addresses .address')).toHaveLength(2);
    await type('.addresses .street', 'Sample St 1');
    await type('.addresses .postal', '8000');
    await type('.addresses .city', 'Zurich');
    await type('.addresses .region', 'ZH');
    await type('.addresses .country', 'Switzerland');
    await type('.addresses .row-label', 'office');
    await submit();
    expect(saved).toEqual([
      {
        organization: 'Example AG',
        addresses: [
          {
            street: 'Sample St 1',
            postalCode: '8000',
            city: 'Zurich',
            region: 'ZH',
            country: 'Switzerland',
            label: 'office',
          },
        ],
      },
    ]);
  });

  it('removes an existing address and phone of a contact and sends the emptied lists', async () => {
    await create({
      contact: contact(ID, {
        phones: [{ number: '+41 44 000 00 00' }],
        addresses: [{ city: 'Zurich' }, { city: 'Bern', label: 'home' }],
      }),
    });
    expect(text(root.querySelector('button.save'))).toBe('Save changes');
    expect(values('.addresses .city')).toEqual(['Zurich', 'Bern']);
    await click('.addresses .remove', 0);
    expect(values('.addresses .city')).toEqual(['Bern']);
    expect(values('.addresses .row-label')).toEqual(['home']);
    await click('.phones .remove');
    await submit();
    expect(saved).toHaveLength(1);
    expect(saved[0]).toMatchObject({
      givenName: 'Ada',
      emails: [{ address: 'ada@example.org', label: 'work' }],
      phones: [],
      addresses: [{ city: 'Bern', label: 'home' }],
      title: null,
    });
  });

  it('asks for a name, and forgets the hint once something is typed', async () => {
    await create({ error: 'Changed elsewhere' });
    expect(text(root.querySelector('.form-error'))).toBe(
      'The contact could not be saved: Changed elsewhere',
    );
    await click('.add-email');
    await type('.emails .row-label', 'work');
    await submit();
    expect(saved).toEqual([]);
    expect(text(root.querySelector('.form-error'))).toBe(
      'Enter a name, an organization or an email address.',
    );
    await type('input[name="familyName"]', 'Example');
    expect(text(root.querySelector('.form-error'))).toBe(
      'The contact could not be saved: Changed elsewhere',
    );
  });

  it('accepts an email address alone as the name of a new contact', async () => {
    await create();
    await click('.add-email');
    await type('.emails .value', ' ada@example.org ');
    await submit();
    expect(saved).toEqual([{ emails: [{ address: 'ada@example.org' }] }]);
  });

  it('does not save while busy and shows it', async () => {
    await create({ contact: contact(ID), busy: true });
    const save = root.querySelector('button.save') as HTMLButtonElement;
    expect(text(save)).toBe('Saving…');
    expect(save.disabled).toBe(true);
    expect((root.querySelector('button.cancel') as HTMLButtonElement).disabled).toBe(true);
    await submit();
    expect(saved).toEqual([]);
  });

  it('emits cancel', async () => {
    await create();
    await click('button.cancel');
    expect(cancelled).toBe(1);
    expect(saved).toEqual([]);
  });

  it('refills the form with a reloaded contact, dropping unsaved edits', async () => {
    await create({ contact: contact(ID, { title: 'Engineer' }) });
    await type('input[name="title"]', 'Draft title');
    await click('.add-phone');
    fixture.componentRef.setInput('contact', contact(ID, { title: 'Lead', version: 2 }));
    await render();
    expect(values('input[name="title"]')).toEqual(['Lead']);
    expect(root.querySelectorAll('.phones .row')).toHaveLength(0);
  });
});
