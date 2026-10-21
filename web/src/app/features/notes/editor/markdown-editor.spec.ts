import { Completion, CompletionContext, CompletionResult } from '@codemirror/autocomplete';
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
    (valueChange)="changes.push($event)"
    (uploadFailed)="failures.push($event)"
  />`,
})
class Host {
  readonly value = signal('Intro');
  readonly changes: string[] = [];
  readonly failures: unknown[] = [];
  upload: (file: File) => Promise<string> = () => Promise.resolve('');
}

describe('MarkdownEditor', () => {
  let fixture: ComponentFixture<Host>;
  let host: Host;

  async function create(upload?: Host['upload']): Promise<void> {
    fixture = TestBed.createComponent(Host);
    host = fixture.componentInstance;
    if (upload) host.upload = upload;
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
});
