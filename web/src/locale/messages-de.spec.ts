/// <reference types="node" />
// The German translation file (task i18n-german). `npm run build:de` fails on a trans-unit that is
// missing from messages.de.xlf, but Angular's XLIFF 1.2 loader silently falls back to the English
// <source> when a unit has no <target>, and it does not notice a translation that drops a
// placeholder or was written for an older English text. These checks keep messages.de.xlf in step
// with the extracted messages.xlf (`npm run i18n:extract`).
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

// `ng test` runs from the web/ folder.
const localeDir = join(process.cwd(), 'src', 'locale');

interface Unit {
  source: string;
  target: string | null;
  placeholders: string[];
  targetPlaceholders: string[];
}

/** Message text with every `<x id="..."/>` placeholder written as `<id>` (no braces, see icuCases). */
function text(el: Element): string {
  return Array.from(el.childNodes)
    .map((node) =>
      node.nodeType === node.ELEMENT_NODE
        ? `<${(node as Element).getAttribute('id')}>`
        : (node.textContent ?? ''),
    )
    .join('');
}

function placeholders(el: Element | null): string[] {
  if (!el) return [];
  return Array.from(el.getElementsByTagName('x'))
    .map((x) => x.getAttribute('id') ?? '')
    .sort();
}

function load(file: string): Map<string, Unit> {
  const xml = readFileSync(join(localeDir, file), 'utf8');
  const doc = new DOMParser().parseFromString(xml, 'application/xml');
  expect(doc.getElementsByTagName('parsererror')).toHaveLength(0);
  const units = new Map<string, Unit>();
  for (const unit of Array.from(doc.getElementsByTagName('trans-unit'))) {
    const id = unit.getAttribute('id') ?? '';
    expect(units.has(id), `duplicate trans-unit ${id} in ${file}`).toBe(false);
    const source = unit.getElementsByTagName('source')[0];
    const target = unit.getElementsByTagName('target')[0] ?? null;
    units.set(id, {
      source: text(source),
      target: target ? text(target) : null,
      placeholders: placeholders(source),
      targetPlaceholders: placeholders(target),
    });
  }
  return units;
}

/** ICU case keys (`=0`, `one`, `other`, select values) in order of appearance, nested ones included. */
function icuCases(message: string): string[] {
  if (!/^\{VAR_(PLURAL|SELECT)/.test(message.trim())) return [];
  return Array.from(message.matchAll(/([=\w-]+)\s*\{/g), (m) => m[1]).sort();
}

const english = load('messages.xlf');
const german = load('messages.de.xlf');

describe('German translations (messages.de.xlf)', () => {
  it('declares German as the target language of an XLIFF 1.2 file', () => {
    const xml = readFileSync(join(localeDir, 'messages.de.xlf'), 'utf8');
    const doc = new DOMParser().parseFromString(xml, 'application/xml');
    expect(doc.documentElement.getAttribute('version')).toBe('1.2');
    const file = doc.getElementsByTagName('file')[0];
    expect(file.getAttribute('source-language')).toBe('en');
    expect(file.getAttribute('target-language')).toBe('de');
  });

  it('has a unit for every extracted message and none for messages that no longer exist', () => {
    expect(english.size).toBeGreaterThan(0);
    const missing = [...english.keys()].filter((id) => !german.has(id));
    const stale = [...german.keys()].filter((id) => !english.has(id));
    expect(missing).toEqual([]);
    expect(stale).toEqual([]);
  });

  it('translates the current English text of every message', () => {
    // A unit whose <source> differs from messages.xlf was translated from an older English text.
    const outdated = [...english].filter(
      ([id, en]) => german.get(id) && german.get(id)?.source !== en.source,
    );
    expect(outdated.map(([id]) => id)).toEqual([]);
  });

  it('gives every message a non-empty <target>', () => {
    const untranslated = [...german].filter(
      ([, de]) => de.target === null || de.target.trim() === '',
    );
    expect(untranslated.map(([id]) => id)).toEqual([]);
  });

  it('keeps every placeholder of the English message in the translation', () => {
    expect([...english.values()].some((en) => en.placeholders.length > 0)).toBe(true);
    const mismatched = [...english]
      .filter(([id, en]) => {
        const de = german.get(id);
        return de && JSON.stringify(de.targetPlaceholders) !== JSON.stringify(en.placeholders);
      })
      .map(([id]) => id);
    expect(mismatched).toEqual([]);
  });

  it('keeps the plural and select cases of ICU messages', () => {
    expect([...english.values()].some((en) => icuCases(en.source).includes('other'))).toBe(true);
    const mismatched = [...english]
      .filter(([id, en]) => {
        const target = german.get(id)?.target;
        return (
          target != null && JSON.stringify(icuCases(target)) !== JSON.stringify(icuCases(en.source))
        );
      })
      .map(([id]) => id);
    expect(mismatched).toEqual([]);
  });

  it('keeps the quick-capture due keywords the parser accepts (SPEC.md: !today, !tomorrow)', () => {
    // The parser (server CaptureParser) only understands the English keywords, so a hint that
    // shows a translated keyword teaches syntax that silently does nothing.
    const translatedKeywords = [...english]
      .flatMap(([id, en]) =>
        ['!today', '!tomorrow']
          .filter(
            (keyword) => en.source.includes(keyword) && !german.get(id)?.target?.includes(keyword),
          )
          .map((keyword) => `${id}: ${keyword}`),
      )
      .sort();
    expect(translatedKeywords).toEqual([]);
  });
});
