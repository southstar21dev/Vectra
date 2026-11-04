import { useMemo } from 'react';
import { TripCard } from './trip-card';
import { useTripPlans } from './use-trip-plans';
import { dedupeTrips } from './trip-utils';
import type { TripPlan } from './types';
import { EmptyState } from '@/components/ui/empty-state';

interface TripPlannerSectionProps {
  onOpenDetail: (trip: TripPlan) => void;
}

export function TripPlannerSection({ onOpenDetail }: TripPlannerSectionProps) {
  const { data: trips = [] } = useTripPlans();

  const dedupedTrips = useMemo(() => dedupeTrips(trips), [trips]);

  if (dedupedTrips.length === 0) {
    return (
    
      <EmptyState
          className="mt-0 sm:mt-0"
          emoji="🗾"
          title="No saved trips yet"
          loadingText="checking your saved trackers…"/>
    );
  }

  return (
    <ul className="space-y-2">
      {dedupedTrips.map((trip: TripPlan) => (
        <li key={trip.id}>
          <TripCard variant="row" trip={trip} onTap={() => onOpenDetail(trip)} />
        </li>
      ))}
    </ul>
  );
}
