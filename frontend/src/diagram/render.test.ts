// @vitest-environment happy-dom
import { describe, expect, it, vi } from 'vitest';
import { renderOrRepair, sanitizeSvg } from './render';

const ok = (s: string) => {
  if (s.includes('BROKEN')) throw new Error('parse error');
  return `<svg>${s}</svg>`;
};

describe('renderOrRepair', () => {
  it('renders valid source without asking for a repair', async () => {
    const repair = vi.fn();
    const out = await renderOrRepair('fine', ok, repair);
    expect(out).toEqual({ kind: 'svg', svg: '<svg>fine</svg>', source: 'fine', repaired: false });
    expect(repair).not.toHaveBeenCalled();
  });

  it('repairs a parse failure exactly once and renders the fix', async () => {
    const repair = vi.fn().mockResolvedValue('fixed');
    const out = await renderOrRepair('BROKEN', ok, repair);
    expect(repair).toHaveBeenCalledTimes(1);
    expect(repair).toHaveBeenCalledWith('BROKEN', 'parse error');
    expect(out).toMatchObject({ kind: 'svg', source: 'fixed', repaired: true });
  });

  it('falls back to the raw source with the error when the repair is also broken', async () => {
    const repair = vi.fn().mockResolvedValue('BROKEN AGAIN');
    const out = await renderOrRepair('BROKEN', ok, repair);
    expect(repair).toHaveBeenCalledTimes(1);
    expect(out).toMatchObject({ kind: 'raw', source: 'BROKEN AGAIN' });
    expect((out as { error: string }).error).toContain('after one repair attempt');
  });

  it('falls back to the original source when the repair request itself fails', async () => {
    const out = await renderOrRepair('BROKEN', ok, () => Promise.reject(new Error('busy')));
    expect(out).toMatchObject({ kind: 'raw', source: 'BROKEN' });
    expect((out as { error: string }).error).toContain('busy');
  });
});

describe('sanitizeSvg', () => {
  it('strips scripts, foreignObject, handlers, javascript: links and the font @import', () => {
    const dirty = `<svg><style>@import url('https://fonts.googleapis.com/x');text{}</style>`
      + `<script>alert(1)</script><foreignObject><div>x</div></foreignObject>`
      + `<rect onclick="evil()" width="1"/><a href="javascript:alert(1)">l</a><text>safe &lt;b&gt;</text></svg>`;
    const clean = sanitizeSvg(dirty);
    expect(clean).not.toMatch(/script|foreignObject|onclick|javascript:|@import/i);
    expect(clean).toContain('safe &lt;b&gt;');
  });

  it('decodes entities before testing the href scheme, and keeps harmless hrefs', () => {
    const clean = sanitizeSvg('<svg><a href="j&#97;va\tscript:alert(1)">x</a><a href="#n1">ok</a></svg>');
    expect(clean).not.toMatch(/javascript:/i);
    expect(clean).toContain('#n1');
  });

  it('refuses markup that does not parse as XML instead of injecting it', () => {
    expect(() => sanitizeSvg('<svg><a href=javascript:alert(1)>x</a></svg>')).toThrow();
  });
});
