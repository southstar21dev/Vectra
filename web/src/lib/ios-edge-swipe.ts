/**
 * Left-edge swipe fallback for iOS home-screen web apps.
 *
 * iOS 26.x has no OS edge-back gesture in installed web apps, so the
 * history-based dialog dismissal never fires there. iOS 27+ does have the
 * gesture. This module detects the swipe itself and dismisses the top dialog
 * through the same JS path on every iOS version: while a dialog is open it
 * claims touches that start on the left edge so the OS never begins its
 * native back-swipe. Once the gesture locks horizontal, the topmost dialog
 * follows the finger; released past the threshold it slides out and
 * dismisses, otherwise it snaps back. If the OS still claims the gesture,
 * `touchcancel` aborts us, restores the panel, and the native popstate path
 * closes the dialog. When no panel can be animated, a short grace period
 * lets a native popstate close the dialog first, and the count guard
 * prevents a double close.
 */

export interface EdgeSwipePoint {
  x: number;
  y: number;
}

/** A swipe must start within this many px of the left screen edge. */
export const EDGE_START_MAX_X = 24;
/** ...and travel at least this far right. */
export const EDGE_MIN_HORIZONTAL_DX = 60;
/** Vertical drift must stay below this fraction of the horizontal travel. */
export const EDGE_MAX_VERTICAL_RATIO = 0.6;
/** Let a native edge-back gesture close the dialog before we do. */
export const EDGE_DISMISS_GRACE_MS = 300;
/** Slide-out distance/duration for a committed edge swipe. */
export const EDGE_DRAG_SLIDE_OUT_MS = 180;
/** Return-to-rest duration when the swipe is released short of the threshold. */
export const EDGE_DRAG_SNAP_BACK_MS = 220;

/** iOS home-screen apps expose navigator.standalone; nothing else does. */
export function isIosStandaloneApp(standalone: boolean | undefined): boolean {
  return standalone === true;
}

/** True when the gesture started at the left edge and moved right far enough. */
export function isEdgeBackSwipe(start: EdgeSwipePoint, end: EdgeSwipePoint): boolean {
  const dx = end.x - start.x;
  const dy = Math.abs(end.y - start.y);
  return (
    start.x <= EDGE_START_MAX_X &&
    dx >= EDGE_MIN_HORIZONTAL_DX &&
    dy <= dx * EDGE_MAX_VERTICAL_RATIO
  );
}

/** After the grace period: act only if no dialog was closed meanwhile. */
export function shouldDismissAfterGrace(dialogCountAtSwipe: number, currentDialogCount: number): boolean {
  return dialogCountAtSwipe > 0 && currentDialogCount >= dialogCountAtSwipe;
}

/** While a dialog is open the app claims touches that start on the left edge
 *  so the OS never begins its native back-swipe; every iOS version then
 *  dismisses through this module's JS path. */
export function shouldClaimEdgeTouch(dialogCount: number, clientX: number): boolean {
  return dialogCount > 0 && clientX <= EDGE_START_MAX_X;
}

/** Scrim opacity tracks the drag linearly; fully transparent at one viewport width. */
export function scrimOpacityForDrag(offset: number, viewportWidth: number): number {
  if (viewportWidth <= 0) return 1;
  return Math.max(0, Math.min(1, 1 - Math.max(0, offset) / viewportWidth));
}

/**
 * Display corner radius in points for known device sizes (portrait or
 * landscape; CSS px == pt on iOS). Safari exposes no screen-radius API.
 * Unknown sizes return 0 (square) rather than a wrong curve.
 */
const SCREEN_CORNER_RADII: ReadonlyArray<readonly [number, number, number]> = [
  [440, 956, 62], // iPhone 16 Pro Max
  [402, 874, 62], // iPhone 16 Pro
  [430, 932, 55], // 14/15 Pro Max, 16 Plus
  [428, 926, 53.33], // 12/13 Pro Max, 14 Plus
  [393, 852, 55], // 14 Pro, 15, 15 Pro, 16
  [390, 844, 47.33], // 12/12 Pro/13/13 Pro/14
  [375, 812, 44], // 12/13 mini (X/XS/11 Pro also match; newer value wins)
  [414, 896, 41.5], // XR/11 (11 Pro Max also matches)
  [1024, 1366, 18], // iPad Pro 12.9"
  [834, 1194, 18], // iPad Pro 11" / Air
  [820, 1180, 18], // iPad Air 10.9"
  [810, 1080, 18], // iPad 10.2"
  [375, 667, 0], // SE / older square-corner phones
];

export function estimateScreenCornerRadius(screenWidth: number, screenHeight: number): number {
  const short = Math.min(screenWidth, screenHeight);
  const long = Math.max(screenWidth, screenHeight);
  const match = SCREEN_CORNER_RADII.find(([w, h]) => w === short && h === long);
  return match ? match[2] : 0;
}

