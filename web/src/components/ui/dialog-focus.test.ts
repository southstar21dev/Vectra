import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { resolveFocusReturnTarget, restoreFocusTo } from './dialog-focus';

/** Minimal element fake: the suite runs without a DOM environment. */
class FakeElement {
  parentElement: FakeElement | null = null;
  attrs = new Map<string, string>();
  focus = vi.fn();
  isConnected = true;
  constructor(
    public cursor = 'auto',
    public focusable = false
  ) {}
  matches() {
    return this.focusable;
  }
  closest(): FakeElement | null {
    if (this.focusable) return this;
    return this.parentElement?.closest() ?? null;
  }
  hasAttribute(name: string) {
    return this.attrs.has(name);
  }
  setAttribute(name: string, value: string) {
    this.attrs.set(name, value);
  }
  removeAttribute(name: string) {
    this.attrs.delete(name);
  }
  addEventListener = vi.fn();
}

const body = new FakeElement();
const html = new FakeElement();

beforeEach(() => {
  vi.stubGlobal('HTMLElement', FakeElement);
  vi.stubGlobal('document', { body, documentElement: html });
  vi.stubGlobal('window', { getComputedStyle: (el: FakeElement) => ({ cursor: el.cursor }) });
});

afterEach(() => {
  vi.unstubAllGlobals();
});

const asEl = (el: FakeElement) => el as unknown as HTMLElement;

describe('resolveFocusReturnTarget', () => {
  it('prefers the element that held focus at open time', () => {
    const opener = new FakeElement('pointer', true);
    expect(resolveFocusReturnTarget(asEl(opener), null)).toBe(opener);
  });

  it("falls back to the pressed element's focusable ancestor when focus was on <body>", () => {
    const button = new FakeElement('pointer', true);
    const icon = new FakeElement('pointer');
    icon.parentElement = button;
    expect(resolveFocusReturnTarget(asEl(body), asEl(icon))).toBe(button);
  });

  it('uses the whole clickable card when nothing focusable was pressed', () => {
    const page = new FakeElement('auto');
    const card = new FakeElement('pointer');
    const label = new FakeElement('pointer');
    card.parentElement = page;
    label.parentElement = card;
    expect(resolveFocusReturnTarget(asEl(body), asEl(label))).toBe(card);
  });

  it('returns null with no opener and no recent press', () => {
    expect(resolveFocusReturnTarget(asEl(body), null)).toBeNull();
  });
});

describe('restoreFocusTo', () => {
  it('focuses a focusable opener as-is', () => {
    const opener = new FakeElement('pointer', true);
    restoreFocusTo(asEl(opener));
    expect(opener.focus).toHaveBeenCalled();
    expect(opener.attrs.has('tabindex')).toBe(false);
  });

  it('makes a non-focusable opener temporarily focusable', () => {
    const card = new FakeElement('pointer');
    restoreFocusTo(asEl(card));
    expect(card.attrs.get('tabindex')).toBe('-1');
    expect(card.focus).toHaveBeenCalled();
    expect(card.addEventListener).toHaveBeenCalledWith('blur', expect.any(Function), { once: true });
  });

  it('skips an opener that left the page', () => {
    const gone = new FakeElement('pointer', true);
    gone.isConnected = false;
    restoreFocusTo(asEl(gone));
    expect(gone.focus).not.toHaveBeenCalled();
  });
});
