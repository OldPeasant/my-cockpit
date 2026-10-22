import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  input,
  output,
  signal,
  untracked,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { ContactRequest, ContactResponse } from '../../api/generated';
import {
  AddressRow,
  ContactFormValue,
  EMPTY_ADDRESS,
  hasName,
  toForm,
  toRequest,
} from './contact-form-model';

type TextField = Exclude<keyof ContactFormValue, 'emails' | 'phones' | 'addresses'>;
type ListField = 'emails' | 'phones' | 'addresses';

/**
 * The fields of a contact to create (`contact` null) or change. Emits the request to send; the
 * page saves it and shows `error`.
 */
@Component({
  selector: 'app-contact-form',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule],
  templateUrl: './contact-form.html',
  styleUrl: './contact-form.scss',
})
export class ContactForm {
  readonly contact = input<ContactResponse | null>(null);
  readonly busy = input(false);
  readonly error = input<string | null>(null);

  readonly save = output<ContactRequest>();
  readonly cancelled = output<void>();

  protected readonly form = signal<ContactFormValue>(toForm(null));
  protected readonly missingName = signal(false);
  protected readonly isNew = computed(() => this.contact() === null);

  constructor() {
    // A contact reloaded after a conflict refills the form.
    effect(() => {
      const contact = this.contact();
      untracked(() => {
        this.form.set(toForm(contact));
        this.missingName.set(false);
      });
    });
  }

  protected setText(field: TextField, event: Event): void {
    const value = (event.target as HTMLInputElement | HTMLTextAreaElement).value;
    this.form.update((form) => ({ ...form, [field]: value }));
    this.missingName.set(false);
  }

  protected setRow(list: ListField, index: number, key: string, event: Event): void {
    const value = (event.target as HTMLInputElement).value;
    this.form.update((form) => ({
      ...form,
      [list]: form[list].map((row, i) => (i === index ? { ...row, [key]: value } : row)),
    }));
    this.missingName.set(false);
  }

  protected addRow(list: ListField): void {
    const row =
      list === 'emails'
        ? { address: '', label: '' }
        : list === 'phones'
          ? { number: '', label: '' }
          : ({ ...EMPTY_ADDRESS } satisfies AddressRow);
    this.form.update((form) => ({ ...form, [list]: [...form[list], row] }));
  }

  protected removeRow(list: ListField, index: number): void {
    this.form.update((form) => ({
      ...form,
      [list]: form[list].filter((_, i) => i !== index),
    }));
  }

  protected submit(): void {
    if (this.busy()) return;
    const form = this.form();
    if (!hasName(form)) {
      this.missingName.set(true);
      return;
    }
    this.save.emit(toRequest(form, !this.isNew()));
  }
}
