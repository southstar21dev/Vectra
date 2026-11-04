import { useState, Suspense, lazy } from 'react';
import { RiApps2Line, RiCheckLine, RiLoader4Line, RiRefreshLine, RiSearchLine } from '@remixicon/react';
import { Button } from '@/components/ui/button';
import { LoadingOverlay } from '@/components/ui/loading-overlay';
import { StatusLabel } from '@/components/ui/status-label';
import { Input } from '@/components/ui/input';
import { FormGroup, SHEET_INPUT_CLASS } from '@/components/ui/sheet-form';
import { SheetSectionTitle, SHEET_GUTTER } from '@/features/trips/sheet-chrome';
import { cn } from '@/lib/utils';
import type { CatalogRoute } from '../catalog-types';
import { RouteBadge } from '../route-badge';
import { FormattedRouteTitle } from '../formatted-route-title';
import { MapFallback } from '../map/map-fallback';
import type { RoutePreviewVehicle } from '../route-preview-map';
import { DirectionSelect, ListRow, ListStatus, StopCountPill } from '../tracker-list-row';

// Lazy: the route preview pulls in maplibre, kept out of the eager bundle.
const LazyRoutePreviewMap = lazy(() => import('../route-preview-map').then((m) => ({ default: m.RoutePreviewMap })));
import type { LatLon } from '../route-geometry';
import { formatRouteDisplayName, MAX_TRACKER_STOPS, routeTypeMeta, isRouteMatch } from '../transit-utils';
import type { FutarRoute, FutarStop, TrackerStop } from '../types';
import type { TransitTracker } from '../use-transit';
import { ROUTE_TYPE_FILTERS } from './constants';

interface LineBrowserProps {
  routeTypeFilter: string;
  setRouteTypeFilter: (value: string) => void;
  catalog: { routes: CatalogRoute[] } | null;
  catalogError: boolean;
  onRetryCatalog: () => void;
  filteredRoutes: FutarRoute[];
  routeQuery: string;
  existing: TransitTracker[];
  onSelectRoute: (route: CatalogRoute) => void;
}

/** Root of By line: a type filter strip and the catalog as one list. */
export function LineBrowser({
  routeTypeFilter,
  setRouteTypeFilter,
  catalog,
  catalogError,
  onRetryCatalog,
  filteredRoutes,
  routeQuery,
  existing,
  onSelectRoute,
}: LineBrowserProps) {
  return (
    <>
      {/* The strip bleeds to the sheet edge so the last chip scrolls out
          under the gutter instead of clipping on it. */}
      <div role="radiogroup" aria-label="Line type" className={cn('-mx-5 flex gap-2 overflow-x-auto no-scrollbar sm:-mx-6', SHEET_GUTTER)}>
        {ROUTE_TYPE_FILTERS.map((t) => {
          const active = routeTypeFilter === t;
          const Icon = t === 'ALL' ? RiApps2Line : routeTypeMeta(t).icon;
          return (
            <button
              key={t}
              type="button"
              role="radio"
              aria-checked={active}
              onClick={() => setRouteTypeFilter(t)}
              className={cn(
                'flex h-9 shrink-0 items-center gap-1.5 rounded-full pl-3 pr-3.5 text-sm transition-[color,background-color,box-shadow,transform] cursor-pointer active:scale-95 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent-primary',
                active
                  ? 'border border-white/20 bg-accent-primary font-bold text-white shadow-[inset_0_1px_0_rgba(255,255,255,0.3)]'
                  : 'card-glass font-semibold text-secondary hover:text-primary'
              )}
            >
              <Icon className={cn('h-4 w-4', active ? 'opacity-90' : 'text-muted')} aria-hidden="true" />
              {t === 'ALL' ? 'All' : routeTypeMeta(t).label}
            </button>
          );
        })}
      </div>

      {!catalog ? (
        catalogError ? (
          <ListStatus>
            <p className="text-sm text-muted">Couldn’t load the line catalog</p>
            <Button variant="outline" size="sm" onClick={onRetryCatalog} className="rounded-full">
              <RiRefreshLine className="h-3.5 w-3.5" aria-hidden="true" /> Retry
            </Button>
          </ListStatus>
        ) : (
          <ListStatus role="status" aria-label="Loading line catalog">
            <RiLoader4Line className="h-5 w-5 animate-spin text-muted" aria-hidden="true" />
          </ListStatus>
        )
      ) : filteredRoutes.length === 0 ? (
        <ListStatus>
          <p className="text-sm text-muted">
            {routeQuery.trim() ? <>No lines match “{routeQuery.trim()}”</> : 'No lines of this type.'}
          </p>
        </ListStatus>
      ) : (
        <FormGroup className="overflow-hidden">
          {filteredRoutes.map((route) => {
            const meta = routeTypeMeta(route.type);
            const Icon = meta.icon;
            const isTracked = existing.some((t) => isRouteMatch(t.route_id, route.id));
            return (
              <ListRow
                key={route.id}
                onClick={() => onSelectRoute(route)}
                leading={
                  <RouteBadge size="sm" name={formatRouteDisplayName(route.shortName, route.id)} color={route.color} textColor={route.textColor} />
                }
                title={
                  route.description ? (
                    <FormattedRouteTitle
                      title={route.description}
                      iconClassName="mx-1 inline-block h-3 w-3 shrink-0 align-middle text-muted/60"
                    />
                  ) : (
                    meta.label
                  )
                }
                subtitle={
                  <span className="inline-flex items-center gap-1.5">
                    <Icon className="h-3 w-3 shrink-0 text-muted" aria-hidden="true" />
                    {meta.label}
                    {isTracked && (
                      <span className="rounded-full bg-surface-overlay px-2 py-0.5 text-[10px] font-bold text-secondary">Tracked</span>
                    )}
                  </span>
                }
                navigates
              />
            );
          })}
        </FormGroup>
      )}
    </>
  );
}

