# Workspace Rules for Vectra

## UI Design & Spacing Rules
- **Pill Shape & Badge Vertical Clearance**: Always enforce generous vertical clearance (at least 10px-14px / `space-y-3`, `mt-3`, `pt-2.5`, `my-2.5`, or `mb-3`) between pill shapes (`rounded-full` badges, pills, buttons) and adjacent text or inputs above/below them. Never use tight 2px-4px (`mt-0.5`, `mt-1`) margins directly against pill container boundaries.
- **Dropdown Select Chevron Standard**: All dropdown `<select>` controls across the application MUST use the `<Select>` component from `@/components/ui/select` (or enforce `rounded-full border border-white/10 bg-white/[0.04] pl-4 pr-9 text-center [text-align-last:center]` with inset `ChevronDown` at `right-3.5`). Never render browser native un-styled select elements with ugly default arrows.
- **Mobile Responsive Full-Width Grids**: Button groups in action toolbars or settings forms on mobile must stretch symmetrically using `grid grid-cols-2` or `w-full items-stretch` rather than clustering asymmetrical left-aligned inline items.
- **Offline First**: All schedule data must be cached in `localStorage` (`vectra_schedule_cache_events`) so the timetable, agenda, and semester tabs are 100% functional offline.

## Dialogs & Overlays
- Any dialog mounted conditionally (`{state && <Dialog open ... />}`) MUST keep it mounted through its close animation using `useDialogPresence` (`src/hooks/use-dialog-presence.ts`) and pass `onOpenChange`/`onExited` through. Clearing the state immediately skips the exit animation on every platform.
- A dialog component must accept an explicit `open` prop (never derive open from its data prop), must forward `onExited` to its `Dialog`, and must not rely on data props that clear before the exit finishes. Parents keep the data alive with `useDialogPresence` and clear it in `onExited`; components that early-return on null data otherwise skip their close animation.
- Prefer always-mounted dialogs driven by an `open` prop when their data can be retained; pass `onExited` when the parent clears data on close.
- Full-screen dialog motion and corners come from `dialog-presentation.ts` (`--screen-radius`); do not hand-roll exit animations.
- Map-backed dialogs must expose readiness (`onReady`) from the map component and cover the content with `LoadingOverlay` until both data and the map are ready — never hard-swap from a spinner to a blank canvas.
- Do not wrap a whole dialog in `lazy`/`Suspense` with a full-screen fallback: opening then shows a fallback and a second mount animation. Mount the dialog shell immediately and load heavy inner content behind its internal `LoadingOverlay` (the nearby explorer and map dialogs follow this).
