import { useMemo, useEffect } from 'react';
import { queryOptions, useMutation, useQueryClient, useQuery } from '@tanstack/react-query';
import { queryKeys } from '@/lib/query-keys';
import { pb } from '@/lib/pocketbase';
import { requireSession, getSessionUserId } from '@/lib/auth-helpers';
import { nowInBudapest } from '@/lib/date-utils';
import { isOnline } from '@/lib/online';
import { useOnlineStatus } from '@/hooks/use-online-status';
import { useNow } from '@/hooks/use-now';
import { createOfflineCache } from '@/lib/offline-cache';
import { encryptField, decryptField, decryptRow, isCiphertext } from '@/lib/encryption';
import type { Json } from '@/lib/pocketbase-mappers';
import type { NeptunScheduleEventsRecord, ScheduleEventsRecord } from '@/types/pocketbase-types';
import { classOccursOnDate } from './schedule-conflicts';
import { getCurrentSemester } from './ics-parser';

export type AttendanceType = 'lecture' | 'seminar' | 'lab' | 'unknown';

/**
 * Schedule event as the UI sees it. PocketBase rows (`user` relation, optional
 * text fields) are adapted to this stable domain shape at the data-layer
 * boundary. Neptun-synced rows (from neptun_schedule_events) are tagged
 * `'neptun'` so the UI can keep them read-only, mirroring the old id-less
 * localStorage events.
 */
export interface ScheduleEvent {
  id: string;
  user_id: string;
  course_name: string;
  course_code: string;
  instructor: string;
  location: string;
  day_of_week: number;
  start_time: string;
  end_time: string;
  color: string;
  semester: string;
  semester_start: string | null;
  semester_end: string | null;
  event_type: string;
  attendance_type: AttendanceType | null;
  csv_metadata: Json;
  created_at: string;
  updated_at: string;
  source?: 'manual' | 'neptun';
}

/** Raw PocketBase row shapes (fields are optional in the generated types). */
type ScheduleEventRecord = ScheduleEventsRecord<Json>;
type NeptunScheduleEventRecord = NeptunScheduleEventsRecord;

export interface ScheduleFormData {
  course_name: string;
  attendance_type?: 'lecture' | 'seminar' | 'lab' | 'unknown' | null;
  course_code: string;
  instructor: string;
  location: string;
  day_of_week: number;
  start_time: string;
  end_time: string;
  color: string;
  semester: string;
  semester_start: string | null;
  semester_end: string | null;
  frequency?: 'weekly' | 'even' | 'odd';
}

export type ScheduleFrequency = 'weekly' | 'even' | 'odd';

/** Read the odd/even-week frequency filter stored in csv_metadata. */
export function getEventFrequency(meta: Json | null | undefined): ScheduleFrequency {
  const f = (meta as { frequency?: string } | null | undefined)?.frequency;
  return f === 'even' || f === 'odd' ? f : 'weekly';
}

/** Merge a frequency into csv_metadata (weekly/undefined removes the key). */
function setFrequencyInMetadata(meta: Json | null | undefined, frequency?: ScheduleFrequency): Json {
  const next: Record<string, Json | undefined> = { ...(meta as Record<string, Json | undefined>) };
  if (frequency === 'even' || frequency === 'odd') {
    next.frequency = frequency;
  } else {
    delete next.frequency;
  }
  return next;
}

/** Create payload; the owner relation + timestamps are assigned by the backend. */
type ScheduleEventInsert = {
  course_name: string;
  day_of_week: number;
  start_time: string;
  end_time: string;
  course_code?: string;
  instructor?: string;
  location?: string;
  color?: string;
  semester?: string;
  semester_start?: string | null;
  semester_end?: string | null;
  event_type?: string;
  attendance_type?: AttendanceType | null;
  csv_metadata?: Json;
};
type ScheduleEventUpdate = Partial<ScheduleEventInsert>;

