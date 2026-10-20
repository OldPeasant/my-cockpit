import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { ItemType } from './links.service';

/** 24×24 glyphs per item type (Material icons, Apache 2.0); the app ships no icon font. */
const GLYPHS: Record<ItemType, string> = {
  TASK: 'M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 18c-4.41 0-8-3.59-8-8s3.59-8 8-8 8 3.59 8 8-3.59 8-8 8zm4.59-12.42L10 14.17l-2.59-2.58L6 13l4 4 8-8z',
  PROJECT: 'M3 13h2v-2H3v2zm0 4h2v-2H3v2zm0-8h2V7H3v2zm4 4h14v-2H7v2zm0 4h14v-2H7v2zM7 7v2h14V7H7z',
  NOTE: 'M14 2H6c-1.1 0-2 .9-2 2v16c0 1.1.89 2 1.99 2H18c1.1 0 2-.9 2-2V8l-6-6zm2 16H8v-2h8v2zm0-4H8v-2h8v2zm-3-5V3.5L18.5 9H13z',
  EVENT:
    'M17 12h-5v5h5v-5zM16 1v2H8V1H6v2H5c-1.11 0-1.99.9-1.99 2L3 19c0 1.1.89 2 2 2h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2h-1V1h-2zm3 18H5V8h14v11z',
  EMAIL:
    'M20 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2zm0 4l-8 5-8-5V6l8 5 8-5v2z',
  FILE: 'M6 2c-1.1 0-1.99.9-1.99 2L4 20c0 1.1.89 2 1.99 2H18c1.1 0 2-.9 2-2V8l-6-6H6zm7 7V3.5L18.5 9H13z',
  FOLDER:
    'M10 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2h-8l-2-2z',
  CONTACT:
    'M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z',
  BOOKMARK: 'M17 3H7c-1.1 0-1.99.9-1.99 2L5 21l7-3 7 3V5c0-1.1-.9-2-2-2z',
  JOURNAL_ENTRY:
    'M18 2H6c-1.1 0-2 .9-2 2v16c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zM6 4h5v8l-2.5-1.5L6 12V4z',
  HABIT: 'M7 7h10v3l4-4-4-4v3H5v6h2V7zm10 10H7v-3l-4 4 4 4v-3h12v-6h-2v4z',
};

/** The user-facing name of an item type. */
export function itemTypeLabel(type: ItemType): string {
  switch (type) {
    case 'TASK':
      return $localize`:@@itemType.task:Task`;
    case 'PROJECT':
      return $localize`:@@itemType.project:Project`;
    case 'NOTE':
      return $localize`:@@itemType.note:Note`;
    case 'EVENT':
      return $localize`:@@itemType.event:Event`;
    case 'EMAIL':
      return $localize`:@@itemType.email:Email`;
    case 'FILE':
      return $localize`:@@itemType.file:File`;
    case 'FOLDER':
      return $localize`:@@itemType.folder:Folder`;
    case 'CONTACT':
      return $localize`:@@itemType.contact:Contact`;
    case 'BOOKMARK':
      return $localize`:@@itemType.bookmark:Bookmark`;
    case 'JOURNAL_ENTRY':
      return $localize`:@@itemType.journalEntry:Journal entry`;
    case 'HABIT':
      return $localize`:@@itemType.habit:Habit`;
  }
}

/**
 * The glyph of an item type in a small tile. The server's Material Symbols name (`icon`) is kept as
 * `data-icon` for when an icon font arrives; the glyph is chosen by type, so every type has one.
 */
@Component({
  selector: 'app-item-type-icon',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    'aria-hidden': 'true',
    '[attr.data-type]': 'type()',
    '[attr.data-icon]': 'icon() ?? null',
  },
  template: `<svg viewBox="0 0 24 24" focusable="false"><path [attr.d]="glyph()" /></svg>`,
  styles: `
    :host {
      display: inline-grid;
      flex: none;
      place-items: center;
      width: 32px;
      height: 32px;
      border-radius: var(--mat-sys-corner-small);
      background: var(--mat-sys-secondary-container);
      color: var(--mat-sys-on-secondary-container);
    }

    svg {
      width: 18px;
      height: 18px;
      fill: currentColor;
    }
  `,
})
export class ItemTypeIcon {
  readonly type = input.required<ItemType>();
  readonly icon = input<string>();

  protected readonly glyph = computed(() => GLYPHS[this.type()]);
}
