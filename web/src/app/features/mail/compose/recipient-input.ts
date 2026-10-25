import { COMMA, ENTER, SEMICOLON } from '@angular/cdk/keycodes';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  inject,
  input,
  model,
  signal,
  viewChild,
} from '@angular/core';
import {
  MatAutocompleteModule,
  MatAutocompleteSelectedEvent,
  MatAutocompleteTrigger,
} from '@angular/material/autocomplete';
import { MatChipInputEvent, MatChipsModule } from '@angular/material/chips';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MailComposeService, RecipientSuggestion } from './mail-compose.service';

/** A recipient of the draft. */
export interface Recipient {
  address: string;
  name?: string;
}

/** Wait this long after the last keystroke before asking for suggestions. */
export const SUGGEST_DEBOUNCE_MS = 200;

/** A bare `local@domain` address: what the server accepts as a recipient. */
const ADDRESS = /^[^\s@<>(),;:"]+@[^\s@<>(),;:"]+\.[^\s@<>(),;:"]+$/;

/** Whether `address` is a plain email address. */
export function isValidAddress(address: string): boolean {
  return ADDRESS.test(address);
}

/**
 * Parses typed or pasted text into recipients: `ann@example.com`, `Ann <ann@example.com>` or
 * several of them separated by commas, semicolons or line breaks.
 */
export function parseRecipients(text: string): Recipient[] {
  return text
    .split(/[,;\n]+/)
    .map((part) => part.trim())
    .filter(Boolean)
    .map((part) => {
      const angle = /^(.*)<([^>]*)>$/.exec(part);
      if (!angle) return { address: part };
      const name = angle[1].trim().replace(/^"(.*)"$/, '$1');
      return name ? { address: angle[2].trim(), name } : { address: angle[2].trim() };
    });
}

/**
 * A recipient field (To, Cc or Bcc): the recipients as chips, an input that turns typed addresses
 * into chips (Enter, comma, semicolon, leaving the field, pasting a list) and suggestions while
 * typing: contacts (marked with a person icon) first, then recent correspondents. Invalid addresses stay as chips marked invalid, so they can be fixed.
 */
@Component({
  selector: 'app-recipient-input',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatAutocompleteModule, MatChipsModule, MatFormFieldModule],
  template: `
    <mat-form-field class="field" subscriptSizing="dynamic">
      <mat-label>{{ label() }}</mat-label>
      <mat-chip-grid #grid [attr.aria-label]="label()">
        @for (recipient of recipients(); track $index) {
          <mat-chip-row
            [class.invalid]="!valid(recipient)"
            [attr.title]="recipient.address"
            (removed)="remove($index)"
          >
            {{ recipient.name || recipient.address }}
            @if (!valid(recipient)) {
              <span class="cdk-visually-hidden" i18n="@@mailCompose.invalidAddress"
                >(invalid address)</span
              >
            }
            <button matChipRemove [attr.aria-label]="removeLabel(recipient)">
              <svg viewBox="0 0 24 24" aria-hidden="true">
                <path
                  d="M19 6.41 17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z"
                />
              </svg>
            </button>
          </mat-chip-row>
        }
        <input
          #field
          type="email"
          multiple
          autocomplete="off"
          [matChipInputFor]="grid"
          [matChipInputSeparatorKeyCodes]="separators"
          [matChipInputAddOnBlur]="true"
          [matAutocomplete]="auto"
          (matChipInputTokenEnd)="addTyped($event)"
          (input)="typed(field.value)"
          (paste)="pasted($event)"
        />
      </mat-chip-grid>
      <mat-autocomplete
        #auto="matAutocomplete"
        [autoActiveFirstOption]="true"
        (optionSelected)="choose($event)"
      >
        @for (suggestion of suggestions(); track suggestion.address) {
          <mat-option [value]="suggestion" [class.contact]="!!suggestion.contactId">
            @if (suggestion.contactId) {
              <svg class="kind" viewBox="0 0 24 24" aria-hidden="true">
                <path
                  d="M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z"
                />
              </svg>
              <span class="cdk-visually-hidden" i18n="@@mailCompose.contactSuggestion"
                >Contact:</span
              >
            }
            @if (suggestion.name) {
              <span class="name">{{ suggestion.name }}</span>
            }
            <span class="address">{{ suggestion.address }}</span>
          </mat-option>
        }
      </mat-autocomplete>
    </mat-form-field>
  `,
  styles: `
    :host {
      display: block;
    }
    .field {
      width: 100%;
    }
    .invalid {
      --mat-chip-elevated-container-color: var(--mat-sys-error-container);
      --mat-chip-label-text-color: var(--mat-sys-on-error-container);
      text-decoration: wavy underline var(--mat-sys-error);
    }
    svg {
      width: 18px;
      height: 18px;
      fill: currentColor;
    }
    .kind {
      margin-right: var(--app-space-2);
      vertical-align: middle;
      fill: var(--app-fg-muted);
    }
    .name {
      margin-right: var(--app-space-2);
    }
    .address {
      color: var(--app-fg-muted);
    }
  `,
})
export class RecipientInput {
  private readonly service = inject(MailComposeService);

