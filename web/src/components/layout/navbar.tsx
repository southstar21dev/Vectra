import { useState, useEffect, useLayoutEffect, useRef } from 'react';
import { RiAddLine } from '@remixicon/react';
import { cn, getGreeting } from '@/lib/utils';
import { Button } from '@/components/ui/button';
import { useAuth } from '@/features/auth/use-auth';
import { vibrate } from '@/lib/haptics';
import { resolveNavGesture } from '@/lib/touch-gestures';
import { markScrollOnNextPage } from '@/lib/navigation-scroll';
import { navigateInApp } from '@/lib/app-navigation';
import { prefetchRouteForPath } from '@/lib/route-prefetch';
import { motion, AnimatePresence, useReducedMotion } from 'framer-motion';
import { useNavbarActions } from '@/features/navbar/navbar-action-context';
import { desktopNavItems, mobilePrimaryNavItems } from './nav-items';

/** Width the FAB slot takes beside the nav pill when shown: w-14 + ml-2. */
const FAB_SLOT_PX = 56 + 8;
/** Nav pill chrome around the tabs: 1px border + p-1.5 on each side. */
const NAV_CHROME_PX = 2 * (1 + 6);
/** The icon box that glides inside each tab slot. */
const ICON_BOX_PX = 44;
/**
 * One curve for everything the dock moves when the FAB slot appears, so the
 * capsule clip, its shadow, the pill, the icons and the FAB stay in lockstep.
 * Every animated property here is compositor-only (transform, opacity,
 * clip-path): nothing in the dock changes layout or repaints per frame, which
 * is what kept the split stuttering on older phones.
 */
const DOCK_EASE = 'duration-300 ease-[cubic-bezier(0.32,0.72,0,1)] motion-reduce:transition-none';

interface NavbarProps {
  /** Active route. Updated urgently by the router (outside its transition), so
   *  the pill moves on the tap frame instead of after the new page renders. */
  currentPath: string;
}

