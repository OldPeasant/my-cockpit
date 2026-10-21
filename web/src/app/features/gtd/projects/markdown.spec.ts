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

  it('caps heading levels at h6 and drops closing hashes', () => {
    expect(renderMarkdown('#### Deep ##\n###### Deeper')).toBe('<h6>Deep</h6><h6>Deeper</h6>');
    expect(renderMarkdown('## Steps')).toBe('<h4>Steps</h4>');
  });

  it('does not treat a hash without a space as a heading', () => {
    expect(renderMarkdown('#hashtag')).toBe('<p>#hashtag</p>');
  });

  it('renders "1)" and "+" lists and ends a paragraph at a list', () => {
    expect(renderMarkdown('Steps:\n1) call\n2) write\n\n+ milk')).toBe(
      '<p>Steps:</p><ol><li>call</li><li>write</li></ol><ul><li>milk</li></ul>',
    );
  });

  it('normalizes Windows and old Mac line endings', () => {
    expect(renderMarkdown('one\r\ntwo\rthree')).toBe('<p>one<br>two<br>three</p>');
  });

  it('renders Markdown inside quotes and nested quotes', () => {
    expect(renderMarkdown('> **Note**\n> > inner')).toBe(
      '<blockquote><p><strong>Note</strong></p><blockquote><p>inner</p></blockquote></blockquote>',
    );
  });

  it('renders an unclosed code fence to the end of the text, without inline markup', () => {
    expect(renderMarkdown('```\n**not bold**\n# not a heading')).toBe(
      '<pre><code>**not bold**\n# not a heading</code></pre>',
    );
  });

  it('escapes HTML in headings, list items, quotes, code spans and link labels', () => {
    expect(
      renderMarkdown(
        '# <script>alert(1)</script>\n- <b>x</b>\n> <i>q</i>\n\n`<svg onload=x>` [<img>](https://example.org)',
      ),
    ).toBe(
      '<h3>&lt;script&gt;alert(1)&lt;/script&gt;</h3>' +
        '<ul><li>&lt;b&gt;x&lt;/b&gt;</li></ul>' +
        '<blockquote><p>&lt;i&gt;q&lt;/i&gt;</p></blockquote>' +
        '<p><code>&lt;svg onload=x&gt;</code> ' +
        '<a href="https://example.org" target="_blank" rel="noopener noreferrer">&lt;img&gt;</a></p>',
    );
  });

  it('cannot break out of the link href attribute', () => {
    const html = renderMarkdown('[x](https://example.org/"onmouseover="alert(1))');
    expect(html).toContain('href="https://example.org/&quot;onmouseover=&quot;alert(1"');
    expect(html).not.toContain('" onmouseover');
  });

  it('drops links with unsafe schemes, whatever their case', () => {
    expect(renderMarkdown('[a](JavaScript:alert(1)) [b](data:text/html,x) [c](vbscript:x)')).toBe(
      '<p>a) b c</p>',
    );
  });

  it('keeps mailto links and is case-insensitive about safe schemes', () => {
    expect(renderMarkdown('[mail](mailto:ann@example.org) [up](HTTPS://example.org)')).toBe(
      '<p><a href="mailto:ann@example.org" target="_blank" rel="noopener noreferrer">mail</a> ' +
        '<a href="HTTPS://example.org" target="_blank" rel="noopener noreferrer">up</a></p>',
    );
  });

  it('keeps emphasis markers inside a link target literal', () => {
    expect(renderMarkdown('[draft](https://example.org/_draft_/a)')).toBe(
      '<p><a href="https://example.org/_draft_/a" target="_blank" rel="noopener noreferrer">draft</a></p>',
    );
  });

  it('keeps the link attributes intact when emphasis follows the link', () => {
    expect(renderMarkdown('[a](https://example.org) then _b_ and [*c*](https://example.org/~~x~~)')).toBe(
      '<p><a href="https://example.org" target="_blank" rel="noopener noreferrer">a</a> then <em>b</em> and ' +
        '<a href="https://example.org/~~x~~" target="_blank" rel="noopener noreferrer"><em>c</em></a></p>',
    );
  });

  it('leaves snake_case words and lone asterisks alone', () => {
    expect(renderMarkdown('use snake_case_name and 2 * 3')).toBe(
      '<p>use snake_case_name and 2 * 3</p>',
    );
  });

  it('renders nothing for an empty text', () => {
    expect(renderMarkdown('')).toBe('');
    expect(renderMarkdown('\n  \n')).toBe('');
  });
});
