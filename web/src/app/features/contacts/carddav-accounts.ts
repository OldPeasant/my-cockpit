import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, inject, output, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { CardDavAccountResponse } from '../../api/generated';
import { ApiError } from '../../core/http';
import { ContactsService } from './contacts.service';

/** The fields of the add form. */
interface NewAccount {
  name: string;
  url: string;
  username: string;
  password: string;
}

const EMPTY: NewAccount = { name: '', url: '', username: '', password: '' };

/** How the server's field names of a 400 read in a message. */
const FIELD_LABELS: Record<string, string> = {
  name: $localize`:@@contacts.carddav.field.name:Name`,
  url: $localize`:@@contacts.carddav.field.url:Server URL`,
};

/**
 * The CardDAV accounts the contacts sync with (SPEC.md §8.3): their sync state, syncing one now,
 * removing one (its contacts stay) and adding one. Emits `changed` when the accounts changed, so
 * the page can offer them as a place for new contacts.
 */
@Component({
  selector: 'app-carddav-accounts',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe, MatButtonModule],
  templateUrl: './carddav-accounts.html',
  styleUrl: './carddav-accounts.scss',
})
export class CardDavAccounts {
  private readonly service = inject(ContactsService);

  readonly changed = output<CardDavAccountResponse[]>();

  /** The accounts; null while they load. */
  protected readonly accounts = signal<CardDavAccountResponse[] | null>(null);
  protected readonly loadError = signal<string | null>(null);

  /** Ids of the accounts being synced or removed. */
  protected readonly busy = signal<ReadonlySet<string>>(new Set());
  /** The account whose removal waits for confirmation. */
  protected readonly confirmRemove = signal<string | null>(null);
  /** An error of the last sync or removal, by account id. */
  protected readonly actionError = signal<{ id: string; message: string } | null>(null);

  protected readonly adding = signal(false);
  protected readonly form = signal<NewAccount>(EMPTY);
  protected readonly saving = signal(false);
  protected readonly addError = signal<string | null>(null);

  constructor() {
    void this.load();
  }

  protected async load(): Promise<void> {
    this.loadError.set(null);
    try {
      this.show(await this.service.carddavAccounts());
    } catch (error) {
      this.loadError.set(message(error));
    }
  }

  protected host(account: CardDavAccountResponse): string {
    try {
      return new URL(account.url).host;
    } catch {
      return account.url;
    }
  }

  protected isBusy(id: string): boolean {
    return this.busy().has(id);
  }

  protected async syncNow(account: CardDavAccountResponse): Promise<void> {
    if (this.isBusy(account.id)) return;
    this.setBusy(account.id, true);
    this.actionError.set(null);
    try {
      const synced = await this.service.syncCardDavAccount(account.id);
      this.show((this.accounts() ?? []).map((a) => (a.id === synced.id ? synced : a)));
    } catch (error) {
      this.actionError.set({ id: account.id, message: message(error) });
    } finally {
      this.setBusy(account.id, false);
    }
  }

  protected async remove(account: CardDavAccountResponse): Promise<void> {
    if (this.isBusy(account.id)) return;
    this.setBusy(account.id, true);
    this.actionError.set(null);
    try {
      await this.service.removeCardDavAccount(account.id);
      this.confirmRemove.set(null);
      this.show((this.accounts() ?? []).filter((a) => a.id !== account.id));
    } catch (error) {
      this.actionError.set({ id: account.id, message: message(error) });
    } finally {
      this.setBusy(account.id, false);
    }
  }

  protected startAdd(): void {
    this.form.set(EMPTY);
    this.addError.set(null);
    this.adding.set(true);
  }

  protected cancelAdd(): void {
    this.adding.set(false);
    this.addError.set(null);
  }

  protected setField(field: keyof NewAccount, event: Event): void {
    const value = (event.target as HTMLInputElement).value;
    this.form.update((form) => ({ ...form, [field]: value }));
  }

  protected async add(): Promise<void> {
    if (this.saving()) return;
    const form = this.form();
    if (!form.name.trim() || !form.url.trim()) {
      this.addError.set($localize`:@@contacts.carddav.missing:Enter a name and the server URL.`);
      return;
    }
    this.saving.set(true);
    this.addError.set(null);
    try {
      const added = await this.service.addCardDavAccount({
        name: form.name.trim(),
        url: form.url.trim(),
        ...(form.username.trim() ? { username: form.username.trim() } : {}),
        ...(form.password ? { password: form.password } : {}),
      });
      this.adding.set(false);
      this.form.set(EMPTY);
      this.show([...(this.accounts() ?? []), added]);
      // The first sync finds the address book: its outcome shows at once.
      void this.syncNow(added);
    } catch (error) {
      this.addError.set(message(error));
    } finally {
      this.saving.set(false);
    }
  }

  private show(accounts: CardDavAccountResponse[]): void {
    this.accounts.set(accounts);
    this.changed.emit(accounts);
  }

  private setBusy(id: string, on: boolean): void {
    this.busy.update((ids) => {
      const next = new Set(ids);
      if (on) next.add(id);
      else next.delete(id);
      return next;
    });
  }
}

function message(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 400 && error.errors.length) {
      return error.errors.map((e) => `${FIELD_LABELS[e.field] ?? e.field} ${e.message}`).join('; ');
    }
    return error.userMessage;
  }
  return $localize`:@@contacts.error:Something went wrong.`;
}
