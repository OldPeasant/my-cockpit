import { Injectable, signal } from '@angular/core';

/** `localStorage` key of the senders whose remote images load without asking. */
export const IMAGE_ALLOWLIST_KEY = 'cockpit.mail.loadImagesFrom';

/**
 * The per-sender "always load images" list of the read view (SPEC.md §4.1): sender addresses,
 * lower case, kept in this browser.
 */
@Injectable({ providedIn: 'root' })
export class ImageAllowlist {
  private readonly senders = signal<ReadonlySet<string>>(stored());

  has(address: string | undefined): boolean {
    return !!address && this.senders().has(normalize(address));
  }

  add(address: string): void {
    this.update((senders) => senders.add(normalize(address)));
  }

  remove(address: string): void {
    this.update((senders) => senders.delete(normalize(address)));
  }

  private update(change: (senders: Set<string>) => void): void {
    const next = new Set(this.senders());
    change(next);
    this.senders.set(next);
    try {
      localStorage.setItem(IMAGE_ALLOWLIST_KEY, JSON.stringify([...next]));
    } catch {
      // Storage full or disabled: the choice holds for this session only.
    }
  }
}

function normalize(address: string): string {
  return address.trim().toLowerCase();
}

function stored(): Set<string> {
  try {
    const value: unknown = JSON.parse(localStorage.getItem(IMAGE_ALLOWLIST_KEY) ?? '[]');
    return new Set(
      Array.isArray(value)
        ? value.filter((entry): entry is string => typeof entry === 'string').map(normalize)
        : [],
    );
  } catch {
    return new Set();
  }
}
