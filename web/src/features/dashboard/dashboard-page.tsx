import { lazy, Suspense, useMemo, useState } from 'react';
import { useSchedule } from '@/features/schedule/use-schedule';
import { useScheduleItems, ScheduleItemConflictError } from '@/features/schedule/use-schedule-items';
import { ScheduleItemEditor } from '@/features/schedule/schedule-item-editor';
import { getScheduleItemOccurrencesForDate } from '@/features/schedule/schedule-occurrences';
import type { ScheduleItem } from '@/features/schedule/schedule-conflicts';
import type { StoredScheduleItem } from '@/features/schedule/schedule-items-store';
import { ScheduleDayTimeline } from '@/features/schedule/schedule-day-timeline';
import { PostageStamp } from '@/components/ui/postage-stamp';
import { isExamEvent } from '@/features/schedule/exam-detection';
import { getIntensiveCourseKeys } from '@/features/course-tracker/attendance-calc';
import { CourseTrackerModal } from '@/features/course-tracker/components/course-tracker-modal';
import { useTransitTrackers } from '@/features/transit/use-transit';
import { WeatherCard } from '@/features/weather/weather-card';
import { HolidayCard } from '@/features/holidays/holiday-card';
import { DeferredTransitWidget } from '@/features/dashboard/deferred-transit-widget';
import { DashboardCommuteCard } from '@/features/dashboard/dashboard-commute';
import { Reveal } from '@/components/ui/reveal';
import { useCourseTracker } from '@/features/course-tracker/use-course-tracker';
import { useLazyOpen } from '@/hooks/use-lazy-open';
import type { TransitTracker } from '@/features/transit/use-transit';
import type { ScheduleEvent } from '@/features/schedule/use-schedule';
import type { TripPlan } from '@/features/trips/types';
import { nowInBudapest } from '@/lib/date-utils';
import { useNow } from '@/hooks/use-now';
import { toast } from 'sonner';
import { OfflineBanner } from '@/components/ui/offline-banner';
import { useDialogPresence } from '@/hooks/use-dialog-presence';

// Dialogs are opened on demand and one of them pulls the map libraries, so
// they load as their own chunks instead of weighing down the dashboard.
const LivePositionDialog = lazy(() =>
  import('@/features/transit/live-position/live-position-dialog').then((m) => ({ default: m.LivePositionDialog }))
);
const TripDetailDialog = lazy(() =>
  import('@/features/trips/trip-detail-dialog').then((m) => ({ default: m.TripDetailDialog }))
);
const CreateTripDialog = lazy(() =>
  import('@/features/trips/create-trip/create-trip-dialog').then((m) => ({ default: m.CreateTripDialog }))
);

