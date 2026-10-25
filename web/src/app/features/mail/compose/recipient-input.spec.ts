import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MailComposeService, RecipientSuggestion } from './mail-compose.service';
import {
  isValidAddress,
  parseRecipients,
  Recipient,
  RecipientInput,
  SUGGEST_DEBOUNCE_MS,
} from './recipient-input';

describe('parseRecipients', () => {
  it('reads bare addresses, names in angle brackets and lists', () => {
    expect(
      parseRecipients('ann@example.test, "Bob B." <bob@example.test>; Cleo <cleo@example.test>\n'),
    ).toEqual([
      { address: 'ann@example.test' },
      { address: 'bob@example.test', name: 'Bob B.' },
      { address: 'cleo@example.test', name: 'Cleo' },
    ]);
  });

  it('ignores empty parts', () => {
    expect(parseRecipients(' , ;')).toEqual([]);
  });
});

describe('isValidAddress', () => {
  it.each(['ann@example.test', 'a.b+c@sub.example.test'])('accepts %s', (address) => {
    expect(isValidAddress(address)).toBe(true);
  });

  it.each(['ann', 'ann@', '@example.test', 'ann@example', 'Ann <ann@example.test>', 'a b@x.test'])(
    'rejects %s',
    (address) => {
      expect(isValidAddress(address)).toBe(false);
    },
  );
});

@Component({
  imports: [RecipientInput],
  template: `<app-recipient-input label="To" [(recipients)]="recipients" />`,
})
class Host {
  readonly recipients = signal<Recipient[]>([]);
}

class FakeSuggestions {
  queries: string[] = [];
  result: RecipientSuggestion[] = [];
  suggestions(query: string) {
    this.queries.push(query);
    return Promise.resolve(this.result);
  }
}

