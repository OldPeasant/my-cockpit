import { ItemRefDto } from '../../../api/generated';

type ItemType = ItemRefDto['type'];

/** The item types an inline link may name (SPEC.md §4.3), as the server knows them. */
const ITEM_TYPES: readonly ItemType[] = [
  'TASK',
  'PROJECT',
  'NOTE',
  'EVENT',
  'EMAIL',
  'FILE',
  'FOLDER',
  'CONTACT',
  'BOOKMARK',
  'JOURNAL_ENTRY',
  'HABIT',
];

/** `[[type:id]]` with the type in any case and a UUID, as the server parses it (`NoteMarkdown.kt`). */
const INLINE_LINK =
  /\[\[([A-Za-z_]+):([0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12})]]/g;

/** A list item, optionally a checklist item (`- [ ]`, `- [x]`). */
const LIST_ITEM = /^(\s*)([-*+]|\d+[.)])\s+(?:\[([ xX])\]\s+)?(.*)$/;

/** The checkbox of a checklist item, for toggling it in the source. */
const CHECKBOX = /^(\s*(?:[-*+]|\d+[.)])\s+\[)([ xX])(\])/;

/** What the preview knows about the item of an inline link. */
export interface InlineLinkInfo {
  readonly title: string;
  /** Client route of the item; without one the chip is not a link. */
  readonly deepLink?: string;
}

export interface NoteRenderOptions {
  /** The URL that shows the file `id` (the browser sends the session cookie). */
  fileUrl(id: string): string;
  /** What is known about an inline link's item; unknown items show their type only. */
  inlineLink(type: ItemType, id: string): InlineLinkInfo | undefined;
  /** The name of an item type, for chips. */
  typeLabel(type: ItemType): string;
}

/** The Markdown of an inline link to `item`: `[[task:0192…]]` (the type in lower case). */
export function inlineLinkMarkdown(item: ItemRefDto): string {
  return `[[${item.type.toLowerCase()}:${item.id}]]`;
}

/** The items linked inline, each once, in order of appearance; unknown types are left out. */
export function inlineLinks(source: string): ItemRefDto[] {
  const seen = new Set<string>();
  const refs: ItemRefDto[] = [];
  for (const match of source.matchAll(INLINE_LINK)) {
    const type = itemType(match[1]);
    const id = match[2].toLowerCase();
    if (!type || seen.has(`${type}:${id}`)) continue;
    seen.add(`${type}:${id}`);
    refs.push({ type, id });
  }
  return refs;
}

/**
 * `source` with the checklist item on line `line` (0-based) checked or unchecked. Lines that are not
 * checklist items stay as they are.
 */
export function toggleChecklistItem(source: string, line: number): string {
  const lines = source.split('\n');
  if (line < 0 || line >= lines.length) return source;
  lines[line] = lines[line].replace(CHECKBOX, (match, open: string, mark: string, close: string) =>
    mark === ' ' ? `${open}x${close}` : `${open} ${close}`,
  );
  return lines.join('\n');
}

/**
 * A note body as HTML for the preview (SPEC.md §8.1): headings, paragraphs, lists with checklist
 * items (checkboxes carry their source line as `data-line`), quotes, fenced code, rules, emphasis,
 * `[links](https://…)`, images (`cockpit-file:{id}` through {@link NoteRenderOptions.fileUrl}) and
 * inline links `[[type:id]]` as chips (with `data-route` when the item has a deep link).
 *
 * All text is HTML-escaped before markup is added, links only take `http(s):`/`mailto:` targets and
 * images only `cockpit-file:` and `https:`, so the result may be trusted as HTML.
 */
