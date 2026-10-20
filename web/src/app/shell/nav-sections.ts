/** One entry of the left navigation (SPEC.md §9.1). `path` is the absolute router path. */
export interface NavSection {
  readonly id: string;
  readonly path: string;
  readonly label: string;
}

/** Sections grouped by what the user does there; the shell renders a divider between groups. */
export interface NavGroup {
  readonly id: string;
  readonly label: string;
  readonly sections: readonly NavSection[];
}

export const NAV_GROUPS: readonly NavGroup[] = [
  {
    id: 'home',
    label: $localize`:@@nav.group.home:Home`,
    sections: [{ id: 'dashboard', path: '/', label: $localize`:@@nav.dashboard:Dashboard` }],
  },
  {
    id: 'tasks',
    label: $localize`:@@nav.group.tasks:Tasks`,
    sections: [
      { id: 'inbox', path: '/inbox', label: $localize`:@@nav.inbox:Inbox` },
      { id: 'next', path: '/next', label: $localize`:@@nav.next:Next` },
      { id: 'projects', path: '/projects', label: $localize`:@@nav.projects:Projects` },
      { id: 'waiting', path: '/waiting', label: $localize`:@@nav.waiting:Waiting` },
      { id: 'someday', path: '/someday', label: $localize`:@@nav.someday:Someday` },
      { id: 'logbook', path: '/logbook', label: $localize`:@@nav.logbook:Logbook` },
    ],
  },
  {
    id: 'time',
    label: $localize`:@@nav.group.time:Time and messages`,
    sections: [
      { id: 'calendar', path: '/calendar', label: $localize`:@@nav.calendar:Calendar` },
      { id: 'mail', path: '/mail', label: $localize`:@@nav.mail:Mail` },
    ],
  },
  {
    id: 'knowledge',
    label: $localize`:@@nav.group.knowledge:Knowledge`,
    sections: [
      { id: 'notes', path: '/notes', label: $localize`:@@nav.notes:Notes` },
      { id: 'files', path: '/files', label: $localize`:@@nav.files:Files` },
      { id: 'contacts', path: '/contacts', label: $localize`:@@nav.contacts:Contacts` },
      { id: 'bookmarks', path: '/bookmarks', label: $localize`:@@nav.bookmarks:Bookmarks` },
    ],
  },
  {
    id: 'reflect',
    label: $localize`:@@nav.group.reflect:Reflect`,
    sections: [
      { id: 'journal', path: '/journal', label: $localize`:@@nav.journal:Journal` },
      { id: 'habits', path: '/habits', label: $localize`:@@nav.habits:Habits` },
      { id: 'review', path: '/review', label: $localize`:@@nav.review:Review` },
    ],
  },
  {
    id: 'system',
    label: $localize`:@@nav.group.system:System`,
    sections: [
      { id: 'trash', path: '/trash', label: $localize`:@@nav.trash:Trash` },
      { id: 'settings', path: '/settings', label: $localize`:@@nav.settings:Settings` },
    ],
  },
];

export const NAV_SECTIONS: readonly NavSection[] = NAV_GROUPS.flatMap((group) => group.sections);