  readonly label = input.required<string>();
  readonly recipients = model<Recipient[]>([]);

  protected readonly separators = [ENTER, COMMA, SEMICOLON] as const;
  protected readonly suggestions = signal<RecipientSuggestion[]>([]);

  private readonly field = viewChild.required<ElementRef<HTMLInputElement>>('field');
  private readonly trigger = viewChild.required(MatAutocompleteTrigger);
  private timer?: ReturnType<typeof setTimeout>;
  /** Counts the lookups: only the latest one may show its suggestions. */
  private seq = 0;

  constructor() {
    inject(DestroyRef).onDestroy(() => clearTimeout(this.timer));
  }

  protected valid(recipient: Recipient): boolean {
    return isValidAddress(recipient.address);
  }

  protected removeLabel(recipient: Recipient): string {
    const address = recipient.address;
    return $localize`:@@mailCompose.removeRecipient:Remove ${address}:address:`;
  }

  protected remove(index: number): void {
    this.recipients.update((list) => list.filter((_, i) => i !== index));
  }

  protected addTyped(event: MatChipInputEvent): void {
    // Enter (or a click) on a suggestion: the suggestion is added instead of the typed text.
    const trigger = this.trigger();
    if (trigger.panelOpen && trigger.activeOption) return;
    this.add(parseRecipients(event.value));
    event.chipInput.clear();
    this.suggestions.set([]);
  }

  protected pasted(event: ClipboardEvent): void {
    const text = event.clipboardData?.getData('text') ?? '';
    if (!/[,;\n]/.test(text)) return;
    event.preventDefault();
    this.add(parseRecipients(text));
  }

  protected choose(event: MatAutocompleteSelectedEvent): void {
    const suggestion = event.option.value as RecipientSuggestion;
    const { address, name } = suggestion;
    this.add([name ? { address, name } : { address }]);
    this.field().nativeElement.value = '';
    this.suggestions.set([]);
  }

  /** Looks up suggestions for the text typed so far, once typing pauses. */
  protected typed(text: string): void {
    clearTimeout(this.timer);
    const query = text.trim();
    const seq = ++this.seq;
    if (!query) {
      this.suggestions.set([]);
      return;
    }
    this.timer = setTimeout(() => {
      this.service.suggestions(query).then(
        (found) => {
          if (seq !== this.seq) return;
          const taken = new Set(this.recipients().map((r) => r.address.toLowerCase()));
          this.suggestions.set(found.filter((s) => !taken.has(s.address.toLowerCase())));
        },
        // Suggestions are a help: without them, addresses can still be typed.
        () => seq === this.seq && this.suggestions.set([]),
      );
    }, SUGGEST_DEBOUNCE_MS);
  }

  private add(added: Recipient[]): void {
    if (!added.length) return;
    this.seq++;
    clearTimeout(this.timer);
    const taken = new Set(this.recipients().map((r) => r.address.toLowerCase()));
    const fresh = added.filter((r) => {
      const key = r.address.toLowerCase();
      if (taken.has(key)) return false;
      taken.add(key);
      return true;
    });
    if (fresh.length) this.recipients.update((list) => [...list, ...fresh]);
  }
}
