import { TripCard } from '@/features/trips/trip-card';
import { useCommute } from '@/features/trips/use-commute';
import type { TripPlan } from '@/features/trips/types';
import { cn } from '@/lib/utils';

interface DashboardCommuteCardProps {
  className?: string;
  onOpenTrip: (trip: TripPlan) => void;
}

/** The currently relevant commute on the dashboard — the transit planner's own
 *  trip card for whichever direction the resolver picked (to uni or home), and
 *  nothing at all when no commute window is active. */
export function DashboardCommuteCard({ className, onOpenTrip }: DashboardCommuteCardProps) {
  const { commute } = useCommute();

  if (!commute) return null;

  return (
    <div className={cn('min-w-0 mb-6 lg:mb-0', className)}>
      <TripCard trip={commute.trip} onTap={() => onOpenTrip(commute.trip)} />
    </div>
  );
}
