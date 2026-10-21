import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  output,
  ViewEncapsulation,
} from '@angular/core';
import { DomSanitizer, SafeHtml } from '@angular/platform-browser';
import { Router } from '@angular/router';
import { itemTypeLabel } from '../../../shared/links';
import { NoteEditorService } from './note-editor.service';
import { InlineLinkInfo, renderNoteMarkdown } from './note-markdown';

/**
 * A note body rendered (SPEC.md §8.1): checklists toggle (`checklistToggle` gets the item's source line),
 * images show through the authenticated file URL, inline links `[[type:id]]` are chips that open
 * their item in the app. `readonly` disables the checkboxes.
 */
@Component({
  selector: 'app-note-markdown-view',
  changeDetection: ChangeDetectionStrategy.OnPush,
  // Not encapsulated: the styles reach the rendered HTML; every selector starts with this element.
  encapsulation: ViewEncapsulation.None,
  host: { '[innerHTML]': 'html()', '(click)': 'onClick($event)' },
  template: '',
  styleUrl: './note-markdown-view.scss',
})
export class NoteMarkdownView {
  private readonly sanitizer = inject(DomSanitizer);
  private readonly service = inject(NoteEditorService);
  private readonly router = inject(Router);

  readonly source = input.required<string>();
  /** What is known about inline links' items, by `TYPE:id`. */
  readonly links = input<ReadonlyMap<string, InlineLinkInfo>>(new Map());
  readonly readonly = input(false);

  readonly checklistToggle = output<number>();

  protected readonly html = computed<SafeHtml>(() => {
    const links = this.links();
    let html = renderNoteMarkdown(this.source(), {
      fileUrl: (id) => this.service.fileUrl(id),
      inlineLink: (type, id) => links.get(`${type}:${id}`),
      typeLabel: itemTypeLabel,
    });
    if (this.readonly()) html = html.replace(/ data-line="\d+"/g, ' disabled');
    // renderNoteMarkdown escapes all text and restricts URLs; the sanitizer would drop checkboxes.
    return this.sanitizer.bypassSecurityTrustHtml(html);
  });

  protected onClick(event: MouseEvent): void {
    const target = event.target as HTMLElement;
    if (target instanceof HTMLInputElement && target.dataset['line'] !== undefined) {
      this.checklistToggle.emit(Number(target.dataset['line']));
      return;
    }
    const chip = target.closest<HTMLElement>('a[data-route]');
    if (chip && !event.ctrlKey && !event.metaKey && !event.shiftKey && event.button === 0) {
      event.preventDefault();
      void this.router.navigateByUrl(chip.dataset['route'] ?? '/');
    }
  }
}