/** Adapt a PocketBase schedule_events row to the domain `ScheduleEvent`. */
function adaptScheduleEvent(row: ScheduleEventRecord, source: 'manual' | 'neptun'): ScheduleEvent {
  return {
    id: row.id,
    user_id: row.user ?? '',
    course_name: row.course_name ?? '',
    course_code: row.course_code ?? '',
    instructor: row.instructor ?? '',
    location: row.location ?? '',
    day_of_week: Number(row.day_of_week ?? 0),
    start_time: row.start_time ?? '',
    end_time: row.end_time ?? '',
    color: row.color ?? '',
    semester: row.semester ?? '',
    semester_start: row.semester_start ?? null,
    semester_end: row.semester_end ?? null,
    event_type: row.event_type ?? '',
    attendance_type: row.attendance_type ?? null,
    csv_metadata: row.csv_metadata ?? null,
    created_at: row.created_at ?? '',
    updated_at: row.updated_at ?? '',
    source,
  };
}

const scheduleCache = createOfflineCache<ScheduleEvent[]>('vectra_schedule_cache_events');

export const saveScheduleToOfflineCache = (events: ScheduleEvent[], userId?: string | null) => scheduleCache.write(events, userId);
export const getScheduleFromOfflineCache = (userId?: string | null): ScheduleEvent[] | undefined => scheduleCache.read(userId) ?? undefined;

export async function fetchScheduleEventsFromCloud(userId?: string | null): Promise<ScheduleEvent[]> {
  const activeUserId = userId === undefined ? getSessionUserId() : userId;
  const ownerFilter = activeUserId ? pb.filter('user = {:u}', { u: activeUserId }) : '';

  const manualRows = await pb.collection<ScheduleEventRecord>('schedule_events').getFullList({
    filter: ownerFilter,
    sort: 'day_of_week,start_time',
  });

  const SCHEDULE_ENCRYPTED_FIELDS = ['course_name', 'instructor', 'location'] as const;
  const dbEvents = await Promise.all(manualRows.map(async (row): Promise<ScheduleEvent> => {
    const decrypted = await decryptRow(row, SCHEDULE_ENCRYPTED_FIELDS);
    if ([decrypted.course_name, decrypted.instructor, decrypted.location].some(isCiphertext)) {
      throw new Error('Could not decrypt the latest class schedule.');
    }
    return adaptScheduleEvent(decrypted, 'manual');
  }));

  const neptunRows = await pb.collection<NeptunScheduleEventRecord>('neptun_schedule_events').getFullList({
    filter: ownerFilter,
  });

  const neptunEvents = await Promise.all(neptunRows.map(async (row): Promise<ScheduleEvent> => {
    const [course_name, course_code, instructor, location, dayOfWeek, start_time, end_time, semester, semester_start, semester_end, event_type] = await Promise.all([
      decryptField(row.course_name),
      decryptField(row.course_code),
      decryptField(row.instructor),
      decryptField(row.location),
      decryptField(row.day_of_week),
      decryptField(row.start_time),
      decryptField(row.end_time),
      decryptField(row.semester),
      decryptField(row.semester_start),
      decryptField(row.semester_end),
      decryptField(row.event_type),
    ]);
    const decryptedValues = [course_name, course_code, instructor, location, dayOfWeek, start_time, end_time, semester, semester_start, semester_end, event_type];
    if (decryptedValues.some(isCiphertext)) {
      throw new Error('Could not decrypt the latest Neptun schedule.');
    }
    return {
      id: row.id,
      user_id: row.user ?? '',
      course_name: String(course_name || ''),
      course_code: String(course_code || ''),
      instructor: String(instructor || ''),
      location: String(location || ''),
      // A missing weekday must not become 0 (Sunday); -1 lists it on no day.
      day_of_week: dayOfWeek == null || dayOfWeek === '' || !Number.isInteger(Number(dayOfWeek)) ? -1 : Number(dayOfWeek),
      start_time: String(start_time || ''),
      end_time: String(end_time || ''),
      color: row.color ?? '',
      semester: String(semester || ''),
      semester_start: (semester_start as string | null) ?? null,
      semester_end: (semester_end as string | null) ?? null,
      event_type: String(event_type || 'class'),
      attendance_type: null,
      csv_metadata: null,
      created_at: row.created_at ?? '',
      updated_at: row.updated_at ?? '',
      source: 'neptun',
    };
  }));

  const allEvents = [...dbEvents, ...neptunEvents].sort((a, b) =>
    a.day_of_week !== b.day_of_week ? a.day_of_week - b.day_of_week : a.start_time.localeCompare(b.start_time)
  );
  saveScheduleToOfflineCache(allEvents, activeUserId);
  return allEvents;
}

