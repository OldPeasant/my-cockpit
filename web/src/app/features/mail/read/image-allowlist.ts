import { computed, inject, Injectable } from '@angular/core';
import { SettingsService } from '../../settings/settings.service';

/**
 * `localStorage` key of the per-browser sender list the read view used before the list moved to
 * the synced settings; its entries are merged into `mailImageAllowedSenders` once, then removed.
 */
export const IMAGE_ALLOWLIST_KEY = 'cockpit.mail.loadImagesFrom';

/**
 * Whether the read view loads remote images without asking (SPEC.md §4.1, §4.7): the synced
 * settings `mailImagePolicy` (`allow` loads every mail's images) and `mailImageAllowedSenders`
 * (sender addresses or domains, kept lower case). Changes go through {@link SettingsService}, one
 * after the other, so each starts from the list the previous one saved.
 */
@Injectable({ providedIn: 'root' })
export class ImageAllowlist {
  private readonly settings = inject(SettingsService);
  private chain: Promise<unknown> = Promise.resolve();
  private migration: Promise<void> | null = null;

  /** `allow` when every mail loads its remote images; `block` (the default) until loaded. */
  readonly policy = computed(() => this.settings.settings()?.mailImagePolicy ?? 'block');
  /** The allowed senders; empty until the settings are loaded. */
  readonly senders = computed(() => this.settings.settings()?.mailImageAllowedSenders ?? []);

  /**
   * Loads the settings unless they are loaded and merges the old `localStorage` list into them
   * (once). Never fails: without settings, images stay blocked.
   */
  async ready(): Promise<void> {
    try {
      if (!this.settings.settings()) await this.settings.load();
    } catch {
      return;
    }
    this.migration ??= this.migrate();
    await this.migration;
  }

  /** Whether the sender is in the allowed senders (by address or by domain). */
  has(address: string | null | undefined): boolean {
    return senderListed(this.senders(), address);
  }

  /** Whether a mail from this sender loads its remote images without asking. */
  loadsImagesFrom(address: string | null | undefined): boolean {
    return this.policy() === 'allow' || this.has(address);
  }

  /** Adds the sender to the allowed senders; rejects (rolled back) when the save fails. */
  add(address: string): Promise<unknown> {
    return this.change((senders) =>
      senderListed(senders, address) ? senders : [...senders, normalize(address)],
    );
  }

  /**
   * Removes every entry that lets this sender's images load (its address and its domain), so the
   * images are asked for again.
   */
  remove(address: string): Promise<unknown> {
    return this.change((senders) => senders.filter((entry) => !entryMatches(entry, address)));
  }

  /** Removes exactly this entry of the list (as the settings show it). */
  removeEntry(entry: string): Promise<unknown> {
    return this.change((senders) => senders.filter((other) => other !== entry));
  }

  private change(next: (senders: readonly string[]) => readonly string[]): Promise<unknown> {
    const result = this.chain.then(async () => {
      await this.ready();
      // Without loaded settings the list would be built from an empty one and replace the stored list.
      if (!this.settings.settings()) throw new Error('The settings could not be loaded.');
      const current = this.senders();
      const changed = next(current);
      if (sameList(changed, current)) return;
      await this.settings.update({ mailImageAllowedSenders: [...changed] });
    });
    this.chain = result.catch(() => undefined);
    return result;
  }

  private async migrate(): Promise<void> {
    const old = storedSenders();
    if (old === null) return;
    const merged = [...this.senders()];
    for (const address of old) if (!senderListed(merged, address)) merged.push(address);
    try {
      if (merged.length !== this.senders().length) {
        await this.settings.update({ mailImageAllowedSenders: merged });
      }
      localStorage.removeItem(IMAGE_ALLOWLIST_KEY);
    } catch {
      // Not saved: the old list stays, so the next page load tries again.
      this.migration = null;
    }
  }
}

/** Whether `address` matches an entry of `senders`: the same address, or its domain. */
export function senderListed(
  senders: readonly string[],
  address: string | null | undefined,
): boolean {
  return !!address?.trim() && senders.some((entry) => entryMatches(entry, address));
}

/** An entry with `@` and a local part is an address; otherwise (`example.test`, `@example.test`) a domain. */
function entryMatches(entry: string, address: string): boolean {
  const sender = normalize(address);
  const wanted = normalize(entry);
  if (!wanted || !sender) return false;
  if (wanted === sender) return true;
  const domain = wanted.startsWith('@') ? wanted.slice(1) : wanted.includes('@') ? null : wanted;
  return !!domain && sender.slice(sender.lastIndexOf('@') + 1) === domain;
}

function normalize(address: string): string {
  return address.trim().toLowerCase();
}

function sameList(a: readonly string[], b: readonly string[]): boolean {
  return a.length === b.length && a.every((entry, i) => entry === b[i]);
}

/**
 * The old per-browser list, or `null` when there is none or storage cannot be read (then nothing
 * is removed and the next page load tries again); `[]` when its content is not a list.
 */
function storedSenders(): string[] | null {
  let text: string | null;
  try {
    text = localStorage.getItem(IMAGE_ALLOWLIST_KEY);
  } catch {
    return null;
  }
  if (text === null) return null;
  try {
    const value: unknown = JSON.parse(text);
    return Array.isArray(value)
      ? value
          .filter((entry): entry is string => typeof entry === 'string' && !!entry.trim())
          .map(normalize)
      : [];
  } catch {
    return [];
  }
}