/** Stops shared with another tram line, called out next to the stop name. */
function stopNote(name: string): string | null {
  if (name.includes('Szerémi') || name.includes('Újbuda-központ')) return 'Tram 4';
  if (name.includes('Karinthy') || name.includes('Móricz')) return 'Tram 6';
  if (name.includes('Izraelita')) return 'Tram 28';
  return null;
}

const MAP_HEIGHT = 208;

interface LineStopPickerProps {
  browseRoute: CatalogRoute;
  selectedLineStops: TrackerStop[];
  routePolylines: LatLon[][];
  routeStops: FutarStop[] | null;
  previewVehicles: RoutePreviewVehicle[];
  onToggleStop: (stop: FutarStop) => void;
  stopQuery: string;
  setStopQuery: (value: string) => void;
  loadingRouteStops: boolean;
  routeStopsError: string | null;
  onRetry: () => void;
  filteredRouteStops: FutarStop[];
  routeHeadsigns: string[];
  onDirectionChange: (stopId: string, headsign: string) => void;
}

/** After a line is picked: the route on the map, then its stops to tick. */
export function LineStopPicker({
  browseRoute,
  selectedLineStops,
  routePolylines,
  routeStops,
  previewVehicles,
  onToggleStop,
  stopQuery,
  setStopQuery,
  loadingRouteStops,
  routeStopsError,
  onRetry,
  filteredRouteStops,
  routeHeadsigns,
  onDirectionChange,
}: LineStopPickerProps) {
  const [routeMapReady, setRouteMapReady] = useState(false);
  const atLimit = selectedLineStops.length >= MAX_TRACKER_STOPS;

  return (
    <>
      {/* The route leads, edge to edge, the way the trip sheet opens on its map. */}
      <div className="relative" style={{ height: MAP_HEIGHT }}>
        <Suspense fallback={<MapFallback />}>
          <LazyRoutePreviewMap
            polylines={routePolylines}
            stops={(routeStops ?? []).map((s) => ({
              id: s.id,
              name: s.name,
              lat: s.lat,
              lon: s.lon,
              selected: selectedLineStops.some((sel) => sel.stop_id === s.id),
            }))}
            vehicles={previewVehicles}
            routeColor={browseRoute.color || '3b82f6'}
            height={MAP_HEIGHT}
            onReady={() => setRouteMapReady(true)}
            onSelectStop={(stopId) => {
              const stop = (routeStops ?? []).find((s) => s.id === stopId);
              if (stop) onToggleStop(stop);
            }}
          />
        </Suspense>
        <LoadingOverlay show={loadingRouteStops || !routeMapReady} label="Loading route map…" />
        <div aria-hidden className="pointer-events-none absolute inset-x-0 bottom-0 h-12 bg-gradient-to-t from-surface-secondary to-transparent" />
      </div>

      <section className={cn('space-y-3 pt-4', SHEET_GUTTER)}>
        <SheetSectionTitle action={<StopCountPill count={selectedLineStops.length} />}>Stops to track</SheetSectionTitle>
        <Input
          icon={RiSearchLine}
          value={stopQuery}
          onChange={(e) => setStopQuery(e.target.value)}
          placeholder={`Search stops on ${browseRoute.shortName}…`}
          aria-label={`Search stops on ${browseRoute.shortName}`}
          className={SHEET_INPUT_CLASS}
        />

        {loadingRouteStops ? (
          <ListStatus role="status" aria-label="Loading stops">
            <RiLoader4Line className="h-5 w-5 animate-spin text-muted" aria-hidden="true" />
          </ListStatus>
        ) : routeStopsError ? (
          <ListStatus>
            <p className="max-w-sm text-sm text-tint-red">Could not load stops: {routeStopsError}</p>
            <Button variant="outline" size="sm" onClick={onRetry} className="rounded-full">
              <RiRefreshLine className="h-3.5 w-3.5" aria-hidden="true" /> Retry
            </Button>
          </ListStatus>
        ) : filteredRouteStops.length === 0 ? (
          <ListStatus>
            <p className="text-sm text-muted">No stops match “{stopQuery.trim()}”</p>
          </ListStatus>
        ) : (
          <FormGroup className="overflow-hidden">
            {filteredRouteStops.map((stop) => {
              const selectedEntry = selectedLineStops.find((s) => s.stop_id === stop.id);
              const isSelected = !!selectedEntry;
              const blocked = !isSelected && atLimit;
              const note = stopNote(stop.name);
              return (
                <div key={stop.id} className={cn(isSelected && 'bg-accent-primary/8')}>
                  <button
                    type="button"
                    role="checkbox"
                    aria-checked={isSelected}
                    disabled={blocked}
                    onClick={() => onToggleStop(stop)}
                    className={cn(
                      'flex w-full items-center gap-3 px-4 py-3 text-left transition-colors focus-visible:outline focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-accent-primary',
                      blocked ? 'cursor-not-allowed opacity-50' : 'cursor-pointer hover:bg-surface-overlay/70 active:bg-surface-overlay'
                    )}
                  >
                    <span
                      aria-hidden="true"
                      className={cn(
                        'flex h-5 w-5 shrink-0 items-center justify-center rounded-md border transition-colors',
                        isSelected ? 'border-accent-primary bg-accent-primary text-white' : 'border-subtle bg-surface-secondary text-transparent'
                      )}
                    >
                      <RiCheckLine className="h-3.5 w-3.5" />
                    </span>
                    <span className="min-w-0 flex-1 truncate text-sm font-semibold text-primary">{stop.name}</span>
                    {note && (
                      <StatusLabel tone="amber">{note}</StatusLabel>
                    )}
                  </button>
                  {isSelected && routeHeadsigns.length > 0 && (
                    <div className="px-4 pb-3 pl-12">
                      <DirectionSelect
                        value={selectedEntry.headsign || ''}
                        headsigns={routeHeadsigns}
                        onChange={(h) => onDirectionChange(stop.id, h)}
                        label={`Direction at ${stop.name}`}
                      />
                    </div>
                  )}
                </div>
              );
            })}
          </FormGroup>
        )}
        {atLimit && !loadingRouteStops && !routeStopsError && (
          <p className="px-1 text-xs text-muted">That’s the most stops a line can track. Untick one to swap it.</p>
        )}
      </section>
    </>
  );
}
