import {
  acceptCompletion,
  Completion,
  CompletionContext,
  completionStatus,
  CompletionResult,
  currentCompletions,
  startCompletion,
} from '@codemirror/autocomplete';
import { EditorState } from '@codemirror/state';
import { EditorView } from '@codemirror/view';
import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { linkCompletions, LinkOption, MarkdownEditor } from './markdown-editor';

const TASK_ID = '0192f0c4-0000-7000-8000-0000000000a1';
const BOOK: LinkOption = { type: 'TASK', id: TASK_ID, title: 'Book flights', detail: 'Task' };

const tick = (): Promise<void> => new Promise((resolve) => setTimeout(resolve));

describe('linkCompletions', () => {
  async function complete(
    doc: string,
    search = vi.fn(async () => [BOOK]),
  ): Promise<{ result: CompletionResult | null; view: EditorView; search: typeof search }> {
    const pos = doc.indexOf('|');
    const text = doc.replace('|', '');
    const view = new EditorView({
      state: EditorState.create({ doc: text, selection: { anchor: pos } }),
    });
    const context = new CompletionContext(view.state, pos, false);
    const result = await linkCompletions(search)(context);
    return { result, view, search };
  }

  function apply(view: EditorView, result: CompletionResult, option: Completion): void {
    const fn = option.apply as (v: EditorView, c: Completion, from: number, to: number) => void;
    fn(view, option, result.from, view.state.selection.main.head);
  }

  it('searches what follows [[ and inserts [[type:id]]', async () => {
    const { result, view, search } = await complete('Call [[book|');
    expect(search).toHaveBeenCalledWith('book');
    expect(result?.from).toBe(5);
    expect(result?.options.map((o) => [o.label, o.detail])).toEqual([['Book flights', 'Task']]);
    apply(view, result!, result!.options[0]);
    expect(view.state.doc.toString()).toBe(`Call [[task:${TASK_ID}]]`);
    expect(view.state.selection.main.head).toBe(view.state.doc.length);
  });

  it('takes a closing ]] typed already into the link', async () => {
    const { result, view } = await complete('[[book|]] later');
    apply(view, result!, result!.options[0]);
    expect(view.state.doc.toString()).toBe(`[[task:${TASK_ID}]] later`);
  });

  it('does not search without [[ or without a query', async () => {
    expect((await complete('Call book|')).result).toBeNull();
    const empty = await complete('Call [[ |');
    expect(empty.result).toBeNull();
    expect(empty.search).not.toHaveBeenCalled();
    expect((await complete('[[done]] and |')).result).toBeNull();
  });
});

@Component({
  imports: [MarkdownEditor],
  template: `<app-markdown-editor
    [value]="value()"
    label="Text"
    [uploadImage]="upload"
    [searchLinks]="search"
    (valueChange)="changes.push($event)"
    (uploadFailed)="failures.push($event)"
  />`,
})
class Host {
  readonly value = signal('Intro');
  readonly changes: string[] = [];
  readonly failures: unknown[] = [];
  upload?: (file: File) => Promise<string> = () => Promise.resolve('');
  search?: (query: string) => Promise<LinkOption[]>;
}