describe('RecipientInput', () => {
  let fixture: ComponentFixture<Host>;
  let service: FakeSuggestions;

  beforeEach(async () => {
    vi.useFakeTimers();
    service = new FakeSuggestions();
    TestBed.configureTestingModule({
      providers: [{ provide: MailComposeService, useValue: service }],
    });
    fixture = TestBed.createComponent(Host);
    fixture.detectChanges();
    await fixture.whenStable();
  });

  afterEach(() => {
    fixture.destroy();
    vi.useRealTimers();
  });

  const input = () => fixture.nativeElement.querySelector('input') as HTMLInputElement;
  const inner = () =>
    fixture.debugElement.query((d) => d.componentInstance instanceof RecipientInput)
      .componentInstance as { suggestions: () => RecipientSuggestion[] };
  const chips = () =>
    [...(fixture.nativeElement as HTMLElement).querySelectorAll('mat-chip-row')].map((chip) => ({
      text: chip.textContent?.trim(),
      invalid: chip.classList.contains('invalid'),
    }));

  function type(text: string) {
    input().value = text;
    input().dispatchEvent(new Event('input'));
  }

  function enter() {
    input().dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', keyCode: 13 }));
    fixture.detectChanges();
  }

  it('turns a typed address into a chip on Enter', () => {
    type('ann@example.test');
    enter();
    expect(fixture.componentInstance.recipients()).toEqual([{ address: 'ann@example.test' }]);
    expect(chips()).toEqual([
      { text: expect.stringContaining('ann@example.test'), invalid: false },
    ]);
    expect(input().value).toBe('');
  });

  it('keeps an invalid address as a chip marked invalid', () => {
    type('not-an-address');
    enter();
    expect(chips()).toEqual([
      { text: expect.stringContaining('(invalid address)'), invalid: true },
    ]);
  });

  it('adds every recipient of a pasted list, once each', () => {
    fixture.componentInstance.recipients.set([{ address: 'ann@example.test' }]);
    fixture.detectChanges();
    const paste = new Event('paste', { cancelable: true }) as ClipboardEvent;
    Object.defineProperty(paste, 'clipboardData', {
      value: { getData: () => 'ANN@example.test, Bob <bob@example.test>' },
    });
    input().dispatchEvent(paste);
    fixture.detectChanges();
    expect(paste.defaultPrevented).toBe(true);
    expect(fixture.componentInstance.recipients()).toEqual([
      { address: 'ann@example.test' },
      { address: 'bob@example.test', name: 'Bob' },
    ]);
    expect(chips().map((c) => c.text)).toEqual([
      expect.stringContaining('ann@example.test'),
      expect.stringContaining('Bob'),
    ]);
  });

  it('removes a chip with its remove button', () => {
    fixture.componentInstance.recipients.set([
      { address: 'ann@example.test' },
      { address: 'bob@example.test' },
    ]);
    fixture.detectChanges();
    const remove = fixture.nativeElement.querySelector(
      'button[aria-label="Remove ann@example.test"]',
    ) as HTMLButtonElement;
    remove.click();
    fixture.detectChanges();
    expect(fixture.componentInstance.recipients()).toEqual([{ address: 'bob@example.test' }]);
  });

  it('asks for suggestions once typing pauses, without recipients already added', async () => {
    fixture.componentInstance.recipients.set([{ address: 'ann@example.test' }]);
    service.result = [
      { address: 'ann@example.test', name: 'Ann' },
      { address: 'anna@example.test', name: 'Anna' },
    ];
    type('a');
    await vi.advanceTimersByTimeAsync(SUGGEST_DEBOUNCE_MS / 2);
    type('an');
    await vi.advanceTimersByTimeAsync(SUGGEST_DEBOUNCE_MS - 1);
    expect(service.queries).toEqual([]);
    await vi.advanceTimersByTimeAsync(1);
    expect(service.queries).toEqual(['an']);
    expect(inner().suggestions()).toEqual([{ address: 'anna@example.test', name: 'Anna' }]);
  });

  it('adds a chosen suggestion with its name', async () => {
    service.result = [{ address: 'anna@example.test', name: 'Anna' }];
    input().focus();
    type('ann');
    await vi.advanceTimersByTimeAsync(SUGGEST_DEBOUNCE_MS);
    fixture.detectChanges();
    await fixture.whenStable();
    const option = document.querySelector('mat-option') as HTMLElement;
    expect(option.textContent).toContain('anna@example.test');
    option.click();
    fixture.detectChanges();
    expect(fixture.componentInstance.recipients()).toEqual([
      { address: 'anna@example.test', name: 'Anna' },
    ]);
    expect(input().value).toBe('');
  });

  it('marks contact suggestions and adds a chosen contact as name and address only', async () => {
    service.result = [
      { address: 'ann@example.test', name: 'Ann Example', contactId: 'c-1' },
      { address: 'anna@example.test', name: 'Anna' },
    ];
    input().focus();
    type('ann');
    await vi.advanceTimersByTimeAsync(SUGGEST_DEBOUNCE_MS);
    fixture.detectChanges();
    await fixture.whenStable();
    const options = Array.from(document.querySelectorAll('mat-option')) as HTMLElement[];
    expect(options.map((o) => o.classList.contains('contact'))).toEqual([true, false]);
    expect(options[0].querySelector('svg.kind')).not.toBeNull();
    expect(options[0].textContent).toContain('Contact:');
    expect(options[1].querySelector('svg.kind')).toBeNull();
    options[0].click();
    fixture.detectChanges();
    expect(fixture.componentInstance.recipients()).toEqual([
      { address: 'ann@example.test', name: 'Ann Example' },
    ]);
  });

  it('announces contacts to screen readers only and hides the icon from them', async () => {
    service.result = [
      { address: 'ann@example.test', name: 'Ann Example', contactId: 'c-1' },
      { address: 'anna@example.test', name: 'Anna' },
    ];
    input().focus();
    type('ann');
    await vi.advanceTimersByTimeAsync(SUGGEST_DEBOUNCE_MS);
    fixture.detectChanges();
    await fixture.whenStable();
    const options = Array.from(document.querySelectorAll('mat-option')) as HTMLElement[];
    const label = options[0].querySelector('.cdk-visually-hidden');
    expect(label?.textContent?.trim()).toBe('Contact:');
    expect(options[0].querySelector('svg.kind')?.getAttribute('aria-hidden')).toBe('true');
    expect(options[1].textContent).not.toContain('Contact:');
  });

  it('adds a chosen contact without name as its address only', async () => {
    service.result = [{ address: 'bo@example.test', name: '', contactId: 'c-2' }];
    input().focus();
    type('bo');
    await vi.advanceTimersByTimeAsync(SUGGEST_DEBOUNCE_MS);
    fixture.detectChanges();
    await fixture.whenStable();
    const option = document.querySelector('mat-option') as HTMLElement;
    expect(option.classList.contains('contact')).toBe(true);
    option.click();
    fixture.detectChanges();
    expect(fixture.componentInstance.recipients()).toEqual([{ address: 'bo@example.test' }]);
  });

  it('hides a contact suggestion whose address is already a recipient, in any case', async () => {
    fixture.componentInstance.recipients.set([{ address: 'Ann@Example.test', name: 'Ann' }]);
    service.result = [
      { address: 'ann@example.test', name: 'Ann Example', contactId: 'c-1' },
      { address: 'ann@work.example.test', name: 'Ann Example', contactId: 'c-1' },
    ];
    type('ann');
    await vi.advanceTimersByTimeAsync(SUGGEST_DEBOUNCE_MS);
    expect(inner().suggestions()).toEqual([
      { address: 'ann@work.example.test', name: 'Ann Example', contactId: 'c-1' },
    ]);
  });

  it('adds no chip for an empty field or an address already there', () => {
    fixture.componentInstance.recipients.set([{ address: 'ann@example.test' }]);
    fixture.detectChanges();
    type('   ');
    enter();
    type('Ann@Example.test');
    enter();
    expect(fixture.componentInstance.recipients()).toEqual([{ address: 'ann@example.test' }]);
  });

  it('leaves pasting a single address to the input', () => {
    const paste = new Event('paste', { cancelable: true }) as ClipboardEvent;
    Object.defineProperty(paste, 'clipboardData', { value: { getData: () => 'ann@example.test' } });
    input().dispatchEvent(paste);
    expect(paste.defaultPrevented).toBe(false);
    expect(fixture.componentInstance.recipients()).toEqual([]);
  });

  it('shows no suggestions when looking them up fails', async () => {
    vi.spyOn(service, 'suggestions').mockRejectedValueOnce(new Error('offline'));
    type('ann');
    await vi.advanceTimersByTimeAsync(SUGGEST_DEBOUNCE_MS);
    expect(inner().suggestions()).toEqual([]);
  });

  it('shows only the suggestions of the latest text when answers arrive out of order', async () => {
    const answers: ((found: RecipientSuggestion[]) => void)[] = [];
    vi.spyOn(service, 'suggestions').mockImplementation(
      () => new Promise((resolve) => answers.push(resolve)),
    );
    type('a');
    await vi.advanceTimersByTimeAsync(SUGGEST_DEBOUNCE_MS);
    type('ann');
    await vi.advanceTimersByTimeAsync(SUGGEST_DEBOUNCE_MS);
    expect(answers).toHaveLength(2);

    answers[1]([{ address: 'anna@example.test', name: 'Anna' }]);
    await vi.advanceTimersByTimeAsync(0);
    answers[0]([{ address: 'alex@example.test', name: 'Alex' }]);
    await vi.advanceTimersByTimeAsync(0);
    expect(inner().suggestions()).toEqual([{ address: 'anna@example.test', name: 'Anna' }]);
  });

  it('drops a pending lookup once the typed address became a chip', async () => {
    let answer!: (found: RecipientSuggestion[]) => void;
    vi.spyOn(service, 'suggestions').mockImplementation(
      () => new Promise((resolve) => (answer = resolve)),
    );
    type('ann');
    await vi.advanceTimersByTimeAsync(SUGGEST_DEBOUNCE_MS);
    type('ann@example.test');
    enter();
    answer([{ address: 'anna@example.test', name: 'Anna' }]);
    await vi.advanceTimersByTimeAsync(SUGGEST_DEBOUNCE_MS);
    expect(inner().suggestions()).toEqual([]);
    expect(fixture.componentInstance.recipients()).toEqual([{ address: 'ann@example.test' }]);
  });

  it('adds the highlighted suggestion, not the typed text, on Enter', async () => {
    service.result = [{ address: 'anna@example.test', name: 'Anna' }];
    input().focus();
    type('ann');
    await vi.advanceTimersByTimeAsync(SUGGEST_DEBOUNCE_MS);
    fixture.detectChanges();
    await fixture.whenStable();
    // Let the panel open and highlight its first option.
    await vi.advanceTimersByTimeAsync(0);
    fixture.detectChanges();
    await fixture.whenStable();
    // Enter reaches the chip input and the autocomplete: only the highlighted suggestion is added.
    enter();
    await fixture.whenStable();
    expect(fixture.componentInstance.recipients()).toEqual([
      { address: 'anna@example.test', name: 'Anna' },
    ]);
  });

  it('clears the suggestions when the field is emptied', async () => {
    service.result = [{ address: 'anna@example.test', name: 'Anna' }];
    type('ann');
    await vi.advanceTimersByTimeAsync(SUGGEST_DEBOUNCE_MS);
    type('');
    await vi.advanceTimersByTimeAsync(SUGGEST_DEBOUNCE_MS);
    expect(service.queries).toEqual(['ann']);
    expect(inner().suggestions()).toEqual([]);
  });
});