export function Navbar({ currentPath }: NavbarProps) {
  // Scrolled styling is a DOM attribute, not state: scroll-to-top on a new page
  // must not re-render the navbar while its pill is moving.
  const desktopCapsuleRef = useRef<HTMLDivElement>(null);
  const topFadeRef = useRef<HTMLDivElement>(null);
  const [actionPopupOpen, setActionPopupOpen] = useState(false);
  // Press feedback for the tab icons, which are siblings of their hit targets
  // (see the dock below), so CSS :active cannot reach them.
  const [pressedIndex, setPressedIndex] = useState(-1);
  const reduceMotion = useReducedMotion();

  // Close the action popup when the route changes (adjusted during render so
  // the popup can never paint over the new page for a frame).
  const [lastPath, setLastPath] = useState(currentPath);
  if (currentPath !== lastPath) {
    setLastPath(currentPath);
    if (actionPopupOpen) setActionPopupOpen(false);
  }

  const { config, isActionRoute, executeAction } = useNavbarActions();
  // Whether the FAB shows comes from the route alone, so it appears/disappears on
  // the tap frame together with the pill slide. Waiting on the page's registered
  // config would collapse it only after the old page unmounts — a second resize
  // landing mid- or post-slide.
  const hasAction = isActionRoute(currentPath);

  // The dock's width never animates, so the pill's px targets for the *final*
  // layout can be derived from it and transitioned on the same curve as the FAB
  // slot — the pill and the resizing nav stay in lockstep.
  const dockRef = useRef<HTMLDivElement>(null);
  const [dockWidth, setDockWidth] = useState(0);
  useLayoutEffect(() => {
    const dock = dockRef.current;
    if (!dock) return;
    setDockWidth(dock.clientWidth);
    const observer = new ResizeObserver(() => setDockWidth(dock.clientWidth));
    observer.observe(dock);
    return () => observer.disconnect();
  }, []);

  useEffect(() => {
    // rAF-throttled + passive: the handler runs at most once per frame and never
    // blocks scrolling. The scrolled flag is written straight to the DOM, so
    // scrolling never re-renders the navbar.
    let frame = 0;
    const applyScrolled = () => {
      const scrolled = String(window.scrollY > 10);
      desktopCapsuleRef.current?.setAttribute('data-scrolled', scrolled);
      topFadeRef.current?.setAttribute('data-scrolled', scrolled);
    };
    const handleScroll = () => {
      if (frame) return;
      frame = window.requestAnimationFrame(() => {
        frame = 0;
        applyScrolled();
        if (actionPopupOpen) setActionPopupOpen(false);
      });
    };
    applyScrolled();
    window.addEventListener('scroll', handleScroll, { capture: true, passive: true });
    return () => {
      window.removeEventListener('scroll', handleScroll, true);
      if (frame) window.cancelAnimationFrame(frame);
    };
  }, [actionPopupOpen]);

  const desktopActiveIndex = desktopNavItems.findIndex(
    (item) => currentPath === item.href || (item.href !== '/' && currentPath.startsWith(item.href))
  );
  const desktopNavRef = useRef<HTMLElement>(null);
  const desktopTabRefs = useRef<(HTMLAnchorElement | null)[]>([]);
  const [desktopPill, setDesktopPill] = useState<{ x: number; width: number } | null>(null);
  // Offsets are relative to the nav, so page scroll can never enter the
  // measurement. Re-measured on resize too (fonts loading, the bold active label).
  useLayoutEffect(() => {
    const nav = desktopNavRef.current;
    if (!nav) return;
    const measure = () => {
      const tab = desktopTabRefs.current[desktopActiveIndex];
      setDesktopPill(tab ? { x: tab.offsetLeft, width: tab.offsetWidth } : null);
    };
    measure();
    const observer = new ResizeObserver(measure);
    observer.observe(nav);
    return () => observer.disconnect();
  }, [desktopActiveIndex]);

  const { profile, user } = useAuth();

  // Infinite swipe navigation on mobile bottom nav
  const navSwipeStart = useRef<{ x: number; y: number } | null>(null);
  const navSwipedRef = useRef(false);
  const navTapVibratedRef = useRef(false);
  const navTapNavigatedRef = useRef(false);
  const swipeTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const tapTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    return () => {
      if (swipeTimeoutRef.current) clearTimeout(swipeTimeoutRef.current);
      if (tapTimeoutRef.current) clearTimeout(tapTimeoutRef.current);
    };
  }, []);

  const navigateTo = (href: string) => {
    setActionPopupOpen(false);
    if (window.location.pathname === href) return;
    markScrollOnNextPage();
    if (navigateInApp(window.location.pathname, href, window.history)) {
      window.dispatchEvent(new PopStateEvent('popstate'));
    }
  };

  const handleNavSwipeStart = (e: React.TouchEvent) => {
    navSwipedRef.current = false;
    navTapVibratedRef.current = false;
    navTapNavigatedRef.current = false;
    const touch = e.targetTouches[0];
    navSwipeStart.current = { x: touch.clientX, y: touch.clientY };
  };

  const handleNavSwipeEnd = (e: React.TouchEvent) => {
    const start = navSwipeStart.current;
    navSwipeStart.current = null;
    if (!start) return;

    const touch = e.changedTouches[0];
    const tappedHref = (e.target as HTMLElement).closest('a')?.getAttribute('href') ?? null;

    const result = resolveNavGesture({
      start,
      end: { x: touch.clientX, y: touch.clientY },
      tappedHref,
      currentPath,
      navItems: mobilePrimaryNavItems,
    });

    if (result.kind === 'none') return;

    setActionPopupOpen(false);

    if (result.kind === 'tap') {
      if (!result.href) return;

      navTapVibratedRef.current = true;
      if (tapTimeoutRef.current) clearTimeout(tapTimeoutRef.current);
      tapTimeoutRef.current = setTimeout(() => {
        navTapVibratedRef.current = false;
        navTapNavigatedRef.current = false;
      }, 200);
      vibrate(10);

      navTapNavigatedRef.current = true;
      navigateTo(result.href);
      return;
    }

    navSwipedRef.current = true;
    if (swipeTimeoutRef.current) clearTimeout(swipeTimeoutRef.current);
    swipeTimeoutRef.current = setTimeout(() => {
      navSwipedRef.current = false;
    }, 200);
    vibrate(10);
    navigateTo(result.href);
  };

  const displayName = profile?.full_name || user?.email?.split('@')[0] || 'there';
  const initial = (profile?.full_name || user?.email || 'U').charAt(0).toUpperCase();
  const greeting = getGreeting();

  const ActionIcon = config?.icon || RiAddLine;

  const mobileActiveIndex = mobilePrimaryNavItems.findIndex(
    (item) => currentPath === item.href || (item.href !== '/' && currentPath.startsWith(item.href + '/'))
  );
  // The nav keeps the dock's full width in layout; the FAB only *looks* like
  // it takes a slot. Its visible length is a clip on the capsule, and every
  // slot position below is for that visible length.
  const navVisibleWidth = Math.max(0, dockWidth - (hasAction ? FAB_SLOT_PX : 0));
  const pillSlotPx = Math.max(0, (navVisibleWidth - NAV_CHROME_PX) / mobilePrimaryNavItems.length);
  const slotLeft = (index: number) => NAV_CHROME_PX / 2 + index * pillSlotPx;
  const iconX = (index: number) => slotLeft(index) + pillSlotPx / 2 - ICON_BOX_PX / 2;

  return (
    <>
      {/* Desktop Top Navbar (Floating Glass Capsule) */}
      <header className="hidden xl:flex sticky top-0 left-0 right-0 z-40 w-full px-4 sm:px-6 lg:px-8 pt-3 pb-1 transition-[border-color,box-shadow] duration-300 pointer-events-none">
        <div
          ref={desktopCapsuleRef}
          className={cn(
            "w-full max-w-6xl mx-auto px-4 py-2 rounded-full flex items-center justify-between transition-[border-color,box-shadow] duration-300 pointer-events-auto",
            "glass-popup border-subtle shadow-md",
            "data-[scrolled=true]:border-primary data-[scrolled=true]:shadow-lg"
          )}
        >
          {/* Logo & Greeting (Left) */}
          <div className="flex items-center gap-3 shrink-0">
            <a href="/" className="flex items-center transition-transform hover:scale-105 active:scale-95 group">
              <div className="relative">
                <div className="absolute -inset-1 rounded-full bg-accent-primary/25 blur-sm opacity-0 group-hover:opacity-100 transition-opacity" />
                <img src="/favicon.svg" alt="Vectra" className="relative h-9 w-9 object-contain" />
              </div>
            </a>
            <div className="flex flex-col">
              <span className="text-sm font-extrabold text-primary tracking-tight whitespace-nowrap leading-tight">
                {greeting}, {displayName}
              </span>
              <div className="flex items-center gap-1.5 text-[11px] text-muted">
                <span className="w-1.5 h-1.5 rounded-full bg-emerald-400 shadow-[0_0_6px_rgba(52,211,153,0.8)] animate-pulse" />
                <span>Neptun Synced</span>
              </div>
            </div>
          </div>

          {/* Desktop Navigation Capsule */}
          <nav ref={desktopNavRef} className="card-glass relative flex items-center gap-1 p-1 rounded-full">
            {/* One persistent pill, slid to the active tab's offset within the
                nav. A shared layoutId pill was measured page-relative, so when
                navigating from a scrolled page reset the scroll, the sticky
                header looked to have moved and the pill flew in from below. */}
            {desktopActiveIndex >= 0 && desktopPill && (
              <motion.div
                aria-hidden="true"
                initial={false}
                animate={{ x: desktopPill.x, width: desktopPill.width }}
                transition={reduceMotion ? { duration: 0 } : { type: 'spring', stiffness: 450, damping: 35 }}
                className="absolute top-1 bottom-1 left-0 rounded-full bg-accent-primary border border-accent-primary/50 nav-pill-glow pointer-events-none"
              >
                <div className="absolute top-0 inset-x-2 h-[1px] bg-gradient-to-r from-transparent via-white/50 to-transparent pointer-events-none" />
              </motion.div>
            )}
            {desktopNavItems.map((item, index) => {
              const isActive = index === desktopActiveIndex;
              const Icon = isActive ? item.activeIcon : item.icon;

              return (
                <a
                  key={item.href}
                  ref={(el) => { desktopTabRefs.current[index] = el; }}
                  href={item.href}
                  className="relative"
                  onPointerEnter={() => prefetchRouteForPath(item.href)}
                  onPointerDown={() => prefetchRouteForPath(item.href)}
                >
                  <Button
                    variant="ghost"
                    size="sm"
                    className={cn(
                      'relative z-10 h-8 px-3.5 gap-2 text-xs font-semibold rounded-full focus:outline-none focus-visible:outline-none transition-colors duration-200',
                      isActive
                        ? 'text-white font-bold hover:bg-transparent! hover:text-white!'
                        : 'text-secondary hover:bg-surface-overlay hover:text-primary'
                    )}
                  >
                    <Icon className={cn('w-3.5 h-3.5 shrink-0 transition-transform duration-300', isActive ? 'text-white scale-105 nav-icon-glow' : 'text-muted')} />
                    <span>{item.label}</span>
                  </Button>
                </a>
              );
            })}
          </nav>

          {/* User Profile Avatar (Right) */}
          <a href="/settings" className="flex items-center gap-2 group">
            <div className="flex items-center justify-center w-8 h-8 rounded-full bg-accent-primary text-white text-xs font-bold border border-white/20 select-none shrink-0 shadow-md group-hover:scale-105 group-hover:shadow-[0_0_12px_var(--glow-primary)] transition-[transform,box-shadow]">
              {initial}
            </div>
          </a>
        </div>
      </header>

      {/* Fade-out black backdrop behind navbar at the end of pages (Mobile only) */}
      <div
        className="xl:hidden fixed bottom-0 left-0 right-0 h-36 mobile-nav-fade pointer-events-none z-40"
        aria-hidden="true"
      />

      {/* Fade behind the status bar while scrolling (Mobile only) */}
      <div
        ref={topFadeRef}
        className="xl:hidden fixed top-0 left-0 right-0 h-[calc(env(safe-area-inset-top,0px)+5rem)] mobile-nav-fade-top pointer-events-none z-40 transition-opacity duration-300 opacity-0 data-[scrolled=true]:opacity-100"
        aria-hidden="true"
      />

      {/* Backdrops for Mobile Popups */}
      <AnimatePresence>
        {actionPopupOpen && (
          <motion.div
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            transition={{ duration: 0.2 }}
            onClick={() => {
              setActionPopupOpen(false);
            }}
            className="xl:hidden fixed inset-0 bg-black/40 z-45 pointer-events-auto"
          />
        )}
      </AnimatePresence>

      {/* Mobile Bottom Dock (Navigation Pill + Standalone Action Bubble + Anchored Popups) */}
      <div ref={dockRef} className="xl:hidden fixed bottom-[max(0.75rem,env(safe-area-inset-bottom,0px))] left-4 right-4 z-50 pointer-events-none max-w-md mx-auto h-[56px]">
        {/* Contextual Action Popup Menu (Directly above Action Bubble) */}
        <AnimatePresence>
          {actionPopupOpen && hasAction && config && (
            <motion.div
              initial={{ opacity: 0, y: 10, scale: 0.94 }}
              animate={{ opacity: 1, y: 0, scale: 1 }}
              exit={{ opacity: 0, y: 8, scale: 0.94 }}
              transition={{ type: 'spring', stiffness: 380, damping: 28 }}
              className="absolute right-0 bottom-[66px] z-55 flex flex-col gap-2 items-stretch pointer-events-auto"
            >
              {config?.actions.map((act) => {
                const ItemIcon = act.icon;
                const isEmerald = act.variant === 'emerald';
                const isBlue = act.variant === 'blue';
                const isGray = act.variant === 'gray' || act.variant === 'secondary';

                return (
                  <button
                    key={act.id}
                    type="button"
                    disabled={act.disabled}
                    onClick={() => {
                      vibrate(10);
                      setActionPopupOpen(false);
                      executeAction(act.id);
                    }}
                    className={cn(
                      "flex h-10 items-center justify-center gap-2 px-4 rounded-full text-xs font-bold transition-[color,background-color,border-color,box-shadow,transform,opacity] active:scale-95 disabled:opacity-50 disabled:pointer-events-none cursor-pointer shadow-lg",
                      isEmerald
                        ? "pill-depth-emerald text-white"
                        : isBlue
                          ? "pill-depth-blue text-white"
                          : isGray
                            ? "pill-depth-gray"
                            : "pill-depth-accent text-white"
                    )}
                  >
                    <ItemIcon className="w-4 h-4 shrink-0" />
                    <span>{act.label}</span>
                  </button>
                );
              })}
            </motion.div>
          )}
        </AnimatePresence>
        {/* Floating action bubble. It sits *under* the nav in paint order and
            starts small, tucked behind the capsule's end, so as the clip
            retreats the button is uncovered and grows out of the capsule
            instead of popping in beside it. Opacity trails the move slightly
            for the same reason. */}
        <div
          aria-hidden={!hasAction}
          inert={!hasAction}
          className={cn(
            'absolute right-0 top-0 flex h-[56px] w-14 items-center justify-center rounded-full',
            hasAction ? 'translate-x-0 scale-100 opacity-100' : '-translate-x-10 scale-[0.4] opacity-0'
          )}
          style={{
            // Entering, the button trails the capsule's retreat by a beat and
            // grows on a gentler curve than the dock's, so it reads as emerging
            // from the split rather than appearing beside it. Leaving, it
            // tucks back quickly so the capsule can close over it. The
            // utilities above write the individual `translate` and `scale`
            // properties, so those are what transition — not `transform`.
            transition: reduceMotion
              ? 'none'
              : hasAction
                ? 'translate 340ms cubic-bezier(0.22,1,0.36,1) 70ms, scale 340ms cubic-bezier(0.22,1,0.36,1) 70ms, opacity 200ms ease-out 70ms'
                : 'translate 260ms cubic-bezier(0.32,0.72,0,1), scale 260ms cubic-bezier(0.32,0.72,0,1), opacity 140ms ease-in',
          }}
        >
          <motion.button
            type="button"
            onClick={() => {
              vibrate(10);
              setActionPopupOpen((prev) => !prev);
            }}
            whileTap={{ scale: 0.88 }}
            aria-label="Page actions"
            title="Page actions"
            className="card-glass pointer-events-auto relative w-[56px] h-[56px] rounded-full text-primary flex items-center justify-center shrink-0"
          >
            {/* Keyed by the glyph, not by the page's first action: the slot
                appears from the route on the tap frame while the page registers
                its actions a beat later, and a key tied to those actions
                remounted an identical Plus mid-slide — the flash. */}
            <AnimatePresence mode="wait" initial={false}>
              <motion.div
                key={ActionIcon.displayName || ActionIcon.name || 'page-action'}
                initial={{ scale: 0.9, rotate: -8, opacity: 0 }}
                animate={{ scale: 1, rotate: actionPopupOpen ? 45 : 0, opacity: 1 }}
                exit={{ scale: 0.9, rotate: 8, opacity: 0 }}
                transition={{ duration: 0.18, ease: 'easeOut' }}
                className="flex items-center justify-center"
              >
                <ActionIcon className={cn("w-5 h-5 transition-colors", actionPopupOpen ? "text-accent-primary" : "text-primary")} />
              </motion.div>
            </AnimatePresence>
          </motion.button>
        </div>

        {/* Main navigation capsule. Layout never changes here: the capsule is
            always the dock's full width and is clipped to its visible length,
            its shadow scales behind it, the pill and the icons glide by
            transform, and only the hit targets snap to the new slots. */}
        <nav
          aria-label="Primary"
          onTouchStart={handleNavSwipeStart}
          onTouchEnd={handleNavSwipeEnd}
          onTouchCancel={() => {
            navSwipeStart.current = null;
            navSwipedRef.current = false;
            navTapVibratedRef.current = false;
            navTapNavigatedRef.current = false;
            setPressedIndex(-1);
          }}
          className="pointer-events-none absolute inset-0 touch-pan-y"
        >
          {/* Shadow: a blurred shape hides the horizontal scale. */}
          <div
            aria-hidden
            className={cn('absolute inset-0 origin-left rounded-full shadow-[0_8px_24px_-10px_rgba(0,0,0,0.6)] transition-transform', DOCK_EASE)}
            style={{ transform: `scaleX(${dockWidth > 0 ? navVisibleWidth / dockWidth : 1})` }}
          />
          {/* Capsule: clipped, not resized, so its rounded end stays a true
              semicircle through the whole move. */}
          <div
            aria-hidden
            className={cn('card-glass absolute inset-0 rounded-full transition-[clip-path] will-change-[clip-path]', DOCK_EASE)}
            style={{ clipPath: `inset(0 ${hasAction ? FAB_SLOT_PX : 0}px 0 0 round 9999px)` }}
          />

          {/* Active pill: translated to its slot; its width is the slot width,
              which only changes when the FAB slot appears. */}
          <span
            aria-hidden
            className={cn(
              'absolute left-1.5 top-1.5 bottom-1.5 rounded-full bg-accent-primary nav-pill-depth pointer-events-none transition-[transform,width,opacity]',
              DOCK_EASE,
              (mobileActiveIndex < 0 || dockWidth === 0) && 'opacity-0'
            )}
            style={{
              width: pillSlotPx,
              transform: `translateX(${Math.max(mobileActiveIndex, 0) * pillSlotPx}px)`,
            }}
          />

          {/* Hit targets: snap straight to the new slots (no transition). */}
          {mobilePrimaryNavItems.map((item, index) => (
            <a
              key={item.href}
              href={item.href}
              title={item.label}
              aria-label={item.label}
              aria-current={index === mobileActiveIndex ? 'page' : undefined}
              onPointerEnter={() => prefetchRouteForPath(item.href)}
              onPointerDown={() => {
                prefetchRouteForPath(item.href);
                setPressedIndex(index);
              }}
              onPointerUp={() => setPressedIndex(-1)}
              onPointerCancel={() => setPressedIndex(-1)}
              onPointerLeave={() => setPressedIndex(-1)}
              onClick={(e) => {
                setActionPopupOpen(false);
                if (navSwipedRef.current || navTapNavigatedRef.current) {
                  navSwipedRef.current = false;
                  navTapNavigatedRef.current = false;
                  e.preventDefault();
                  e.stopPropagation();
                  return;
                }
                if (navTapVibratedRef.current) {
                  navTapVibratedRef.current = false;
                  return;
                }
                vibrate(10);
              }}
              className="pointer-events-auto absolute top-1.5 bottom-1.5 z-10 rounded-full"
              style={{ left: slotLeft(index), width: pillSlotPx }}
            />
          ))}

          {/* Icons: glide to the slot centres on the compositor. */}
          {mobilePrimaryNavItems.map((item, index) => {
            const isActive = index === mobileActiveIndex;
            const Icon = isActive ? item.activeIcon : item.icon;
            return (
              <span
                key={item.href}
                aria-hidden
                className={cn(
                  'absolute top-1.5 flex h-[44px] w-[44px] items-center justify-center rounded-full pointer-events-none transition-transform',
                  DOCK_EASE,
                  pressedIndex === index && 'duration-150',
                  isActive ? 'text-white' : 'text-secondary'
                )}
                style={{ transform: `translateX(${iconX(index)}px)${pressedIndex === index ? ' scale(0.88)' : ''}` }}
              >
                {/* No `nav-icon-glow` (filter: drop-shadow) on the active icon:
                    a filter on an element that is simultaneously scaling forces
                    a re-raster every frame on the Android WebView. */}
                <Icon className={cn('h-5 w-5 transition-transform duration-200', isActive && 'scale-110')} />
              </span>
            );
          })}
        </nav>

      </div>
    </>
  );
}