describe('MarkdownEditor', () => {
  let fixture: ComponentFixture<Host>;
  let host: Host;

  async function create(upload?: Host['upload'], search?: Host['search']): Promise<void> {
    fixture = TestBed.createComponent(Host);
    host = fixture.componentInstance;
    if (upload) host.upload = upload;
    host.search = search;
    fixture.detectChanges();
    await fixture.whenStable();
  }

  function view(): EditorView {
    const dom = (fixture.nativeElement as HTMLElement).querySelector('.cm-editor') as HTMLElement;
    return EditorView.findFromDOM(dom)!;
  }

  function paste(...files: File[]): ClipboardEvent {
    const event = new Event('paste', { bubbles: true, cancelable: true }) as ClipboardEvent;
    Object.defineProperty(event, 'clipboardData', { value: { files, getData: () => '' } });
    view().contentDOM.dispatchEvent(event);
    return event;
  }

  it('shows the value, reports edits and applies values from outside without reporting them', async () => {
    await create();
    expect(view().state.doc.toString()).toBe('Intro');
    expect(view().contentDOM.getAttribute('aria-label')).toBe('Text');
    view().dispatch({ changes: { from: 5, insert: '!' } });
    expect(host.changes).toEqual(['Intro!']);
    host.value.set('Restored');
    fixture.detectChanges();
    await fixture.whenStable();
    expect(view().state.doc.toString()).toBe('Restored');
    expect(host.changes).toEqual(['Intro!']);
  });

  it('uploads a pasted image and replaces its placeholder with the Markdown', async () => {
    let resolve!: (markdown: string) => void;
    const upload = vi.fn(() => new Promise<string>((r) => (resolve = r)));
    await create(upload);
    view().dispatch({ selection: { anchor: 5 } });
    const event = paste(new File(['x'], 'tent.png', { type: 'image/png' }));
    expect(event.defaultPrevented).toBe(true);
    expect(upload).toHaveBeenCalledOnce();
    expect(view().state.doc.toString()).toMatch(
      /^Intro\n!\[Uploading tent\.png…\]\(uploading:\d+\)\n$/,
    );
    resolve(`![tent.png](cockpit-file:${TASK_ID})`);
    await tick();
    expect(view().state.doc.toString()).toBe(`Intro\n![tent.png](cockpit-file:${TASK_ID})\n`);
    expect(host.changes.at(-1)).toBe(view().state.doc.toString());
  });

  it('removes the placeholder and reports a failed upload', async () => {
    const error = new Error('too large');
    await create(() => Promise.reject(error));
    view().dispatch({ selection: { anchor: 5 } });
    paste(new File(['x'], 'tent.png', { type: 'image/png' }));
    await tick();
    expect(view().state.doc.toString()).toBe('Intro\n\n');
    expect(host.failures).toEqual([error]);
  });

  it('leaves pastes without images to the editor', async () => {
    const upload = vi.fn();
    await create(upload);
    paste(new File(['x'], 'notes.txt', { type: 'text/plain' }));
    expect(view().state.doc.toString()).toBe('Intro');
    expect(upload).not.toHaveBeenCalled();
  });

  function drop(...files: File[]): Event {
    const event = new Event('drop', { bubbles: true, cancelable: true });
    Object.defineProperty(event, 'dataTransfer', {
      value: { files, types: ['Files'], getData: () => '' },
    });
    view().contentDOM.dispatchEvent(event);
    return event;
  }

  it('uploads a dropped image at the drop position', async () => {
    let resolve!: (markdown: string) => void;
    const upload = vi.fn(() => new Promise<string>((r) => (resolve = r)));
    await create(upload);
    // jsdom has no layout: the drop point maps to the start of the text.
    const at = vi.spyOn(view(), 'posAtCoords').mockReturnValue(0);
    view().dispatch({ selection: { anchor: 5 } });
    const event = drop(new File(['x'], 'map.jpg', { type: 'image/jpeg' }));
    expect(at).toHaveBeenCalled();
    expect(event.defaultPrevented).toBe(true);
    expect(upload).toHaveBeenCalledOnce();
    expect(view().state.doc.toString()).toMatch(
      /^!\[Uploading map\.jpg…\]\(uploading:\d+\)\nIntro$/,
    );
    // A drop does not move the cursor: it stays after "Intro".
    const head = view().state.selection.main.head;
    expect(view().state.doc.sliceString(head - 5, head)).toBe('Intro');
    resolve(`![map.jpg](cockpit-file:${TASK_ID})`);
    await tick();
    expect(view().state.doc.toString()).toBe(`![map.jpg](cockpit-file:${TASK_ID})\nIntro`);
    expect(host.changes.at(-1)).toBe(view().state.doc.toString());
  });

  it('inserts a dropped image at the cursor when the drop point is outside the text', async () => {
    await create(() => new Promise<string>(() => undefined));
    vi.spyOn(view(), 'posAtCoords').mockReturnValue(null);
    view().dispatch({ selection: { anchor: 5 } });
    drop(new File(['x'], 'map.jpg', { type: 'image/jpeg' }));
    expect(view().state.doc.toString()).toMatch(
      /^Intro\n!\[Uploading map\.jpg…\]\(uploading:\d+\)\n$/,
    );
  });

  it('leaves drops without images to the editor', async () => {
    const upload = vi.fn();
    await create(upload);
    vi.spyOn(view(), 'posAtCoords').mockReturnValue(0);
    const event = new Event('drop', { bubbles: true, cancelable: true });
    Object.defineProperty(event, 'dataTransfer', {
      value: { files: [], types: ['text/plain'], getData: () => '' },
    });
    view().contentDOM.dispatchEvent(event);
    expect(upload).not.toHaveBeenCalled();
    expect(view().state.doc.toString()).toBe('Intro');
  });

  it('uploads only the images of several pasted files, each into its own line', async () => {
    const resolvers: ((markdown: string) => void)[] = [];
    const upload = vi.fn(() => new Promise<string>((r) => resolvers.push(r)));
    await create(upload);
    view().dispatch({ selection: { anchor: 5 } });
    paste(
      new File(['x'], 'a.png', { type: 'image/png' }),
      new File(['x'], 'notes.txt', { type: 'text/plain' }),
      new File(['x'], '', { type: 'image/gif' }),
    );
    expect(upload).toHaveBeenCalledTimes(2);
    expect(view().state.doc.toString()).toMatch(
      /^Intro\n!\[Uploading a\.png…\]\(uploading:\d+\)\n!\[Uploading image…\]\(uploading:\d+\)\n$/,
    );
    // The second upload finishes first: each result replaces its own placeholder.
    resolvers[1]('![b](cockpit-file:2)');
    await tick();
    resolvers[0]('![a](cockpit-file:1)');
    await tick();
    expect(view().state.doc.toString()).toBe('Intro\n![a](cockpit-file:1)\n![b](cockpit-file:2)\n');
  });

  it('keeps the upload result in place when the text around the placeholder changes meanwhile', async () => {
    let resolve!: (markdown: string) => void;
    await create(() => new Promise<string>((r) => (resolve = r)));
    view().dispatch({ selection: { anchor: 5 } });
    paste(new File(['x'], 'tent.png', { type: 'image/png' }));
    view().dispatch({ changes: { from: 0, insert: 'Trip. ' } });
    resolve('![tent](cockpit-file:1)');
    await tick();
    expect(view().state.doc.toString()).toBe('Trip. Intro\n![tent](cockpit-file:1)\n');
  });

  it('drops the upload result when its placeholder was deleted', async () => {
    let resolve!: (markdown: string) => void;
    await create(() => new Promise<string>((r) => (resolve = r)));
    paste(new File(['x'], 'tent.png', { type: 'image/png' }));
    view().dispatch({ changes: { from: 0, to: view().state.doc.length, insert: 'Gone' } });
    resolve('![tent](cockpit-file:1)');
    await tick();
    expect(view().state.doc.toString()).toBe('Gone');
  });

  it('leaves image pastes to the editor without an upload function', async () => {
    fixture = TestBed.createComponent(Host);
    host = fixture.componentInstance;
    host.upload = undefined;
    fixture.detectChanges();
    await fixture.whenStable();
    paste(new File(['x'], 'tent.png', { type: 'image/png' }));
    expect(view().state.doc.toString()).toBe('Intro');
  });

  it('offers link targets after [[ in the editor and inserts the chosen one', async () => {
    const search = vi.fn(async () => [BOOK]);
    await create(undefined, search);
    view().dispatch({
      changes: { from: 5, insert: ' [[book' },
      selection: { anchor: 12 },
      userEvent: 'input.type',
    });
    startCompletion(view());
    await vi.waitFor(() => expect(completionStatus(view().state)).toBe('active'));
    expect(search).toHaveBeenCalledWith('book');
    expect(currentCompletions(view().state).map((c) => c.label)).toEqual(['Book flights']);
    // CodeMirror accepts a completion only after a short interaction delay.
    await vi.waitFor(() => expect(acceptCompletion(view())).toBe(true));
    expect(view().state.doc.toString()).toBe(`Intro [[task:${TASK_ID}]]`);
    expect(host.changes.at(-1)).toBe(`Intro [[task:${TASK_ID}]]`);
  });

  it('offers no link targets without a search function', async () => {
    await create();
    view().dispatch({
      changes: { from: 5, insert: ' [[book' },
      selection: { anchor: 12 },
      userEvent: 'input.type',
    });
    startCompletion(view());
    await tick();
    await tick();
    expect(currentCompletions(view().state)).toEqual([]);
  });
});