export function DashboardPage() {
  const { todayEvents, events, isOffline } = useSchedule();
  const scheduleItemsState = useScheduleItems(events);
  const {
    items: scheduleItems,
    conflictsByItemId,
    conflictsByEventId,
    needsAcknowledgmentIds,
    syncConflictIds,
    createItem,
    updateItem,
    toggleTaskCompleted,
    resolveSyncConflict,
  } = scheduleItemsState;
  const { isSessionHoliday } = useCourseTracker();
  const { data: transitTrackers = [] } = useTransitTrackers();
  const favoriteTrackers = useMemo(
    () => transitTrackers.filter((t) => t.is_favorite),
    [transitTrackers]
  );
  const liveTracker = useDialogPresence<TransitTracker>();
  const courseTracker = useDialogPresence<ScheduleEvent>();
  const [editingScheduleItem, setEditingScheduleItem] = useState<StoredScheduleItem | null>(null);
  const [showScheduleItemEditor, setShowScheduleItemEditor] = useState(false);
  const liveDialogLoaded = useLazyOpen(Boolean(liveTracker.value));
  const tripDetail = useDialogPresence<TripPlan>();
  const [editingTrip, setEditingTrip] = useState<TripPlan | undefined>(undefined);
  const tripDetailLoaded = useLazyOpen(tripDetail.value != null);
  const createTripLoaded = useLazyOpen(editingTrip != null);

  // Minute clock so the header date and today's lists roll over at midnight.
  const clock = useNow(60_000);
  const todayDate = new Date(clock);
  const now = nowInBudapest(todayDate);
  const todayStr = now.ymd;
  const todayFullDate = new Intl.DateTimeFormat(undefined, { timeZone: 'Europe/Budapest', weekday: 'long', month: 'long', day: 'numeric' }).format(todayDate);
  const todayWeekdayShort = new Intl.DateTimeFormat(undefined, { timeZone: 'Europe/Budapest', weekday: 'short' }).format(todayDate);
  // Short month for the calendar leaf ("Oct", Hungarian "okt.") without the abbreviation dot.
  const todayMonthShort = new Intl.DateTimeFormat(undefined, { timeZone: 'Europe/Budapest', month: 'short' }).format(todayDate).replace(/\.$/, '');
  const todayDayOfMonth = new Intl.DateTimeFormat(undefined, { timeZone: 'Europe/Budapest', day: 'numeric' }).format(todayDate);

  const intensiveCourseKeys = useMemo(() => getIntensiveCourseKeys(events), [events]);
  const activeTodayEvents = useMemo(
    // Pass the start time: a cancelled 08:00 lecture must not hide the same
    // course's 14:00 seminar.
    () => todayEvents.filter((event) => !isSessionHoliday(event.course_name, event.course_code, event.semester, todayStr, event.start_time)),
    [todayEvents, isSessionHoliday, todayStr]
  );
  const todayItems = useMemo(
    () => getScheduleItemOccurrencesForDate(scheduleItems, todayStr),
    [scheduleItems, todayStr]
  );

  const handleScheduleItemClick = (item: ScheduleItem) => {
    const stored = scheduleItems.find((entry) => entry.id === item.id);
    if (!stored) return;
    setEditingScheduleItem(stored);
    setShowScheduleItemEditor(true);
  };

  const handleSaveScheduleItem = async (item: ScheduleItem) => {
    try {
      if (editingScheduleItem) await updateItem(item);
      else await createItem(item);
      toast.success(item.type === 'shift' ? 'Work shift saved' : 'Task saved');
      setShowScheduleItemEditor(false);
      setEditingScheduleItem(null);
    } catch (error) {
      toast.error(error instanceof Error ? error.message : 'Could not save this schedule item.');
      throw error;
    }
  };

  const handleToggleScheduleTask = async (item: ScheduleItem) => {
    try {
      await toggleTaskCompleted(item);
    } catch (error) {
      if (error instanceof ScheduleItemConflictError) {
        const stored = scheduleItems.find((entry) => entry.id === item.id);
        if (stored) {
          setEditingScheduleItem({ ...stored, completed: false });
          setShowScheduleItemEditor(true);
        }
      } else {
        toast.error(error instanceof Error ? error.message : 'Could not update task.');
      }
    }
  };

  return (
    <>
      {isOffline && (
        <OfflineBanner message="No internet connection — Dashboard operating in cached offline mode" />
      )}

      {/* Every card below fades and rises into place, staggered in reading
          order — the same entrance every page uses (see `Reveal`), held until
          the boot splash clears so on a cold start it plays as the splash
          fades rather than behind it. */}

      {/* Weather — slim full-width strip */}
      <Reveal index={0}>
        <WeatherCard />
      </Reveal>

      {/* Hungarian holidays */}
      <Reveal index={1}>
        <HolidayCard />
      </Reveal>

      {/* Commute — mobile slot between holidays and the schedule/map grid */}
      <Reveal index={2} className="lg:hidden">
        <DashboardCommuteCard onOpenTrip={tripDetail.openWith} />
      </Reveal>

      {/* TODAY'S CLASSES + MAP + TRANSIT */}
      <div className={`mb-8 lg:grid lg:gap-8 lg:items-start lg:grid-cols-2`}>          {/* LEFT: Schedule */}
          <Reveal as="section" index={3} className="relative mt-10 min-w-0 rounded-3xl card-glass p-4 sm:p-6 mb-6 lg:mb-0">
          <h2 className="sr-only">{todayFullDate}</h2>
          {/* Today's date as a postage stamp stuck over the card's top-right corner. */}
          <PostageStamp aria-hidden className="absolute -top-9 right-4 z-10 rotate-6" innerClassName="h-[62px] w-14">
            <span className="text-[11px] font-extrabold uppercase leading-none tracking-[0.08em] text-accent-primary">{todayMonthShort}</span>
            <span className="text-[22px] font-extrabold leading-none tabular-nums text-primary">{todayDayOfMonth}</span>
            <span className="text-[11px] font-semibold leading-none text-secondary">{todayWeekdayShort}</span>
          </PostageStamp>

          {/* Full Day Connected Timeline */}
          <ScheduleDayTimeline
            events={activeTodayEvents}
            items={todayItems}
            dateStr={todayStr}
            isToday={true}
            onEventClick={(event) => {
              // Exams have no attendance to track, so they have no Course Hub.
              if (isExamEvent(event)) return;
              courseTracker.openWith(event);
            }}
            onItemClick={handleScheduleItemClick}
            onToggleTaskCompleted={handleToggleScheduleTask}
            onResolveSyncConflict={resolveSyncConflict}
            syncConflictIds={syncConflictIds}
            needsAcknowledgmentIds={needsAcknowledgmentIds}
            itemConflictsById={conflictsByItemId}
            classConflictsByEventId={conflictsByEventId}
            intensiveCourses={intensiveCourseKeys}
            emptyPlaceholder={
              <div className="py-12 px-8 text-center space-y-3 card-glass bg-surface-secondary/80 border border-subtle rounded-3xl flex flex-col items-center justify-center shadow-md">
                <div className="w-12 h-12 rounded-2xl bg-tint-amber text-tint-amber flex items-center justify-center mx-auto text-xl shadow-xs">
                  ☕
                </div>
                <p className="text-base font-bold text-primary">No schedule items today</p>
              </div>
            }
          />
        </Reveal>

        {/* RIGHT COLUMN: TRANSIT */}
        <div className="space-y-6 mb-6 lg:mb-0">
          <Reveal index={4}>
            <DeferredTransitWidget favorites={favoriteTrackers} onOpenTracker={liveTracker.openWith} />
          </Reveal>

          {/* Commute — desktop slot under transit */}
          <Reveal index={5} className="min-w-0 hidden lg:block">
            <DashboardCommuteCard onOpenTrip={tripDetail.openWith} />
          </Reveal>
        </div>
      </div>

      {liveDialogLoaded && (
        <Suspense fallback={null}>
          <LivePositionDialog
            open={liveTracker.open}
            tracker={liveTracker.value}
            onClose={() => liveTracker.onOpenChange(false)}
            onExited={liveTracker.onExited}
          />
        </Suspense>
      )}

      {tripDetailLoaded && (
        <Suspense fallback={null}>
          <TripDetailDialog
            open={tripDetail.open}
            trip={tripDetail.value}
            onOpenChange={tripDetail.onOpenChange}
            onExited={tripDetail.onExited}
            onEdit={(trip) => {
              tripDetail.onOpenChange(false);
              setEditingTrip(trip);
            }}
          />
        </Suspense>
      )}
      {createTripLoaded && (
        <Suspense fallback={null}>
          <CreateTripDialog
            open={editingTrip != null}
            onOpenChange={(open) => {
              if (!open) setEditingTrip(undefined);
            }}
            editingTrip={editingTrip}
          />
        </Suspense>
      )}

      <ScheduleItemEditor
        key={editingScheduleItem?.id || 'dashboard-schedule-item'}
        item={editingScheduleItem || undefined}
        items={scheduleItems}
        events={events}
        open={showScheduleItemEditor}
        isSubmitting={scheduleItemsState.isSaving}
        onClose={() => { setShowScheduleItemEditor(false); setEditingScheduleItem(null); }}
        onSubmit={handleSaveScheduleItem}
        onDelete={async (id) => {
          await scheduleItemsState.deleteItem(id);
          toast.success('Schedule item deleted');
        }}
      />

      {courseTracker.value && (
        <Suspense fallback={null}>
          <CourseTrackerModal
            open={courseTracker.open}
            onOpenChange={courseTracker.onOpenChange}
            onExited={courseTracker.onExited}
            courseName={courseTracker.value.course_name}
            courseCode={courseTracker.value.course_code}
            semester={courseTracker.value.semester}
            instructor={courseTracker.value.instructor}
            location={courseTracker.value.location}
            color={courseTracker.value.color}
            dayOfWeek={courseTracker.value.day_of_week}
            startTime={courseTracker.value.start_time}
            endTime={courseTracker.value.end_time}
            eventType={courseTracker.value.event_type}
            scheduleEvents={events}
          />
        </Suspense>
      )}
    </>
  );
}
