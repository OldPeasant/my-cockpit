import { isTypingTarget, shellActionFor } from './hotkeys';

function press(init: KeyboardEventInit, target: HTMLElement = document.body): KeyboardEvent {
  const event = new KeyboardEvent('keydown', { bubbles: true, cancelable: true, ...init });
  Object.defineProperty(event, 'target', { value: target });
  return event;
}

describe('shellActionFor', () => {
  let input: HTMLInputElement;
  let textarea: HTMLTextAreaElement;
  let editable: HTMLDivElement;

  beforeEach(() => {
    input = document.createElement('input');
    textarea = document.createElement('textarea');
    editable = document.createElement('div');
    editable.contentEditable = 'true';
    // jsdom does not implement isContentEditable.
    Object.defineProperty(editable, 'isContentEditable', { value: true });
  });

  it('maps Ctrl+K and Meta+K to the palette, also while typing', () => {
    expect(shellActionFor(press({ key: 'k', ctrlKey: true }))).toBe('palette');
    expect(shellActionFor(press({ key: 'K', metaKey: true }))).toBe('palette');
    expect(shellActionFor(press({ key: 'k', ctrlKey: true }, input))).toBe('palette');
  });

  it('maps Q and Ctrl+Shift+Space to capture', () => {
    expect(shellActionFor(press({ key: 'q' }))).toBe('capture');
    expect(shellActionFor(press({ key: 'Q' }))).toBe('capture');
    expect(shellActionFor(press({ key: ' ', code: 'Space', ctrlKey: true, shiftKey: true }))).toBe(
      'capture',
    );
  });

  it('maps ? to the shortcut overview', () => {
    expect(shellActionFor(press({ key: '?', shiftKey: true }))).toBe('shortcuts');
  });

  it('ignores Q and ? while typing in inputs, text areas and editable content', () => {
    for (const target of [input, textarea, editable]) {
      expect(shellActionFor(press({ key: 'q' }, target))).toBeNull();
      expect(shellActionFor(press({ key: '?', shiftKey: true }, target))).toBeNull();
    }
  });

  it('ignores modified, repeated, composing and handled key presses', () => {
    expect(shellActionFor(press({ key: 'q', ctrlKey: true }))).toBeNull();
    expect(shellActionFor(press({ key: 'q', altKey: true }))).toBeNull();
    expect(shellActionFor(press({ key: 'Q', shiftKey: true }))).toBeNull();
    expect(shellActionFor(press({ key: 'q', repeat: true }))).toBeNull();
    expect(shellActionFor(press({ key: 'q', isComposing: true }))).toBeNull();
    const handled = press({ key: 'q' });
    handled.preventDefault();
    expect(shellActionFor(handled)).toBeNull();
    expect(shellActionFor(press({ key: 'x' }))).toBeNull();
  });
});

describe('isTypingTarget', () => {
  it('treats text inputs as typing but not buttons or checkboxes', () => {
    const text = document.createElement('input');
    const checkbox = document.createElement('input');
    checkbox.type = 'checkbox';
    expect(isTypingTarget(text)).toBe(true);
    expect(isTypingTarget(checkbox)).toBe(false);
    expect(isTypingTarget(document.createElement('select'))).toBe(true);
    expect(isTypingTarget(document.createElement('button'))).toBe(false);
    expect(isTypingTarget(null)).toBe(false);
  });
});
