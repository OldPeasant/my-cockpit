import { ChangeDetectionStrategy, Component } from '@angular/core';

/**
 * The mark of a {@link FileDrop} target while files are dragged over it: a dashed outline around the
 * nearest positioned ancestor with a "Drop to upload here" line at its bottom. Purely visual (the
 * drop itself is handled by the directive), so it is hidden from assistive technology.
 */
@Component({
  selector: 'app-drop-hint',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'drop-hint', 'aria-hidden': 'true' },
  template: `<p i18n="@@files.upload.dropHint">Drop to upload here</p>`,
  styleUrl: './drop-hint.scss',
})
export class DropHint {}
