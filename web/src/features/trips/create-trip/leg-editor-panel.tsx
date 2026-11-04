import { useMemo, useState } from 'react';
import { RiErrorWarningLine, RiArrowLeftLine, RiCheckLine, RiArrowRightSLine, RiSearchLine, RiDeleteBinLine } from '@remixicon/react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { RouteBadge } from '@/features/transit/route-badge';
import { routeTypeMeta } from '@/features/transit/transit-utils';
import { cn } from '@/lib/utils';
import type { CatalogStop } from '@/features/transit/catalog-types';
import { groupForRoute, routesThrough, servesPair, suggestRoutes, isExcludedFromSuggestions, type RouteSuggestion } from '../route-suggest';
import { stopsAfter } from './journey-options';
import { ROUTE_TYPE_FILTERS, typeMatchesFilter, type DisplayRoute } from './constants';
import type { PlannerCatalog } from './use-planner-catalog';

interface LegEditorPanelProps {
  catalog: PlannerCatalog;
  /** Where this ride boards. */
  from: CatalogStop;
  /** Where this ride should end (edit), or the journey's destination (append). */
  to: CatalogStop;
  /** `edit` keeps both stops fixed; `append` may end anywhere along a line. */
  mode: 'edit' | 'append';
  /** Lines already on the leg (edit mode). */
  initialRouteIds?: string[];
  onConfirm: (routes: RouteSuggestion[], to: CatalogStop) => void;
  /** Present when the ride can be removed (the journey's last ride). */
  onRemove?: () => void;
}

function toSuggestion(route: DisplayRoute): RouteSuggestion {
  return {
    routeId: route.id,
    routeIds: groupForRoute(route.id),
    shortName: route.shortName,
    displayName: route.displayName,
    type: route.type,
    color: route.color,
    textColor: route.textColor,
    headsign: '',
  };
}

const lineRowClass =
  'group flex w-full items-center gap-3 rounded-2xl px-3 py-2.5 text-left transition-colors cursor-pointer hover:bg-surface-overlay active:scale-[0.99] focus-visible:outline focus-visible:outline-2 focus-visible:outline-accent-primary';

/**
 * Choosing the line(s) for one ride. Lines that run the whole way can be
 * combined (either/or: whichever leaves first is taken). In append mode a
 * line that stops short asks where you get off, so a journey can be chained
 * ride by ride when no suggestion fits.
 */
