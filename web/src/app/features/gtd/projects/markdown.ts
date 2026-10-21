/**
 * A small Markdown subset as HTML, for previews of short texts (a project's description): `#`
 * headings, paragraphs, `-`/`*`/`1.` lists, `>` quotes, fenced code, `**bold**`, `*italic*`,
 * `` `code` ``, `~~strike~~` and `[links](https://…)`.
 *
 * Safe for `[innerHTML]`: all text is HTML-escaped before markup is added, and links only take
 * `http(s):` and `mailto:` targets (Angular's sanitizer runs on top).
 */
export function renderMarkdown(source: string): string {
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
    const heading = /^(#{1,6})\s+(.*)$/.exec(line);
    if (heading) {
      // Headings of a description are below the page's own (h1/h2).
      const level = Math.min(heading[1].length + 2, 6);
      html.push(`<h${level}>${inline(heading[2].replace(/\s+#+\s*$/, ''))}</h${level}>`);
      i++;
      continue;
    }
    if (/^\s*([-*+]|\d+[.)])\s+/.test(line)) {
      const ordered = /^\s*\d/.test(line);
      const items: string[] = [];
      while (i < lines.length && /^\s*([-*+]|\d+[.)])\s+/.test(lines[i])) {
        items.push(`<li>${inline(lines[i].replace(/^\s*([-*+]|\d+[.)])\s+/, ''))}</li>`);
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
      html.push(`<blockquote>${renderMarkdown(quoted.join('\n'))}</blockquote>`);
      continue;
    }
    const paragraph: string[] = [];
    while (i < lines.length && lines[i].trim() && !startsBlock(lines[i])) {
      paragraph.push(lines[i++].trim());
    }
    html.push(`<p>${paragraph.map(inline).join('<br>')}</p>`);
  }
  return html.join('');
}

function startsBlock(line: string): boolean {
  return /^(#{1,6}\s|\s*```|\s*>|\s*([-*+]|\d+[.)])\s+)/.test(line);
}

function escape(text: string): string {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

/** Inline markup of one escaped line; code spans are kept apart so their contents stay literal. */
function inline(text: string): string {
  return text
    .split(/(`[^`]+`)/)
    .map((part) =>
      /^`[^`]+`$/.test(part) ? `<code>${escape(part.slice(1, -1))}</code>` : emphasis(escape(part)),
    )
    .join('');
}

/** Stands in for a link's opening tag while emphasis is applied (a private-use character). */
const ANCHOR = '\uE000';

/**
 * Links, then emphasis. A link's opening tag is replaced by a placeholder until the end, so its
 * target (and attributes such as `_blank`) stay literal; its label still gets emphasis.
 */
function emphasis(text: string): string {
  const anchors: string[] = [];
  return text
    .replaceAll(ANCHOR, '')
    .replace(/\[([^\]]+)\]\(([^)\s]+)\)/g, (match, label: string, href: string) => {
      if (!/^(https?:|mailto:)/i.test(href)) return label;
      anchors.push(`<a href="${href}" target="_blank" rel="noopener noreferrer">`);
      return `${ANCHOR}${anchors.length - 1}${ANCHOR}${label}</a>`;
    })
    .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
    .replace(/__(.+?)__/g, '<strong>$1</strong>')
    .replace(/(^|[^*])\*([^*\s][^*]*?)\*(?!\*)/g, '$1<em>$2</em>')
    .replace(/(^|[^\w])_([^_\s][^_]*?)_(?!\w)/g, '$1<em>$2</em>')
    .replace(/~~(.+?)~~/g, '<del>$1</del>')
    .replace(new RegExp(`${ANCHOR}(\\d+)${ANCHOR}`, 'g'), (match, index: string) => anchors[+index]);
}