export const scheduleQueryOptions = queryOptions({
  queryKey: queryKeys.schedule,
  queryFn: async (): Promise<ScheduleEvent[]> => {
    const userId = getSessionUserId();

    if (!isOnline()) return getScheduleFromOfflineCache(userId) ?? [];

    try {
      return await fetchScheduleEventsFromCloud(userId);
    } catch (err) {
      console.warn('Network unavailable, falling back to cached offline schedule:', err);
      const cachedEvents = getScheduleFromOfflineCache(userId);
      if (cachedEvents && cachedEvents.length > 0) return cachedEvents;
      throw err;
    }
  },
  placeholderData: () => getScheduleFromOfflineCache(),
  staleTime: 1000 * 60 * 15, // 15 minutes
  gcTime: 1000 * 60 * 60 * 24 * 30, // 30 days retention
});

export function useCreateScheduleEvent() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (
      event: ScheduleEventInsert & { frequency?: ScheduleFrequency }
    ): Promise<ScheduleEvent> => {
      const session = requireSession();

      const { frequency, ...eventRest } = event;
      const encryptedEvent: ScheduleEventInsert = {
        ...eventRest,
        course_name: (await encryptField(event.course_name)) as string,
        instructor: (await encryptField(event.instructor)) as string,
        location: (await encryptField(event.location)) as string,
        attendance_type: event.attendance_type ?? 'unknown',
      };

      // Frequency (odd/even weeks) rides along in csv_metadata — no DB column for it.
      encryptedEvent.csv_metadata = setFrequencyInMetadata(encryptedEvent.csv_metadata, frequency);

      const record = await pb.collection<ScheduleEventRecord>('schedule_events').create({
        ...encryptedEvent,
        user: session.record!.id,
      });
      return adaptScheduleEvent(record, 'manual');
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.schedule });
    },
  });
}

export function useClearAllScheduleEvents() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async () => {
      const session = requireSession();
      const ownerFilter = pb.filter('user = {:u}', { u: session.record!.id });

      // PocketBase has no bulk delete via the SDK: fetch the user's rows, then
      // delete each one. Do the same for the encrypted Neptun calendar events.
      const manualRows = await pb.collection<ScheduleEventRecord>('schedule_events').getFullList({ filter: ownerFilter });
      await Promise.all(manualRows.map((row) => pb.collection('schedule_events').delete(row.id)));

      const neptunRows = await pb.collection<NeptunScheduleEventRecord>('neptun_schedule_events').getFullList({ filter: ownerFilter });
      await Promise.all(neptunRows.map((row) => pb.collection('neptun_schedule_events').delete(row.id)));

      // Notify listeners that the calendar changed.
      if (typeof window !== 'undefined') {
        window.dispatchEvent(new Event('vectra_neptun_sync_completed'));
      }
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.schedule });
    },
  });
}

