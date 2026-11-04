import { useMemo } from 'react';
import { useSchedule } from '@/features/schedule/use-schedule';
import { useCourseTracker } from '@/features/course-tracker/use-course-tracker';
import { useNow } from '@/hooks/use-now';
import { nowInBudapest } from '@/lib/date-utils';
import { dedupeTrips } from './trip-utils';
import { useFirstLegDeparture } from './use-trip-timeline';
import { useTripPlans } from './use-trip-plans';
import { buildCommuteSessions, resolveCommute, type CommuteKind } from './commute';
import type { TripPlan } from './types';

export interface CommuteState {
  commute: { kind: CommuteKind; trip: TripPlan } | null;
  departure: { minutes: number; isLive: boolean } | null;
  loading: boolean;
  answers: boolean;
}

/** The currently relevant commute: schedule-derived windows, the user's
 *  designated trips and the first leg's next departure, resolved for now. */
export function useCommute(): CommuteState {
  const tick = useNow(30_000);
  const { todayEvents } = useSchedule();
  const { isSessionHoliday } = useCourseTracker();
  const { data: trips = [] } = useTripPlans();

  const { nowMinutes, todayStr } = useMemo(() => {
    const budapest = nowInBudapest(new Date(tick));
    return { nowMinutes: budapest.hours * 60 + budapest.minutes, todayStr: budapest.ymd };
  }, [tick]);

  const sessions = useMemo(
    () =>
      buildCommuteSessions(
        todayEvents
          .filter(
            (event) =>
              !isSessionHoliday(
                event.course_name,
                event.course_code,
                event.semester,
                todayStr,
                event.start_time
              )
          )
          .map((event) => ({ startTime: event.start_time, endTime: event.end_time }))
      ),
    [todayEvents, isSessionHoliday, todayStr]
  );

  const commuteTrips = dedupeTrips(trips);
  const toUniTrip = commuteTrips.find((trip) => trip.direction === 'to_uni') ?? null;
  const toHomeTrip = commuteTrips.find((trip) => trip.direction === 'to_home') ?? null;

  const commute = useMemo(
    () => resolveCommute({ sessions, toUniTrip, toHomeTrip, nowMinutes }),
    [sessions, toUniTrip, toHomeTrip, nowMinutes]
  );
  const { departure, loading } = useFirstLegDeparture(commute?.trip.legs[0] ?? null, tick);
  // The commute card claims the top slot only while it can answer. A feed
  // still warming up keeps the slot (spinner); an empty readout demotes.
  const answers = commute != null && (departure != null || loading);

  return { commute, departure, loading, answers };
}
