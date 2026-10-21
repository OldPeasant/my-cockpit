import {
  inlineLinkMarkdown,
  inlineLinks,
  NoteRenderOptions,
  renderNoteMarkdown,
  toggleChecklistItem,
} from './note-markdown';

const TASK_ID = '0192f0c4-0000-7000-8000-0000000000a1';
const FILE_ID = '0192f0c4-0000-7000-8000-0000000000f1';

const OPTIONS: NoteRenderOptions = {
  fileUrl: (id) => `/api/v1/files/${id}/preview`,
  inlineLink: (type, id) =>
    type === 'TASK' && id === TASK_ID
      ? { title: 'Book <flights>', deepLink: `/tasks/${id}` }
      : undefined,
  typeLabel: (type) => (type === 'TASK' ? 'Task' : type),
};

function render(source: string): string {
  return renderNoteMarkdown(source, OPTIONS);
}

describe('renderNoteMarkdown', () => {
  it('renders checklist items as checkboxes carrying their source line', () => {
    const html = render('Trip\n\n- [ ] Passport\n- [x] Charger\n- plain');
    expect(html).toContain('<p>Trip</p>');
    expect(html).toContain('<input type="checkbox" data-line="2"><span>Passport</span>');
    expect(html).toContain('<input type="checkbox" data-line="3" checked><span>Charger</span>');
    expect(html).toContain('<li>plain</li>');
  });

  it('shows cockpit-file images through the file URL and drops other schemes', () => {
    expect(render(`![tent](cockpit-file:${FILE_ID})`)).toContain(
      `<img src="/api/v1/files/${FILE_ID}/preview" alt="tent" loading="lazy">`,
    );
    expect(render('![x](https://example.org/a_b_c.png)')).toContain(
      'src="https://example.org/a_b_c.png"',
    );
    expect(render('![evil](javascript:alert(1))')).not.toContain('<img');
    expect(render('![evil](http://example.org/x.png)')).not.toContain('<img');
  });

  it('renders inline links as chips: known items link to their route, others show their type', () => {
    const html = render(`See [[task:${TASK_ID}]] and [[note:${FILE_ID}]].`);
    expect(html).toContain(
      `<a class="chip" data-type="TASK" data-id="${TASK_ID}" href="/tasks/${TASK_ID}" data-route="/tasks/${TASK_ID}">` +
        '<span class="chip-type">Task</span> <span class="chip-title">Book &lt;flights&gt;</span></a>',
    );
    expect(html).toContain(
      `<span class="chip" data-type="NOTE" data-id="${FILE_ID}"><span class="chip-type">NOTE</span></span>`,
    );
    expect(render(`[[spaceship:${TASK_ID}]]`)).toContain(`[[spaceship:${TASK_ID}]]`);
  });

  it('escapes HTML and allows only http(s) and mailto links', () => {
    const html = render(
      '<script>alert(1)</script> [x](javascript:alert(1)) [ok](https://a.example)',
    );
    expect(html).not.toContain('<script');
    expect(html).toContain('&lt;script&gt;');
    expect(html).not.toContain('javascript:');
    expect(html).toContain('<a href="https://a.example" target="_blank"');
  });

  it('renders headings below the page heading, emphasis, code, quotes and rules', () => {
    const html = render(
      '# Top\n**bold** *it* `a*b*`\n\n> quoted\n> - [ ] in quote\n\n---\n```\n<x>\n```',
    );
    expect(html).toContain('<h2>Top</h2>');
    expect(html).toContain('<strong>bold</strong> <em>it</em> <code>a*b*</code>');
    expect(html).toContain('<blockquote><p>quoted</p>');
    // A checkbox in a quote cannot be mapped to its source line: shown read-only.
    expect(html).toContain('<input type="checkbox" disabled>');
    expect(html).toContain('<hr>');
    expect(html).toContain('<pre><code>&lt;x&gt;</code></pre>');
  });

  it('indents nested list items', () => {
    expect(render('- a\n  - b')).toContain('<li style="margin-inline-start: 1.5em">b</li>');
  });
});

describe('toggleChecklistItem', () => {
  it('checks and unchecks the item on the given line', () => {
    const source = 'Trip\n- [ ] Passport\n  * [X] Charger\n1. [ ] Tickets';
    expect(toggleChecklistItem(source, 1)).toBe(
      'Trip\n- [x] Passport\n  * [X] Charger\n1. [ ] Tickets',
    );
    expect(toggleChecklistItem(source, 2)).toBe(
      'Trip\n- [ ] Passport\n  * [ ] Charger\n1. [ ] Tickets',
    );
    expect(toggleChecklistItem(source, 3)).toBe(
      'Trip\n- [ ] Passport\n  * [X] Charger\n1. [x] Tickets',
    );
  });

  it('leaves other lines and out-of-range lines alone', () => {
    expect(toggleChecklistItem('Trip\n- plain', 0)).toBe('Trip\n- plain');
    expect(toggleChecklistItem('Trip\n- plain', 1)).toBe('Trip\n- plain');
    expect(toggleChecklistItem('- [ ] a', 5)).toBe('- [ ] a');
    expect(toggleChecklistItem('- [ ] a', -1)).toBe('- [ ] a');
  });

  it('matches the lines the renderer numbers', () => {
    const source = 'Intro\n\n```\n- [ ] not a task\n```\n- [ ] Real';
    const line = Number(/data-line="(\d+)"/.exec(render(source))?.[1]);
    expect(toggleChecklistItem(source, line)).toBe(source.replace('- [ ] Real', '- [x] Real'));
  });
});

describe('inline links', () => {
  it('lists each linked item once, known types only, ids in lower case', () => {
    const upper = TASK_ID.toUpperCase();
    expect(
      inlineLinks(
        `[[task:${TASK_ID}]] [[TASK:${upper}]] [[journal_entry:${FILE_ID}]] [[x:${FILE_ID}]]`,
      ),
    ).toEqual([
      { type: 'TASK', id: TASK_ID },
      { type: 'JOURNAL_ENTRY', id: FILE_ID },
    ]);
  });

  it('writes the type in lower case', () => {
    expect(inlineLinkMarkdown({ type: 'JOURNAL_ENTRY', id: TASK_ID })).toBe(
      `[[journal_entry:${TASK_ID}]]`,
    );
  });
});
