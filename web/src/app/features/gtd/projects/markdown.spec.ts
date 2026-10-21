import { renderMarkdown } from './markdown';

describe('renderMarkdown', () => {
  it('renders headings, paragraphs and lists', () => {
    expect(renderMarkdown('# Goal\nShip it\nsoon\n\n- one\n- two\n\n1. first')).toBe(
      '<h3>Goal</h3><p>Ship it<br>soon</p><ul><li>one</li><li>two</li></ul><ol><li>first</li></ol>',
    );
  });

  it('renders inline markup', () => {
    expect(renderMarkdown('**bold** *it* _also_ ~~gone~~ `a*b*`')).toBe(
      '<p><strong>bold</strong> <em>it</em> <em>also</em> <del>gone</del> <code>a*b*</code></p>',
    );
  });

  it('renders quotes and fenced code literally', () => {
    expect(renderMarkdown('> note\n\n```\n<b>x</b>\n```')).toBe(
      '<blockquote><p>note</p></blockquote><pre><code>&lt;b&gt;x&lt;/b&gt;</code></pre>',
    );
  });

  it('escapes HTML', () => {
    expect(renderMarkdown('<img src=x onerror="alert(1)">')).toBe(
      '<p>&lt;img src=x onerror=&quot;alert(1)&quot;&gt;</p>',
    );
  });

  it('links only http(s) and mailto targets', () => {
    expect(renderMarkdown('[site](https://example.org) [bad](javascript:alert(1))')).toBe(
      '<p><a href="https://example.org" target="_blank" rel="noopener noreferrer">site</a> bad)</p>',
    );
  });

  it('renders nothing for an empty text', () => {
    expect(renderMarkdown('')).toBe('');
    expect(renderMarkdown('\n  \n')).toBe('');
  });
});
