import {
  autocompletion,
  Completion,
  CompletionContext,
  CompletionResult,
} from '@codemirror/autocomplete';
import { defaultKeymap, history, historyKeymap } from '@codemirror/commands';
import { markdown } from '@codemirror/lang-markdown';
import { defaultHighlightStyle, syntaxHighlighting } from '@codemirror/language';
import { EditorState } from '@codemirror/state';
import {
  Decoration,
  EditorView,
  keymap,
  MatchDecorator,
  placeholder,
  ViewPlugin,
  ViewUpdate,
} from '@codemirror/view';
import {
  afterNextRender,
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  effect,
  ElementRef,
  inject,
  input,
  output,
  untracked,
  ViewEncapsulation,
} from '@angular/core';
import { ItemRefDto } from '../../../api/generated';
import { inlineLinkMarkdown } from './note-markdown';

/** A target offered by the `[[` autocomplete. */
export interface LinkOption extends ItemRefDto {
  readonly title: string;
  /** Shown next to the title, e.g. the item type. */
  readonly detail?: string;
}

/** Text before the cursor that asks for a link: `[[` and what was typed since. */
const LINK_TRIGGER = /\[\[([^\]\n]*)$/;

/** Marks inline links `[[type:id]]` in the editor, so they read as one unit. */
const inlineLinkMarks = ViewPlugin.fromClass(
  class {
    private readonly decorator = new MatchDecorator({
      regexp: /\[\[[A-Za-z_]+:[0-9A-Fa-f-]{36}]]/g,
      decoration: Decoration.mark({ class: 'cm-inline-link' }),
    });
    decorations;
    constructor(view: EditorView) {
      this.decorations = this.decorator.createDeco(view);
    }
    update(update: ViewUpdate): void {
      this.decorations = this.decorator.updateDeco(update, this.decorations);
    }
  },
  { decorations: (plugin) => plugin.decorations },
);

let nextUpload = 0;

/**
 * A CodeMirror 6 Markdown editor (SPEC.md §8.1). `value` in, `valueChange` out (on every edit).
 * Pasted or dropped images go to `uploadImage`, which returns the Markdown to insert; meanwhile a
 * placeholder stands at the place. Typing `[[` offers the items `searchLinks` finds and inserts the
 * chosen one as `[[type:id]]`.
 */
@Component({
  selector: 'app-markdown-editor',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: '',
  styleUrl: './markdown-editor.scss',
  encapsulation: ViewEncapsulation.None,
})
export class MarkdownEditor {
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  readonly value = input.required<string>();
  /** The accessible name of the text area. */
  readonly label = input.required<string>();
  readonly placeholder = input('');
  readonly uploadImage = input<(file: File) => Promise<string>>();
  readonly searchLinks = input<(query: string) => Promise<LinkOption[]>>();

  readonly valueChange = output<string>();
  /** An image could not be uploaded: the error, after its placeholder was removed. */
  readonly uploadFailed = output<unknown>();

  private view?: EditorView;
  /** Set while a value from outside is applied: that is no edit to report. */
  private applying = false;

  constructor() {
    afterNextRender(() => {
      this.view = new EditorView({
        parent: this.host.nativeElement,
        state: this.createState(untracked(this.value)),
      });
    });
    inject(DestroyRef).onDestroy(() => this.view?.destroy());
    // A value from outside (load, restore, a toggled checkbox) replaces the text.
    effect(() => {
      const value = this.value();
      const view = this.view;
      if (view && value !== view.state.doc.toString()) {
        this.applying = true;
        try {
          view.dispatch({ changes: { from: 0, to: view.state.doc.length, insert: value } });
        } finally {
          this.applying = false;
        }
      }
    });
  }

  focus(): void {
    this.view?.focus();
  }

  private createState(doc: string): EditorState {
    return EditorState.create({
      doc,
      extensions: [
        history(),
        keymap.of([...defaultKeymap, ...historyKeymap]),
        markdown(),
        syntaxHighlighting(defaultHighlightStyle, { fallback: true }),
        EditorView.lineWrapping,
        placeholder(this.placeholder()),
        EditorView.contentAttributes.of({ 'aria-label': this.label(), 'aria-multiline': 'true' }),
        inlineLinkMarks,
        autocompletion({
          override: [(context) => this.completeLink(context)],
          activateOnTyping: true,
          icons: false,
        }),
        EditorView.updateListener.of((update) => {
          if (update.docChanged && !this.applying)
            this.valueChange.emit(update.state.doc.toString());
        }),
        EditorView.domEventHandlers({
          paste: (event, view) => this.insertImages(view, event.clipboardData, null, event),
          drop: (event, view) => {
            const pos = view.posAtCoords({ x: event.clientX, y: event.clientY });
            return this.insertImages(view, event.dataTransfer, pos, event);
          },
        }),
      ],
    });
  }

  private async completeLink(context: CompletionContext): Promise<CompletionResult | null> {
    const search = this.searchLinks();
    const before = context.state.doc.sliceString(Math.max(0, context.pos - 100), context.pos);
    const match = LINK_TRIGGER.exec(before);
    if (!search || !match) return null;
    const query = match[1].trim();
    if (!query) return null;
    const from = context.pos - match[0].length;
    const options = await search(query);
    if (context.aborted) return null;
    return {
      from,
      // Results belong to the query: typing on asks again.
      filter: false,
      options: options.map((option): Completion => ({
        label: option.title,
        detail: option.detail,
        apply: (view, completion, start, end) => {
          // A closing `]]` the user typed already belongs to the link.
          const after = view.state.doc.sliceString(end, end + 2);
          const to = after === ']]' ? end + 2 : end;
          const insert = inlineLinkMarkdown(option);
          view.dispatch({
            changes: { from: start, to, insert },
            selection: { anchor: start + insert.length },
          });
        },
      })),
    };
  }

  /** Uploads the images among `data`'s files; false (not handled) when there are none. */
  private insertImages(
    view: EditorView,
    data: DataTransfer | null,
    pos: number | null,
    event: Event,
  ): boolean {
    const upload = this.uploadImage();
    const images = [...(data?.files ?? [])].filter((file) => file.type.startsWith('image/'));
    if (!upload || !images.length) return false;
    event.preventDefault();
    let at = pos ?? view.state.selection.main.from;
    for (const file of images) {
      const marker = `![Uploading ${file.name || 'image'}…](uploading:${nextUpload++})`;
      const insert =
        at > 0 && view.state.doc.sliceString(at - 1, at) !== '\n' ? `\n${marker}\n` : `${marker}\n`;
      view.dispatch({
        changes: { from: at, insert },
        selection: pos === null ? { anchor: at + insert.length } : undefined,
      });
      at += insert.length;
      upload(file).then(
        (markdown) => this.replaceMarker(view, marker, markdown),
        (error: unknown) => {
          this.replaceMarker(view, marker, '');
          this.uploadFailed.emit(error);
        },
      );
    }
    return true;
  }

  private replaceMarker(view: EditorView, marker: string, insert: string): void {
    if (this.view !== view) return;
    const from = view.state.doc.toString().indexOf(marker);
    if (from < 0) return;
    view.dispatch({ changes: { from, to: from + marker.length, insert } });
  }
}
