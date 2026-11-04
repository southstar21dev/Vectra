/**
 * Where focus goes back to when a dialog closes.
 *
 * The opener is normally `document.activeElement` at open time. Two cases
 * leave only <body> there: a non-focusable opener (a clickable card `div`)
 * and Safari, which never focuses a clicked button. Then the element the user
 * just pressed stands in — its focusable ancestor if it has one, else the
 * clickable region itself (made focusable just for the restore).
 */

const FOCUSABLE =
  'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), summary, [tabindex]:not([tabindex="-1"]), [contenteditable="true"]';

function isPageRoot(el: Element | null): boolean {
  return !el || el === document.body || el === document.documentElement;
}

/** The outermost ancestor still showing the pointer cursor: the whole
 *  clickable card, not the text span inside it that took the press. */
function clickableRegion(el: Element): HTMLElement | null {
  if (typeof window === 'undefined' || !window.getComputedStyle) return null;
  let region: HTMLElement | null = null;
  let node: Element | null = el;
  while (node && !isPageRoot(node)) {
    if (!(node instanceof HTMLElement) || window.getComputedStyle(node).cursor !== 'pointer') break;
    region = node;
    node = node.parentElement;
  }
  return region;
}

export function resolveFocusReturnTarget(
  active: Element | null,
  pointerTarget: Element | null
): HTMLElement | null {
  if (active instanceof HTMLElement && !isPageRoot(active)) return active;
  if (!pointerTarget || isPageRoot(pointerTarget)) return null;
  const focusable = pointerTarget.closest<HTMLElement>(FOCUSABLE);
  if (focusable) return focusable;
  return clickableRegion(pointerTarget);
}

/** Focus `el` if it's still on the page; a non-focusable target gets a
 *  temporary tabindex="-1" that is dropped again once focus leaves it. */
export function restoreFocusTo(el: HTMLElement | null): void {
  if (!el || !el.isConnected) return;
  if (!el.matches(FOCUSABLE) && !el.hasAttribute('tabindex')) {
    el.setAttribute('tabindex', '-1');
    el.addEventListener('blur', () => el.removeAttribute('tabindex'), { once: true });
  }
  el.focus();
}
