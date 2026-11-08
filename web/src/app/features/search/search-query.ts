import { itemTypeLabel } from '../../shared/links/item-type';
import { ItemType } from '../../shared/links/links.service';

/** Every type the search can be limited to, in the server's group order. */
export const SEARCH_TYPES: readonly ItemType[] = [
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

/** Short English names besides the type itself (`journal_entry`) and its user-facing label. */
const ALIASES: Readonly<Record<string, ItemType>> = {
  mail: 'EMAIL',
  journal: 'JOURNAL_ENTRY',
};

/** The search text without its `type:` filter, and the types to limit the search to. */
export interface SearchQuery {
  readonly text: string;
  /** Empty: all types. */
  readonly types: readonly ItemType[];
}

const TYPE_PREFIX = 'type:';

/**
 * Splits `type:` filters off a palette query: `type:note passport` or `passport type:task,note`.
 * A type is named by its id (`journal_entry`), its label in the UI language (`Notiz`, spaces
 * removed) or a short alias (`mail`, `journal`), in any case and optionally plural (`tasks`).
 * A `type:` word that names no known type stays part of the text, so nothing typed is lost.
 */
export function parseSearchQuery(query: string): SearchQuery {
  const words: string[] = [];
  const types = new Set<ItemType>();
  for (const word of query.trim().split(/\s+/)) {
    const named = word.toLowerCase().startsWith(TYPE_PREFIX)
      ? parseTypes(word.slice(TYPE_PREFIX.length))
      : null;
    if (named) {
      for (const type of named) types.add(type);
    } else if (word) {
      words.push(word);
    }
  }
  return {
    text: words.join(' '),
    types: SEARCH_TYPES.filter((type) => types.has(type)),
  };
}

/** The types a comma-separated list names, or `null` when one of them is unknown or none is given. */
function parseTypes(list: string): ItemType[] | null {
  const names = list.split(',').filter((name) => name);
  if (!names.length) return null;
  const types: ItemType[] = [];
  for (const name of names) {
    const type = typeOf(name);
    if (!type) return null;
    types.push(type);
  }
  return types;
}

function typeOf(name: string): ItemType | undefined {
  const key = normalize(name);
  return lookup(key) ?? (key.endsWith('s') ? lookup(key.slice(0, -1)) : undefined);
}

function lookup(key: string): ItemType | undefined {
  return (
    ALIASES[key] ??
    SEARCH_TYPES.find((type) => normalize(type) === key || normalize(itemTypeLabel(type)) === key)
  );
}

function normalize(name: string): string {
  return name.toLowerCase().replace(/[\s_-]/g, '');
}