export function renderNoteMarkdown(source: string, options: NoteRenderOptions): string {
  const lines = source.replace(/\r\n?/g, '\n').split('\n');
  const html: string[] = [];
  let i = 0;
  while (i < lines.length) {
    const line = lines[i];
    if (/^\s*```/.test(line)) {
      const code: string[] = [];
      i++;
      while (i < lines.length && !/^\s*```/.test(lines[i])) code.push(lines[i++]);
      i++;
      html.push(`<pre><code>${escape(code.join('\n'))}</code></pre>`);
      continue;
    }
    if (!line.trim()) {
      i++;
      continue;
    }
    if (/^\s*([-*_])(\s*\1){2,}\s*$/.test(line)) {
      html.push('<hr>');
      i++;
      continue;
    }
    const heading = /^(#{1,6})\s+(.*)$/.exec(line);
    if (heading) {
      // The note's title is the page heading (h1): body headings start below it.
      const level = Math.min(heading[1].length + 1, 6);
      const text = inline(heading[2].replace(/\s+#+\s*$/, ''), options);
      html.push(`<h${level}>${text}</h${level}>`);
      i++;
      continue;
    }
    if (LIST_ITEM.test(line)) {
      const ordered = /^\s*\d/.test(line);
      const items: string[] = [];
      let match: RegExpExecArray | null;
      while (i < lines.length && (match = LIST_ITEM.exec(lines[i]))) {
        items.push(listItem(match, i, options));
        i++;
      }
      const tag = ordered ? 'ol' : 'ul';
      html.push(`<${tag}>${items.join('')}</${tag}>`);
      continue;
    }
    if (/^\s*>/.test(line)) {
      const quoted: string[] = [];
      while (i < lines.length && /^\s*>/.test(lines[i])) {
        quoted.push(lines[i++].replace(/^\s*>\s?/, ''));
      }
      // Checkboxes in a quote do not map to source lines: the quote is shown read-only.
      const inner = renderNoteMarkdown(quoted.join('\n'), options).replace(
        / data-line="\d+"/g,
        ' disabled',
      );
      html.push(`<blockquote>${inner}</blockquote>`);
      continue;
    }
    const paragraph: string[] = [];
    while (i < lines.length && lines[i].trim() && !startsBlock(lines[i])) {
      paragraph.push(lines[i++].trim());
    }
    html.push(`<p>${paragraph.map((text) => inline(text, options)).join('<br>')}</p>`);
  }
  return html.join('');
}

function listItem(match: RegExpExecArray, line: number, options: NoteRenderOptions): string {
  const [, indent, , mark, text] = match;
  const depth = Math.min(Math.floor(indent.replace(/\t/g, '  ').length / 2), 6);
  const style = depth ? ` style="margin-inline-start: ${depth * 1.5}em"` : '';
  const content = inline(text, options);
  if (mark === undefined) return `<li${style}>${content}</li>`;
  const checked = mark !== ' ' ? ' checked' : '';
  return (
    `<li class="task"${style}><label><input type="checkbox" data-line="${line}"${checked}>` +
    `<span>${content}</span></label></li>`
  );
}

function startsBlock(line: string): boolean {
  return /^(#{1,6}\s|\s*```|\s*>|\s*([-*+]|\d+[.)])\s+)/.test(line);
}

function itemType(name: string): ItemType | undefined {
  const type = name.toUpperCase() as ItemType;
  return ITEM_TYPES.includes(type) ? type : undefined;
}

function escape(text: string): string {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

/** Inline markup of one line; code spans are kept apart so their contents stay literal. */
function inline(text: string, options: NoteRenderOptions): string {
  return text
    .split(/(`[^`]+`)/)
    .map((part) =>
      /^`[^`]+`$/.test(part)
        ? `<code>${escape(part.slice(1, -1))}</code>`
        : emphasis(escape(part), options),
    )
    .join('');
}

/** Stands in for generated markup while emphasis is applied (a private-use character). */
const SLOT = '';

/**
 * Images, chips and links, then emphasis. Generated tags are replaced by placeholders until the
 * end, so their attributes (URLs, ids) stay literal; a link's label still gets emphasis.
 */
function emphasis(text: string, options: NoteRenderOptions): string {
  const slots: string[] = [];
  const slot = (html: string) => `${SLOT}${slots.push(html) - 1}${SLOT}`;
  return text
    .replaceAll(SLOT, '')
    .replace(/!\[([^\]]*)\]\(([^)\s]+)\)/g, (match, alt: string, src: string) => {
      const url = imageUrl(src, options);
      return url ? slot(`<img src="${url}" alt="${alt}" loading="lazy">`) : alt;
    })
    .replace(INLINE_LINK, (match, name: string, id: string) => {
      const type = itemType(name);
      return type ? slot(chip(type, id.toLowerCase(), options)) : match;
    })
    .replace(/\[([^\]]+)\]\(([^)\s]+)\)/g, (match, label: string, href: string) => {
      if (!/^(https?:|mailto:)/i.test(href)) return label;
      return `${slot(`<a href="${href}" target="_blank" rel="noopener noreferrer">`)}${label}</a>`;
    })
    .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
    .replace(/__(.+?)__/g, '<strong>$1</strong>')
    .replace(/(^|[^*])\*([^*\s][^*]*?)\*(?!\*)/g, '$1<em>$2</em>')
    .replace(/(^|[^\w])_([^_\s][^_]*?)_(?!\w)/g, '$1<em>$2</em>')
    .replace(/~~(.+?)~~/g, '<del>$1</del>')
    .replace(new RegExp(`${SLOT}(\\d+)${SLOT}`, 'g'), (match, index: string) => slots[+index]);
}

/** The URL of an image source (already escaped); null when it is not allowed. */
function imageUrl(src: string, options: NoteRenderOptions): string | null {
  const file = /^cockpit-file:([0-9A-Fa-f-]{36})$/.exec(src);
  if (file) return escape(options.fileUrl(file[1].toLowerCase()));
  return /^https:\/\//i.test(src) ? src : null;
}

function chip(type: ItemType, id: string, options: NoteRenderOptions): string {
  const info = options.inlineLink(type, id);
  const label = `<span class="chip-type">${escape(options.typeLabel(type))}</span>`;
  const title = info ? ` <span class="chip-title">${escape(info.title)}</span>` : '';
  const attrs = `class="chip" data-type="${type}" data-id="${id}"`;
  if (!info?.deepLink) return `<span ${attrs}>${label}${title}</span>`;
  const route = escape(info.deepLink);
  return `<a ${attrs} href="${route}" data-route="${route}">${label}${title}</a>`;
}
