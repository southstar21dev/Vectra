import { RiTimeLine, RiLoader4Line, RiMapPinLine, RiRefreshLine, RiTrainLine } from '@remixicon/react';
import { Button } from '@/components/ui/button';
import { FormGroup } from '@/components/ui/sheet-form';
import { SheetSectionTitle } from '@/features/trips/sheet-chrome';
import { formatClock } from '@/lib/date-utils';
import type { TimeFormat } from '@/features/settings/appearance-prefs';
import type { CatalogStop } from '../catalog-types';
import { CURATED_STOPS } from '../curated-stops';
import { RouteBadge } from '../route-badge';
import { FormattedRouteTitle } from '../formatted-route-title';
import { LiveSignalIcon } from '../transit-card';
import { ListRow, ListStatus } from '../tracker-list-row';
import { routeTypeMeta, isRouteMatch } from '../transit-utils';
import type { Arrival, FutarRoute } from '../types';
import type { TransitTracker } from '../use-transit';
import { GROUP_LINE_COLORS, METRO_FALLBACK_COLORS, metroViewLabel, type MetroView } from './constants';

const QUICK_PICK_GROUPS: MetroView[] = ['M1', 'M2', 'M3', 'M4', 'POPULAR'];

function GroupDot({ group }: { group: MetroView }) {
  if (group === 'POPULAR') {
    return (
      <span
        className="flex h-9 w-9 items-center justify-center rounded-full border border-primary text-base"
        style={{ backgroundColor: GROUP_LINE_COLORS.POPULAR, color: '#1a1a1a' }}
        aria-hidden="true"
      >
        🚏
      </span>
    );
  }
  const colors = METRO_FALLBACK_COLORS[group];
  return (
    <span
      className="flex h-9 w-9 items-center justify-center rounded-full border border-primary font-mono text-sm font-black"
      style={{ backgroundColor: `#${colors.color}`, color: `#${colors.textColor}` }}
      aria-hidden="true"
    >
      {group}
    </span>
  );
}

interface StopBrowserProps {
  searchQuery: string;
  filteredStops: CatalogStop[];
  catalog: { stops: CatalogStop[] } | null;
  catalogError: boolean;
  onRetryCatalog: () => void;
  onSelectStop: (stopId: string) => void;
  onOpenGroup: (group: MetroView) => void;
}

/** Root of By stop: search results when typing, otherwise the curated groups. */
export function StopBrowser({
  searchQuery,
  filteredStops,
  catalog,
  catalogError,
  onRetryCatalog,
  onSelectStop,
  onOpenGroup,
}: StopBrowserProps) {
  const query = searchQuery.trim();

  if (query) {
    if (filteredStops.length > 0) {
      return (
        <FormGroup className="overflow-hidden">
          {filteredStops.map((stop) => (
            <ListRow
              key={stop.id}
              onClick={() => onSelectStop(stop.id)}
              leading={<RiMapPinLine className="h-4 w-4 text-tint-blue" aria-hidden="true" />}
              title={stop.name}
              navigates
            />
          ))}
        </FormGroup>
      );
    }
    if (catalog) {
      return (
        <ListStatus>
          <p className="text-sm text-muted">No stops match “{query}”</p>
        </ListStatus>
      );
    }
    if (catalogError) {
      return (
        <ListStatus>
          <p className="text-sm text-muted">Couldn’t load the stop catalog</p>
          <Button variant="outline" size="sm" onClick={onRetryCatalog} className="rounded-full">
            <RiRefreshLine className="h-3.5 w-3.5" aria-hidden="true" /> Retry
          </Button>
        </ListStatus>
      );
    }
    return (
      <ListStatus role="status" aria-label="Loading stop catalog">
        <RiLoader4Line className="h-5 w-5 animate-spin text-muted" aria-hidden="true" />
      </ListStatus>
    );
  }

  return (
    <section className="space-y-3">
      <SheetSectionTitle>Quick picks</SheetSectionTitle>
      <FormGroup className="overflow-hidden">
        {QUICK_PICK_GROUPS.map((group) => {
          const count = CURATED_STOPS.filter((s) => s.group === group).length;
          return (
            <ListRow
              key={group}
              onClick={() => onOpenGroup(group)}
              leading={<GroupDot group={group} />}
              title={metroViewLabel(group)}
              subtitle={`${count} stops`}
              navigates
            />
          );
        })}
      </FormGroup>
      <p className="px-1 text-xs text-muted">Or search any stop in Budapest by name.</p>
    </section>
  );
}

/** The stops of one curated group (a metro line or the popular set). */
export function CuratedStopList({ group, onSelectStop }: { group: MetroView; onSelectStop: (stopId: string) => void }) {
  return (
    <FormGroup className="overflow-hidden">
      {CURATED_STOPS.filter((s) => s.group === group).map((stop) => (
        <ListRow
          key={stop.id}
          onClick={() => onSelectStop(stop.id)}
          leading={<RiMapPinLine className="h-4 w-4 text-tint-blue" aria-hidden="true" />}
          title={stop.name}
          navigates
        />
      ))}
    </FormGroup>
  );
}

