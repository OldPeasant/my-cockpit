import {
  afterNextRender,
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  inject,
  input,
  output,
  signal,
  viewChild,
  ViewEncapsulation,
} from '@angular/core';
import { Editor } from '@tiptap/core';
import Image from '@tiptap/extension-image';
import StarterKit from '@tiptap/starter-kit';

/** Formatting commands of the toolbar. */
export type Format = 'bold' | 'italic' | 'underline' | 'bulletList' | 'orderedList' | 'blockquote';

/**
 * Images keep their markup but show as their alt text: quoted mails may point to remote images, and
 * the editor must not load them (the read view asks first, SPEC.md §7.3).
 */
const QuietImage = Image.extend({
  addNodeView() {
    return ({ node }) => {
      const dom = document.createElement('span');
      dom.className = 'image-placeholder';
      dom.textContent = `[${(node.attrs['alt'] as string | null) || $localize`:@@mailCompose.image:image`}]`;
      return { dom };
    };
  },
});

/**
 * The rich text editor of the compose view (TipTap): paragraphs, bold/italic/underline, lists,
 * quotes and links, with a toolbar. `html` is the starting content; `changed` reports every edit
 * with the new HTML.
 */
@Component({
  selector: 'app-rich-text-editor',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="toolbar" role="toolbar" [attr.aria-label]="toolbarLabel">
      @for (tool of tools; track tool.format) {
        <button
          type="button"
          class="tool"
          [class.on]="active().has(tool.format)"
          [attr.aria-pressed]="active().has(tool.format)"
          [attr.aria-label]="tool.label"
          [attr.title]="tool.label"
          (mousedown)="$event.preventDefault()"
          (click)="toggle(tool.format)"
        >
          <svg viewBox="0 0 24 24" aria-hidden="true"><path [attr.d]="tool.icon" /></svg>
        </button>
      }
      <button
        type="button"
        class="tool"
        [class.on]="active().has('link')"
        [attr.aria-label]="linkLabel"
        [attr.title]="linkLabel"
        (mousedown)="$event.preventDefault()"
        (click)="link()"
      >
        <svg viewBox="0 0 24 24" aria-hidden="true">
          <path
            d="M3.9 12c0-1.71 1.39-3.1 3.1-3.1h4V7H7c-2.76 0-5 2.24-5 5s2.24 5 5 5h4v-1.9H7c-1.71 0-3.1-1.39-3.1-3.1zM8 13h8v-2H8v2zm9-6h-4v1.9h4c1.71 0 3.1 1.39 3.1 3.1s-1.39 3.1-3.1 3.1h-4V17h4c2.76 0 5-2.24 5-5s-2.24-5-5-5z"
          />
        </svg>
      </button>
    </div>
    <div #host class="body"></div>
  `,
  styleUrl: './rich-text-editor.scss',
  encapsulation: ViewEncapsulation.None,
})
export class RichTextEditor {
  readonly html = input('');
  readonly label = input.required<string>();
  readonly changed = output<string>();

  protected readonly active = signal<ReadonlySet<string>>(new Set());
  protected readonly toolbarLabel = $localize`:@@mailCompose.formatting:Formatting`;
  protected readonly linkLabel = $localize`:@@mailCompose.link:Link`;
  protected readonly tools: { format: Format; label: string; icon: string }[] = [
    {
      format: 'bold',
      label: $localize`:@@mailCompose.bold:Bold`,
      icon: 'M15.6 10.79c.97-.67 1.65-1.77 1.65-2.79 0-2.26-1.75-4-4-4H7v14h7.04c2.09 0 3.71-1.7 3.71-3.79 0-1.52-.86-2.82-2.15-3.42zM10 6.5h3c.83 0 1.5.67 1.5 1.5s-.67 1.5-1.5 1.5h-3v-3zm3.5 9H10v-3h3.5c.83 0 1.5.67 1.5 1.5s-.67 1.5-1.5 1.5z',
    },
    {
      format: 'italic',
      label: $localize`:@@mailCompose.italic:Italic`,
      icon: 'M10 4v3h2.21l-3.42 8H6v3h8v-3h-2.21l3.42-8H18V4z',
    },
    {
      format: 'underline',
      label: $localize`:@@mailCompose.underline:Underline`,
      icon: 'M12 17c3.31 0 6-2.69 6-6V3h-2.5v8c0 1.93-1.57 3.5-3.5 3.5S8.5 12.93 8.5 11V3H6v8c0 3.31 2.69 6 6 6zm-7 2v2h14v-2H5z',
    },
    {
      format: 'bulletList',
      label: $localize`:@@mailCompose.bulletList:Bulleted list`,
      icon: 'M4 10.5c-.83 0-1.5.67-1.5 1.5s.67 1.5 1.5 1.5 1.5-.67 1.5-1.5-.67-1.5-1.5-1.5zm0-6c-.83 0-1.5.67-1.5 1.5S3.17 7.5 4 7.5 5.5 6.83 5.5 6 4.83 4.5 4 4.5zm0 12c-.83 0-1.5.68-1.5 1.5s.68 1.5 1.5 1.5 1.5-.68 1.5-1.5-.67-1.5-1.5-1.5zM7 19h14v-2H7v2zm0-6h14v-2H7v2zm0-8v2h14V5H7z',
    },
    {
      format: 'orderedList',
      label: $localize`:@@mailCompose.orderedList:Numbered list`,
      icon: 'M2 17h2v.5H3v1h1v.5H2v1h3v-4H2v1zm1-9h1V4H2v1h1v3zm-1 3h1.8L2 13.1v.9h3v-1H3.2L5 10.9V10H2v1zm5-6v2h14V5H7zm0 14h14v-2H7v2zm0-6h14v-2H7v2z',
    },
    {
      format: 'blockquote',
      label: $localize`:@@mailCompose.quote:Quote`,
      icon: 'M6 17h3l2-4V7H5v6h3zm8 0h3l2-4V7h-6v6h3z',
    },
  ];

  private readonly host = viewChild.required<ElementRef<HTMLElement>>('host');
  private editor: Editor | null = null;

  constructor() {
    const destroyRef = inject(DestroyRef);
    afterNextRender(() => {
      const editor = new Editor({
        element: this.host().nativeElement,
        extensions: [
          StarterKit.configure({
            link: { openOnClick: false, autolink: true, defaultProtocol: 'https' },
          }),
          QuietImage,
        ],
        content: this.html(),
        editorProps: {
          attributes: { role: 'textbox', 'aria-multiline': 'true', 'aria-label': this.label() },
        },
        onUpdate: ({ editor }) => this.changed.emit(editor.getHTML()),
        onTransaction: ({ editor }) => this.active.set(activeFormats(editor)),
      });
      this.editor = editor;
      destroyRef.onDestroy(() => editor.destroy());
    });
  }

  /** The current HTML (the starting content until the editor is ready). */
  getHtml(): string {
    return this.editor?.getHTML() ?? this.html();
  }

  focus(): void {
    this.editor?.commands.focus('start');
  }

  protected toggle(format: Format): void {
    const chain = this.editor?.chain().focus();
    if (!chain) return;
    switch (format) {
      case 'bold':
        chain.toggleBold().run();
        break;
      case 'italic':
        chain.toggleItalic().run();
        break;
      case 'underline':
        chain.toggleUnderline().run();
        break;
      case 'bulletList':
        chain.toggleBulletList().run();
        break;
      case 'orderedList':
        chain.toggleOrderedList().run();
        break;
      case 'blockquote':
        chain.toggleBlockquote().run();
        break;
    }
  }

  /** Asks for the address of the selected text; empty removes the link. */
  protected link(): void {
    const editor = this.editor;
    if (!editor) return;
    const current = (editor.getAttributes('link')['href'] as string | undefined) ?? '';
    const href = window.prompt($localize`:@@mailCompose.linkPrompt:Link address`, current);
    if (href === null) return;
    const chain = editor.chain().focus().extendMarkRange('link');
    if (href.trim()) chain.setLink({ href: href.trim() }).run();
    else chain.unsetLink().run();
  }
}

function activeFormats(editor: Editor): ReadonlySet<string> {
  const names = ['bold', 'italic', 'underline', 'bulletList', 'orderedList', 'blockquote', 'link'];
  return new Set(names.filter((name) => editor.isActive(name)));
}