export interface IosEdgeSwipeCallbacks {
  dialogCount: () => number;
  dismissTop: () => void;
  /** Topmost dialog element (last [role="dialog"] in the DOM), if any. */
  topPanel: () => HTMLElement | null;
}

/** Attaches touch listeners (only touchstart is non-passive); returns a detach function. */
export function attachIosEdgeSwipeFallback(callbacks: IosEdgeSwipeCallbacks): () => void {
  let start: EdgeSwipePoint | null = null;
  let last: EdgeSwipePoint | null = null;
  let panel: HTMLElement | null = null;
  let scrim: HTMLElement | null = null;
  let countAtStart = 0;
  let dragging = false;
  let savedWillChange = '';
  let savedBorderRadius = '';
  let savedScrimOpacity = '';
  let cachedCornerRadius: number | null = null;
  let pendingSettle: (() => void) | null = null;

  const reset = () => {
    start = null;
    last = null;
    panel = null;
    scrim = null;
    dragging = false;
    savedWillChange = '';
    savedBorderRadius = '';
    savedScrimOpacity = '';
  };

  const beginDrag = (node: HTMLElement) => {
    savedWillChange = node.style.willChange;
    savedBorderRadius = node.style.borderRadius;
    savedScrimOpacity = scrim?.style.opacity ?? '';
    if (scrim) scrim.style.transition = 'none';
    if (cachedCornerRadius === null) {
      // window.screen does not change while the app is running.
      cachedCornerRadius = estimateScreenCornerRadius(window.screen.width, window.screen.height);
    }
    if (cachedCornerRadius > 0) node.style.borderRadius = `${cachedCornerRadius}px`;
    node.style.transition = 'none';
    node.style.willChange = 'transform';
    dragging = true;
  };

  const clearPanelStyles = (node: HTMLElement, willChange: string, borderRadius: string) => {
    node.style.transition = '';
    node.style.transform = '';
    node.style.willChange = willChange;
    node.style.borderRadius = borderRadius;
  };

  const restoreScrim = (node: HTMLElement | null, opacity: string) => {
    if (!node) return;
    node.style.transition = '';
    node.style.display = '';
    node.style.opacity = opacity;
  };

  /**
   * Runs `onTimeout` after `delay`. A new edge gesture may flush the settle
   * first: the timer is cancelled and `onFlush` (style restoration) runs
   * synchronously so the new gesture snapshots true original values.
   */
  const scheduleSettle = (delay: number, onFlush: () => void, onTimeout: () => void = onFlush) => {
    let active = true;
    let timer = 0;
    const flush = () => {
      if (!active) return;
      active = false;
      window.clearTimeout(timer);
      if (pendingSettle === flush) pendingSettle = null;
      onFlush();
    };
    timer = window.setTimeout(() => {
      if (!active) return;
      active = false;
      if (pendingSettle === flush) pendingSettle = null;
      onTimeout();
    }, delay);
    pendingSettle = flush;
  };

  const slideOut = (
    node: HTMLElement,
    countAtSwipe: number,
    willChange: string,
    borderRadius: string,
    scrimNode: HTMLElement | null,
    scrimOpacity: string
  ) => {
    node.style.transition = `transform ${EDGE_DRAG_SLIDE_OUT_MS}ms ease-out`;
    node.style.transform = 'translateX(100%)';
    if (scrimNode) {
      scrimNode.style.transition = `opacity ${EDGE_DRAG_SLIDE_OUT_MS}ms ease-out`;
      scrimNode.style.opacity = '0';
    }
    scheduleSettle(
      EDGE_DRAG_SLIDE_OUT_MS + 10,
      () => {
        clearPanelStyles(node, willChange, borderRadius);
        restoreScrim(scrimNode, scrimOpacity);
      },
      () => {
        if (callbacks.dialogCount() < countAtSwipe) {
          clearPanelStyles(node, willChange, borderRadius);
          restoreScrim(scrimNode, scrimOpacity);
          return;
        }
        // Hide the panel first: the dialog's own exit animation would otherwise
        // re-center an already off-screen card while it unmounts.
        node.style.display = 'none';
        // Hide the scrim without restoring its opacity: framer-motion would
        // otherwise replay the exit fade from its own motion value (still 1)
        // and flash the dark overlay as the card leaves. A hidden element
        // cannot flash.
        if (scrimNode) {
          scrimNode.style.transition = '';
          scrimNode.style.display = 'none';
        }
        callbacks.dismissTop();
      }
    );
  };

  const snapBack = (
    node: HTMLElement,
    willChange: string,
    borderRadius: string,
    scrimNode: HTMLElement | null,
    scrimOpacity: string
  ) => {
    node.style.transition = `transform ${EDGE_DRAG_SNAP_BACK_MS}ms ease-out`;
    node.style.transform = 'translateX(0px)';
    if (scrimNode) {
      scrimNode.style.transition = `opacity ${EDGE_DRAG_SNAP_BACK_MS}ms ease-out`;
      scrimNode.style.opacity = scrimOpacity;
    }
    scheduleSettle(EDGE_DRAG_SNAP_BACK_MS + 10, () => {
      clearPanelStyles(node, willChange, borderRadius);
      restoreScrim(scrimNode, scrimOpacity);
    });
  };

  const settleActiveDrag = () => {
    if (dragging && panel) {
      snapBack(panel, savedWillChange, savedBorderRadius, scrim, savedScrimOpacity);
    }
    reset();
  };

  const onTouchStart = (event: TouchEvent) => {
    if (event.touches.length !== 1 || callbacks.dialogCount() === 0) {
      settleActiveDrag();
      return;
    }
    const touch = event.touches[0];
    if (!shouldClaimEdgeTouch(callbacks.dialogCount(), touch.clientX)) {
      // Only an edge-started swipe may move the dialog.
      reset();
      return;
    }
    // A new gesture must not snapshot styles a previous settle still has
    // applied: flush that settle so the true originals are back first.
    pendingSettle?.();
    pendingSettle = null;
    // Suppresses the system back-swipe so the same JS dismissal path runs on
    // every iOS version. The ~24px edge strip cannot start scrolls/taps while
    // a dialog is open; that is the accepted cost.
    event.preventDefault();
    start = { x: touch.clientX, y: touch.clientY };
    last = start;
    countAtStart = callbacks.dialogCount();
    panel = callbacks.topPanel();
    scrim = panel?.parentElement?.querySelector<HTMLElement>('[data-dialog-scrim]') ?? null;
    dragging = false;
  };

  const onTouchMove = (event: TouchEvent) => {
    if (!start || event.touches.length !== 1) return;
    const touch = event.touches[0];
    last = { x: touch.clientX, y: touch.clientY };
    if (!panel) return;
    const dx = last.x - start.x;
    const dy = last.y - start.y;
    if (!dragging) {
      // Direction lock: only start following once the gesture is clearly
      // horizontal, so a mostly-vertical drag never shifts the dialog.
      if (dx < 8 || Math.abs(dy) > Math.abs(dx)) return;
      beginDrag(panel);
    }
    panel.style.transform = `translateX(${Math.max(0, dx)}px)`;
    if (scrim) {
      scrim.style.opacity = String(scrimOpacityForDrag(Math.max(0, dx), window.innerWidth));
    }
  };

  // A system edge gesture claims the touches and cancels the stream; put the
  // dialog back if we had already started following the finger.
  const onTouchCancel = () => settleActiveDrag();

  const onTouchEnd = (event: TouchEvent) => {
    const gestureStart = start;
    const touch = event.changedTouches[0];
    const gestureEnd = touch ? { x: touch.clientX, y: touch.clientY } : last;
    const node = panel;
    const scrimNode = scrim;
    const wasDragging = dragging;
    const countAtSwipe = countAtStart;
    const willChangeAtDrag = savedWillChange;
    const borderRadiusAtDrag = savedBorderRadius;
    const scrimOpacityAtDrag = savedScrimOpacity;
    reset();
    if (!gestureStart || !gestureEnd) {
      if (wasDragging && node) {
        snapBack(node, willChangeAtDrag, borderRadiusAtDrag, scrimNode, scrimOpacityAtDrag);
      }
      return;
    }
    const committed =
      isEdgeBackSwipe(gestureStart, gestureEnd) &&
      callbacks.dialogCount() >= countAtSwipe;

    if (!node) {
      // No animatable element (defensive): keep the previous behavior.
      if (committed) {
        window.setTimeout(() => {
          if (shouldDismissAfterGrace(countAtSwipe, callbacks.dialogCount())) {
            callbacks.dismissTop();
          }
        }, EDGE_DISMISS_GRACE_MS);
      }
      return;
    }

    if (committed) {
      slideOut(node, countAtSwipe, willChangeAtDrag, borderRadiusAtDrag, scrimNode, scrimOpacityAtDrag);
    } else if (wasDragging) {
      snapBack(node, willChangeAtDrag, borderRadiusAtDrag, scrimNode, scrimOpacityAtDrag);
    }
  };

  window.addEventListener('touchstart', onTouchStart, { passive: false });
  window.addEventListener('touchmove', onTouchMove, { passive: true });
  window.addEventListener('touchend', onTouchEnd, { passive: true });
  window.addEventListener('touchcancel', onTouchCancel, { passive: true });

  return () => {
    window.removeEventListener('touchstart', onTouchStart);
    window.removeEventListener('touchmove', onTouchMove);
    window.removeEventListener('touchend', onTouchEnd);
    window.removeEventListener('touchcancel', onTouchCancel);
  };
}