interface StopRoutePickerProps {
  loadingRoutes: boolean;
  routesError: string | null;
  onRetry: () => void;
  routes: FutarRoute[] | null;
  selectedRouteId: string | null;
  onSelectRoute: (routeId: string) => void;
  existing: TransitTracker[];
  selectedRoute: FutarRoute | null;
  selectedHeadsign: string;
  onSelectHeadsign: (headsign: string) => void;
  allDeparturesCount: number;
  directions: { headSign: string; times: Arrival[] }[];
  timeFormat: TimeFormat;
}

/** After a stop is picked: the lines serving it, then the direction. */
export function StopRoutePicker({
  loadingRoutes,
  routesError,
  onRetry,
  routes,
  selectedRouteId,
  onSelectRoute,
  existing,
  selectedRoute,
  selectedHeadsign,
  onSelectHeadsign,
  allDeparturesCount,
  directions,
  timeFormat,
}: StopRoutePickerProps) {
  if (loadingRoutes) {
    return (
      <ListStatus role="status" aria-label="Loading departures">
        <RiLoader4Line className="h-5 w-5 animate-spin text-muted" aria-hidden="true" />
        <p className="text-sm text-muted">Loading departures…</p>
      </ListStatus>
    );
  }
  if (routesError) {
    return (
      <ListStatus>
        <p className="max-w-sm text-sm text-tint-red">Could not load departures: {routesError}</p>
        <Button variant="outline" size="sm" onClick={onRetry} className="rounded-full">
          <RiRefreshLine className="h-3.5 w-3.5" aria-hidden="true" /> Retry
        </Button>
      </ListStatus>
    );
  }

  return (
    <>
      <section className="space-y-3">
        <SheetSectionTitle>Lines at this stop</SheetSectionTitle>
        <FormGroup className="overflow-hidden">
          {(routes || []).map((route) => {
            const meta = routeTypeMeta(route.type);
            const Icon = meta.icon;
            const isTracked = existing.some((t) => isRouteMatch(t.route_id, route.id));
            return (
              <ListRow
                key={route.id}
                onClick={() => onSelectRoute(route.id)}
                selected={selectedRouteId === route.id}
                leading={<RouteBadge name={route.shortName} color={route.color} textColor={route.textColor} />}
                title={
                  <span className="inline-flex max-w-full items-center gap-1.5">
                    <Icon className="h-3.5 w-3.5 shrink-0 text-secondary" aria-hidden="true" />
                    <span className="truncate">{meta.label}</span>
                    {isTracked && (
                      <span className="shrink-0 rounded-full bg-surface-overlay px-2 py-0.5 text-[10px] font-bold text-secondary">
                        Tracked
                      </span>
                    )}
                  </span>
                }
                subtitle={
                  route.description ? (
                    <FormattedRouteTitle
                      title={route.description}
                      iconClassName="mx-1 inline-block h-2.5 w-2.5 shrink-0 align-middle text-muted/60"
                    />
                  ) : undefined
                }
              />
            );
          })}
        </FormGroup>
      </section>

      {selectedRoute && (
        <section className="space-y-3">
          <SheetSectionTitle>Direction</SheetSectionTitle>
          <FormGroup className="overflow-hidden">
            <ListRow
              onClick={() => onSelectHeadsign('')}
              selected={selectedHeadsign === ''}
              leading={<RiTrainLine className="h-4 w-4 text-secondary" aria-hidden="true" />}
              title="All directions"
              subtitle={allDeparturesCount > 0 ? `${allDeparturesCount} departures` : undefined}
            />
            {directions.map((dir) => (
              <ListRow
                key={dir.headSign}
                onClick={() => onSelectHeadsign(dir.headSign)}
                selected={selectedHeadsign === dir.headSign}
                leading={<span className="w-4" aria-hidden="true" />}
                title={dir.headSign}
                trailing={
                  <span className="flex items-center gap-1.5">
                    {dir.times.slice(0, 2).map((t, i) => (
                      <span
                        key={i}
                        className="inline-flex items-center gap-1 rounded-full bg-surface-overlay px-2 py-0.5 text-[11px] font-semibold tabular-nums text-secondary"
                      >
                        {t.isPredicted ? (
                          <LiveSignalIcon className="h-2.5 w-2.5 text-tint-emerald" />
                        ) : (
                          <RiTimeLine className="h-2.5 w-2.5 shrink-0 text-muted" aria-hidden="true" />
                        )}
                        {formatClock(t.arrivalMs, timeFormat)}
                      </span>
                    ))}
                  </span>
                }
              />
            ))}
          </FormGroup>
          <p className="px-1 text-xs text-muted">All directions shows every departure from this stop.</p>
        </section>
      )}
    </>
  );
}