export function LegEditorPanel({ catalog, from, to, mode, initialRouteIds = [], onConfirm, onRemove }: LegEditorPanelProps) {
  const { routes, index, stops } = catalog;
  // Lines running the whole stretch, plus any line already on the leg that
  // the suggestions don't list (picked from the catalog earlier), so editing
  // never silently drops it.
  const [direct] = useState<RouteSuggestion[]>(() => {
    const suggested = suggestRoutes(routes, index, from.name, to.name);
    const covered = new Set(suggested.flatMap((s) => s.routeIds));
    const current = routes
      .filter((r) => initialRouteIds.includes(r.id) && !covered.has(r.id))
      .map(toSuggestion)
      .filter((s, i, all) => all.findIndex((x) => x.routeIds.join() === s.routeIds.join()) === i);
    // Lines already on the leg lead the list.
    // Lines already on the leg lead; night lines (N-prefixed) trail the daytime ones.
    const onLeg = (x: RouteSuggestion) => x.routeIds.some((id) => initialRouteIds.includes(id));
    const night = (x: RouteSuggestion) => /^N\d/i.test(x.shortName);
    const rest = suggested.filter((x) => !onLeg(x));
    return [...current, ...suggested.filter(onLeg), ...rest.filter((x) => !night(x)), ...rest.filter(night)];
  });
  const [selected, setSelected] = useState<RouteSuggestion[]>(() =>
    direct.filter((s) => s.routeIds.some((id) => initialRouteIds.includes(id)))
  );
  const [query, setQuery] = useState('');
  const [typeFilter, setTypeFilter] = useState<string>('ALL');
  const [error, setError] = useState('');
  // Append mode: the line picked that does not reach the destination.
  const [boarding, setBoarding] = useState<RouteSuggestion | null>(null);
  const [stopQuery, setStopQuery] = useState('');

  const directIds = useMemo(() => new Set(direct.flatMap((s) => s.routeIds)), [direct]);
  const throughHere = useMemo(
    () => (mode === 'append' ? routesThrough(routes, index, from.name).filter((s) => !s.routeIds.some((id) => directIds.has(id))) : []),
    [mode, routes, index, from.name, directIds]
  );

  const q = query.trim().toLowerCase();
  const searchResults = useMemo(() => {
    if (!q && typeFilter === 'ALL') return [];
    return routes
      .filter((r) => !(isExcludedFromSuggestions(r) && r.id === 'BKK_R3299'))
      .filter((r) => typeMatchesFilter(r.type, typeFilter))
      .filter((r) => !q || r.shortName.toLowerCase().includes(q) || r.displayName.toLowerCase().includes(q))
      .slice(0, 40);
  }, [routes, q, typeFilter]);

  const isSelected = (s: Pick<RouteSuggestion, 'routeId' | 'routeIds'>) =>
    selected.some((x) => x.routeIds.some((id) => s.routeIds.includes(id)));

  const toggle = (s: RouteSuggestion) => {
    setError('');
    setSelected((prev) => (isSelected(s) ? prev.filter((x) => !x.routeIds.some((id) => s.routeIds.includes(id))) : [...prev, s]));
  };

  const pickCatalogLine = (route: DisplayRoute) => {
    const s = toSuggestion(route);
    if (servesPair(index, s.routeIds, from.name, to.name)) {
      toggle(direct.find((d) => d.routeId === s.routeId) ?? s);
      return;
    }
    if (mode === 'append' && stopsAfter(index, s.routeIds, from, stops).length > 0) {
      setBoarding(s);
      return;
    }
    setError(
      mode === 'append'
        ? `${route.shortName} doesn’t stop at ${from.name}.`
        : `${route.shortName} doesn’t run from ${from.name} to ${to.name} in that direction.`
    );
  };

  if (boarding) {
    const sq = stopQuery.trim().toLowerCase();
    const after = stopsAfter(index, boarding.routeIds, from, stops).filter((s) => !sq || s.name.toLowerCase().includes(sq));
    return (
      <div className="space-y-4">
        <button
          type="button"
          onClick={() => setBoarding(null)}
          className="inline-flex items-center gap-1.5 rounded-full py-1 text-xs font-semibold text-secondary hover:text-primary cursor-pointer"
        >
          <RiArrowLeftLine className="h-3.5 w-3.5" /> Other lines
        </button>
        <div className="flex items-center gap-3">
          <RouteBadge name={boarding.shortName} color={boarding.color} textColor={boarding.textColor} size="md" />
          <div className="min-w-0">
            <h3 className="text-base font-extrabold tracking-tight text-primary">Where do you get off?</h3>
            <p className="truncate text-xs text-secondary">Boarding at {from.name}</p>
          </div>
        </div>
        <Input icon={RiSearchLine} value={stopQuery} onChange={(e) => setStopQuery(e.target.value)} placeholder="Filter stops" aria-label="Filter stops" />
        <ol className="relative">
          {after.map((stop) => (
            <li key={stop.id}>
              <button type="button" onClick={() => onConfirm([boarding], stop)} className={lineRowClass}>
                <span className="h-2.5 w-2.5 shrink-0 rounded-full border-2" style={{ borderColor: `#${boarding.color}` }} />
                <span className="min-w-0 flex-1 truncate text-sm font-semibold text-primary">{stop.name}</span>
                <RiArrowRightSLine className="h-4 w-4 shrink-0 text-muted" />
              </button>
            </li>
          ))}
        </ol>
        {after.length === 0 && <p className="px-3 text-xs text-muted">No stop matches “{stopQuery.trim()}”.</p>}
      </div>
    );
  }

  const renderLine = (s: RouteSuggestion, action: 'toggle' | 'board') => {
    const on = action === 'toggle' && isSelected(s);
    const headsigns = s.headsigns?.length ? s.headsigns : s.headsign ? [s.headsign] : [];
    return (
      <button
        key={s.routeId}
        type="button"
        aria-pressed={action === 'toggle' ? on : undefined}
        onClick={() => (action === 'toggle' ? toggle(s) : setBoarding(s))}
        className={cn(lineRowClass, on && 'bg-accent-primary/10 ring-1 ring-inset ring-accent-primary/40 hover:bg-accent-primary/15')}
      >
        <span className="flex w-14 shrink-0 justify-center">
          <RouteBadge name={s.shortName} color={s.color} textColor={s.textColor} size="sm" />
        </span>
        <span className="min-w-0 flex-1">
          <span className="block truncate text-sm font-semibold text-primary">{s.displayName}</span>
          {headsigns.length > 0 && <span className="mt-0.5 block truncate text-xs text-muted">towards {headsigns.join(' · ')}</span>}
        </span>
        {action === 'toggle' ? (
          <span
            className={cn(
              'flex h-6 w-6 shrink-0 items-center justify-center rounded-full border transition-colors',
              on ? 'border-accent-primary bg-accent-primary text-white' : 'border-subtle text-transparent'
            )}
          >
            <RiCheckLine className="h-3.5 w-3.5" />
          </span>
        ) : (
          <RiArrowRightSLine className="h-4 w-4 shrink-0 text-muted" />
        )}
      </button>
    );
  };

  return (
    <div className="space-y-6">
      <section aria-labelledby="direct-lines" className="space-y-1">
        <h3 id="direct-lines" className="text-base font-extrabold tracking-tight text-primary">
          {mode === 'append' ? `Goes all the way` : 'Lines on this stretch'}
        </h3>
        <p className="pb-2 text-xs text-secondary">Choose more than one and Vectra takes whichever leaves first.</p>
        {direct.length > 0 ? (
          direct.map((s) => renderLine(s, 'toggle'))
        ) : (
          <p className="rounded-2xl panel-glass px-4 py-3 text-xs text-secondary">No single line runs the whole way from here.</p>
        )}
      </section>

      {throughHere.length > 0 && (
        <section aria-labelledby="through-lines" className="space-y-1">
          <h3 id="through-lines" className="text-base font-extrabold tracking-tight text-primary">Other lines from {from.name}</h3>
          <p className="pb-2 text-xs text-secondary">Ride part of the way, then choose where you get off.</p>
          {throughHere.slice(0, 12).map((s) => renderLine(s, 'board'))}
        </section>
      )}

      <section aria-labelledby="all-lines" className="space-y-3">
        <h3 id="all-lines" className="text-base font-extrabold tracking-tight text-primary">Any line</h3>
        <Input icon={RiSearchLine} value={query} onChange={(e) => setQuery(e.target.value)} placeholder="Line number or name" aria-label="Search every line" />
        <div className="-mx-1 flex gap-1.5 overflow-x-auto px-1 pb-1 no-scrollbar">
          {ROUTE_TYPE_FILTERS.map((type) => {
            const active = typeFilter === type;
            return (
              <button
                key={type}
                type="button"
                aria-pressed={active}
                onClick={() => setTypeFilter(type)}
                className={cn(
                  'h-8 shrink-0 rounded-full px-3 text-xs font-semibold transition-colors cursor-pointer active:scale-[0.98]',
                  active ? 'bg-accent-primary text-white' : 'bg-surface-overlay text-secondary hover:text-primary'
                )}
              >
                {type === 'ALL' ? 'All' : routeTypeMeta(type).label}
              </button>
            );
          })}
        </div>
        {searchResults.length > 0 && (
          <div>
            {searchResults.map((route) => {
              const s = toSuggestion(route);
              const on = isSelected(s);
              return (
                <button key={route.id} type="button" onClick={() => pickCatalogLine(route)} className={cn(lineRowClass, on && 'bg-accent-primary/10 ring-1 ring-inset ring-accent-primary/40')}>
                  <span className="flex w-14 shrink-0 justify-center">
                    <RouteBadge name={route.shortName} color={route.color} textColor={route.textColor} size="sm" />
                  </span>
                  <span className="min-w-0 flex-1">
                    <span className="block truncate text-sm font-semibold text-primary">{route.displayName}</span>
                    <span className="mt-0.5 block text-xs text-muted">{routeTypeMeta(route.type).label}</span>
                  </span>
                  {on ? <RiCheckLine className="h-4 w-4 shrink-0 text-accent-primary" /> : <RiArrowRightSLine className="h-4 w-4 shrink-0 text-muted" />}
                </button>
              );
            })}
          </div>
        )}
        {(q || typeFilter !== 'ALL') && searchResults.length === 0 && <p className="px-3 text-xs text-muted">No line matches.</p>}
      </section>

      {error && (
        <p role="alert" className="flex items-start gap-2 rounded-2xl bg-tint-red px-3.5 py-3 text-xs font-medium text-tint-red">
          <RiErrorWarningLine className="mt-0.5 h-4 w-4 shrink-0" />
          {error}
        </p>
      )}

      <div className="sticky bottom-0 -mx-5 space-y-2 bg-surface-secondary px-5 pb-1 pt-3 sm:-mx-6 sm:px-6">
        <Button type="button" size="lg" className="h-12 w-full rounded-2xl" disabled={selected.length === 0} onClick={() => onConfirm(selected, to)}>
          {selected.length === 0
            ? 'Choose a line'
            : selected.length === 1
              ? `Ride ${selected[0].shortName}`
              : `Ride ${selected.map((s) => s.shortName).join(' or ')}`}
        </Button>
        {onRemove && (
          <Button type="button" variant="ghost" className="h-10 w-full rounded-2xl text-tint-red hover:text-tint-red" onClick={onRemove}>
            <RiDeleteBinLine className="h-4 w-4" /> Remove this ride
          </Button>
        )}
      </div>
    </div>
  );
}