export function useUpdateScheduleEvent() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({
      id,
      ...updates
    }: ScheduleEventUpdate & { id: string } & { frequency?: ScheduleFrequency }): Promise<ScheduleEvent> => {
      // Owner scoping is enforced server-side by the collection's owner rules.
      requireSession();

      const { frequency, ...cleanUpdates } = updates;
      const encryptedUpdates: ScheduleEventUpdate = { ...cleanUpdates };
      if (encryptedUpdates.course_name !== undefined) encryptedUpdates.course_name = (await encryptField(encryptedUpdates.course_name)) as string;
      if (encryptedUpdates.instructor !== undefined) encryptedUpdates.instructor = (await encryptField(encryptedUpdates.instructor)) as string;
      if (encryptedUpdates.location !== undefined) encryptedUpdates.location = (await encryptField(encryptedUpdates.location)) as string;

      // Frequency (odd/even weeks) rides along in csv_metadata — no DB column for it.
      encryptedUpdates.csv_metadata = setFrequencyInMetadata(encryptedUpdates.csv_metadata, frequency);

      const record = await pb.collection<ScheduleEventRecord>('schedule_events').update(id, encryptedUpdates);
      return adaptScheduleEvent(record, 'manual');
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.schedule });
    },
  });
}

export function useDeleteScheduleEvent() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (eventId: string) => {
      // Owner scoping is enforced server-side by the collection's owner rules.
      requireSession();

      await pb.collection('schedule_events').delete(eventId);
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.schedule });
    },
  });
}

export function useSchedule() {
  const queryClient = useQueryClient();
  const isOnlineState = useOnlineStatus();
  const isOffline = !isOnlineState;

  // Listen to window event for sync completions to invalidate cache without useQueryClient inside non-hooks
  useEffect(() => {
    const handleSyncEvent = () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.schedule });
    };
    window.addEventListener('vectra_neptun_sync_completed', handleSyncEvent);
    return () => window.removeEventListener('vectra_neptun_sync_completed', handleSyncEvent);
  }, [queryClient]);

  const { data: events = [], isLoading, error } = useQuery(scheduleQueryOptions);

  const createEvent = useCreateScheduleEvent();
  const updateEvent = useUpdateScheduleEvent();
  const deleteEvent = useDeleteScheduleEvent();

  // Group events by day
  const eventsByDay = useMemo(() => {
    const grouped: Record<number, ScheduleEvent[]> = {};
    for (let i = 0; i < 7; i++) grouped[i] = [];
    events.forEach((event) => {
      if (grouped[event.day_of_week]) {
        grouped[event.day_of_week].push(event);
      }
    });
    return grouped;
  }, [events]);

  // Minute clock: "today" must move on while the app stays open (midnight
  // rolls the day over).
  const clock = useNow(60_000);

  // Today's events (filtered by active semester date range)
  const todayEvents = useMemo(() => {
    const now = nowInBudapest(new Date(clock));
    const todayNum = now.dayOfWeek;
    const todayStr = now.ymd;

    return (eventsByDay[todayNum] || []).filter((event) =>
      classOccursOnDate(event, todayStr, event.semester_start || todayStr, events)
    );
  }, [eventsByDay, events, clock]);

  // Unique semesters sorted chronologically
  const semesters = useMemo(() => {
    const semSet = Array.from(new Set(events.map((e) => e.semester).filter(Boolean)));
    return semSet.sort((a, b) => a.localeCompare(b));
  }, [events]);

  // Current active semester based on today's Budapest date
  const todayYmd = nowInBudapest(new Date(clock)).ymd;
  // eslint-disable-next-line react-hooks/exhaustive-deps
  const currentSemester = useMemo(() => getCurrentSemester(), [todayYmd]);

  return {
    events,
    eventsByDay,
    todayEvents,
    semesters,
    currentSemester,
    isLoading: isOffline ? false : isLoading,
    isOffline,
    error,
    createEvent: createEvent.mutateAsync,
    updateEvent: updateEvent.mutateAsync,
    deleteEvent: deleteEvent.mutateAsync,
    isCreating: createEvent.isPending,
  };
}

