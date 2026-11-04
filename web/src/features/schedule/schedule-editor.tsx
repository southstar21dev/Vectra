import { useId, useState, useMemo } from 'react';
import { RiLoader4Line, RiBookOpenLine } from '@remixicon/react';
import { generateEventColor } from '@/lib/utils';
import { formatHHMM } from '@/lib/date-utils';
import { useAppearance } from '@/hooks/use-appearance';
import type { TimeFormat } from '@/features/settings/appearance-prefs';
import type { ScheduleEvent, ScheduleFormData, ScheduleFrequency } from './use-schedule';
import { getEventFrequency } from './use-schedule';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Select } from '@/components/ui/select';
import { Dialog, DialogContent } from '@/components/ui/dialog';
import { SheetBar, SheetBody, SheetFooter, SheetSectionTitle, SHEET_GUTTER } from '@/features/trips/sheet-chrome';
import { DeleteRow, FormField, FormGroup, PillRadio, SHEET_INPUT_CLASS } from '@/components/ui/sheet-form';
import { cn } from '@/lib/utils';

interface ScheduleEditorProps {
  event?: ScheduleEvent;
  open: boolean;
  onClose: () => void;
  onSubmit: (data: ScheduleFormData) => Promise<void>;
  onDelete?: (id: string) => Promise<void>;
  onOpenCourseHub?: (event: ScheduleEvent) => void;
  isSubmitting?: boolean;
  availableSemesters?: string[];
}

// Monday-first, the way a Budapest timetable reads.
const DAY_OPTIONS = [
  { value: '1', label: 'Mon' },
  { value: '2', label: 'Tue' },
  { value: '3', label: 'Wed' },
  { value: '4', label: 'Thu' },
  { value: '5', label: 'Fri' },
  { value: '6', label: 'Sat' },
  { value: '0', label: 'Sun' },
];

const ATTENDANCE_OPTIONS = [
  { value: 'lecture' as const, label: 'Lecture' },
  { value: 'seminar' as const, label: 'Seminar' },
  { value: 'lab' as const, label: 'Lab' },
  { value: 'unknown' as const, label: 'Not sure' },
];

const ATTENDANCE_HINT: Record<'lecture' | 'seminar' | 'lab' | 'unknown', string> = {
  lecture: 'Not mandatory, so a clash with it is only noted.',
  seminar: 'Mandatory. A clash with it is flagged on the schedule.',
  lab: 'Mandatory. A clash with it is flagged on the schedule.',
  unknown: 'Treated as mandatory until you say otherwise.',
};

const FREQUENCY_OPTIONS = [
  { value: 'weekly' as const, label: 'Every week' },
  { value: 'even' as const, label: 'Even weeks' },
  { value: 'odd' as const, label: 'Odd weeks' },
];

const colorOptions = [
  '#3b82f6', '#a855f7', '#22c55e', '#ef4444', '#f59e0b',
  '#06b6d4', '#ec4899', '#14b8a6', '#eab308', '#8b5cf6',
];

const HOURS_12 = Array.from({ length: 12 }, (_, i) => i + 1);
const HOURS_24 = Array.from({ length: 24 }, (_, i) => i);
const MINUTES = Array.from({ length: 12 }, (_, i) => i * 5);

/* ── 24 h ⇄ 12 h helpers ──────────────────────────────────── */

function parse24hTo12h(time: string): { hour: number; minute: number; period: 'AM' | 'PM' } {
  const [h, m] = (time || '09:00').split(':').map(Number);
  const hourNum = isNaN(h) ? 9 : h;
  const period: 'AM' | 'PM' = hourNum >= 12 ? 'PM' : 'AM';
  let hour = hourNum % 12;
  if (hour === 0) hour = 12;
  // snap to nearest 5-min increment
  const minute = Math.round((m || 0) / 5) * 5 % 60;
  return { hour, minute, period };
}

function parse24h(time: string): { hour: number; minute: number } {
  const [h, m] = (time || '09:00').split(':').map(Number);
  const hour = isNaN(h) ? 9 : Math.min(23, Math.max(0, h));
  const minute = Math.round((m || 0) / 5) * 5 % 60;
  return { hour, minute };
}

function format12hTo24h(hour: number, minute: number, period: 'AM' | 'PM'): string {
  let h24 = hour % 12;
  if (period === 'PM') h24 += 12;
  return `${String(h24).padStart(2, '0')}:${String(minute).padStart(2, '0')}`;
}

/* ── Compact Time Picker ───────────────────────────────────── */

function TimePicker({ value, onChange, format }: { value: string; onChange: (v: string) => void; format?: TimeFormat }) {
  const { prefs } = useAppearance();
  const timeFormat = format || prefs.timeFormat;
  const parsed12h = useMemo(() => parse24hTo12h(value), [value]);
  const parsed24h = useMemo(() => parse24h(value), [value]);

  const update12h = (field: 'hour' | 'minute' | 'period', raw: string) => {
    let { hour, minute, period } = parsed12h;
    if (field === 'hour') hour = Number(raw);
    else if (field === 'minute') minute = Number(raw);
    else period = raw as 'AM' | 'PM';
    onChange(format12hTo24h(hour, minute, period));
  };

  const update24h = (field: 'hour' | 'minute', raw: string) => {
    let { hour, minute } = parsed24h;
    if (field === 'hour') hour = Number(raw);
    else minute = Number(raw);
    onChange(`${String(hour).padStart(2, '0')}:${String(minute).padStart(2, '0')}`);
  };

  const compactCls = 'pl-3 pr-7';

  if (timeFormat === '24h') {
    return (
      <div className="flex items-center gap-1">
        {/* 24-hour Hour */}
        <Select
          value={String(parsed24h.hour)}
          onChange={(e) => update24h('hour', e.target.value)}
          className={compactCls}
          containerClassName="flex-1 min-w-0"
        >
          {HOURS_24.map((h) => (
            <option key={h} value={h} className="bg-surface-tertiary">
              {String(h).padStart(2, '0')}
            </option>
          ))}
        </Select>

        <span className="text-muted font-mono text-xs font-bold select-none shrink-0">:</span>

        {/* Minute */}
        <Select
          value={String(parsed24h.minute)}
          onChange={(e) => update24h('minute', e.target.value)}
          className={compactCls}
          containerClassName="flex-1 min-w-0"
        >
          {MINUTES.map((m) => (
            <option key={m} value={m} className="bg-surface-tertiary">
              {String(m).padStart(2, '0')}
            </option>
          ))}
        </Select>
      </div>
    );
  }

  return (
    <div className="flex items-center gap-1">
      {/* 12-hour Hour */}
      <Select
        value={String(parsed12h.hour)}
        onChange={(e) => update12h('hour', e.target.value)}
        className={compactCls}
        containerClassName="flex-1 min-w-0"
      >
        {HOURS_12.map((h) => (
          <option key={h} value={h} className="bg-surface-tertiary">{h}</option>
        ))}
      </Select>

      <span className="text-muted font-mono text-xs font-bold select-none shrink-0">:</span>

      {/* Minute */}
      <Select
        value={String(parsed12h.minute)}
        onChange={(e) => update12h('minute', e.target.value)}
        className={compactCls}
        containerClassName="flex-1 min-w-0"
      >
        {MINUTES.map((m) => (
          <option key={m} value={m} className="bg-surface-tertiary">
            {String(m).padStart(2, '0')}
          </option>
        ))}
      </Select>

      {/* AM / PM */}
      <Select
        value={parsed12h.period}
        onChange={(e) => update12h('period', e.target.value)}
        className={`${compactCls} tracking-wide`}
        containerClassName="flex-1 min-w-0"
      >
        <option value="AM" className="bg-surface-tertiary">AM</option>
        <option value="PM" className="bg-surface-tertiary">PM</option>
      </Select>
    </div>
  );
}



/* ── Main Editor ───────────────────────────────────────────── */

export function ScheduleEditor({
  event, open, onClose, onSubmit, onDelete, isSubmitting, availableSemesters = [], onOpenCourseHub,
}: ScheduleEditorProps) {
  const [courseName, setCourseName] = useState(event?.course_name || '');
  const [courseCode, setCourseCode] = useState(event?.course_code || '');
  const [attendanceType, setAttendanceType] = useState<'lecture' | 'seminar' | 'lab' | 'unknown'>(event?.attendance_type || 'unknown');
  const [instructor, setInstructor] = useState(event?.instructor || '');
  const [location, setLocation] = useState(event?.location || '');
  const [dayOfWeek, setDayOfWeek] = useState(event?.day_of_week ?? 1);
  const [startTime, setStartTime] = useState(formatHHMM(event?.start_time) || '09:00');
  const [endTime, setEndTime] = useState(formatHHMM(event?.end_time) || '10:30');
  const [color, setColor] = useState(event?.color || generateEventColor());
  const [semester, setSemester] = useState(event?.semester || '');
  const [frequency, setFrequency] = useState<ScheduleFrequency>(
    getEventFrequency(event?.csv_metadata)
  );
  const [isDeleting, setIsDeleting] = useState(false);

  // Reset the form to the edited event (or a blank draft) whenever the editor
  // opens or retargets. Adjusted during render so the previous event's values
  // can never paint for a frame.
  const [prevSync, setPrevSync] = useState<{ open: boolean; event?: ScheduleEvent }>({ open, event });
  if (open !== prevSync.open || event !== prevSync.event) {
    setPrevSync({ open, event });
    if (open) {
      if (event) {
        setCourseName(event.course_name || '');
        setCourseCode(event.course_code || '');
        setAttendanceType(event.attendance_type || 'unknown');
        setInstructor(event.instructor || '');
        setLocation(event.location || '');
        setDayOfWeek(event.day_of_week ?? 1);
        setStartTime(formatHHMM(event.start_time) || '09:00');
        setEndTime(formatHHMM(event.end_time) || '10:30');
        setColor(event.color || generateEventColor());
        setSemester(event.semester || '');
        setFrequency(getEventFrequency(event.csv_metadata));
      } else {
        setCourseName('');
        setCourseCode('');
        setAttendanceType('unknown');
        setInstructor('');
        setLocation('');
        setDayOfWeek(1);
        setStartTime('09:00');
        setEndTime('10:30');
        setColor(generateEventColor());
        setSemester('');
        setFrequency('weekly');
      }
    }
  }

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    await onSubmit({
      course_name: courseName,
      attendance_type: attendanceType,
      course_code: courseCode,
      instructor,
      location,
      day_of_week: dayOfWeek,
      start_time: startTime,
      end_time: endTime,
      color,
      semester,
      semester_start: null,
      semester_end: null,
      frequency,
    });
    onClose();
  };

  const handleDelete = async () => {
    if (!event?.id || !onDelete) return;
    setIsDeleting(true);
    try {
      await onDelete(event.id);
      onClose();
    } catch (err) {
      console.error('Failed to delete event', err);
    } finally {
      setIsDeleting(false);
    }
  };

  const hasDelete = !!(event && event.id && onDelete);
  const titleId = useId();
  const ids = useId();
  const [scrolled, setScrolled] = useState(false);
  const canSave = !isSubmitting && !isDeleting && courseName.trim().length > 0;

  return (
    <Dialog open={open} onOpenChange={(val) => !val && onClose()}>
      <DialogContent
        edgeToEdge
        aria-labelledby={titleId}
        className="sm:max-w-lg max-h-[92vh] rounded-[28px] p-0 overflow-hidden flex flex-col gap-0"
      >
        <form id="schedule-form" onSubmit={handleSubmit} className="flex min-h-0 flex-1 flex-col">
          <SheetBar
            leading="close"
            onLeading={onClose}
            title={event ? 'Edit class' : 'Add class'}
            titleId={titleId}
            subtitle={event?.course_name || undefined}
            scrolled={scrolled}
            trailing={
              event && onOpenCourseHub ? (
                <button
                  type="button"
                  onClick={() => {
                    onClose();
                    onOpenCourseHub(event);
                  }}
                  className="inline-flex h-10 items-center gap-1.5 rounded-full bg-surface-overlay px-3.5 text-sm font-bold text-primary transition-colors hover:bg-surface-tertiary cursor-pointer active:scale-95 focus-visible:outline focus-visible:outline-2 focus-visible:outline-accent-primary"
                >
                  <RiBookOpenLine className="h-3.5 w-3.5" aria-hidden /> Hub
                </button>
              ) : undefined
            }
          />

          <SheetBody onScrolledChange={setScrolled} fadeBottom={false} className={cn('space-y-6 pb-6 pt-3', SHEET_GUTTER)}>
            <section aria-labelledby={`${ids}-class`} className="space-y-3">
              <SheetSectionTitle id={`${ids}-class`}>Class</SheetSectionTitle>
              <FormGroup>
                <FormField label="Course name" htmlFor="class-name">
                  <Input
                    id="class-name"
                    type="text"
                    value={courseName}
                    onChange={(e) => setCourseName(e.target.value)}
                    required
                    placeholder="Analysis II"
                    className={SHEET_INPUT_CLASS}
                  />
                </FormField>
                <FormField label="Attendance" labelId="class-attendance-label" hint={ATTENDANCE_HINT[attendanceType]}>
                  <PillRadio
                    value={attendanceType}
                    onChange={setAttendanceType}
                    options={ATTENDANCE_OPTIONS}
                    labelledBy="class-attendance-label"
                    columns={4}
                  />
                </FormField>
                <div className="grid grid-cols-2 divide-x divide-subtle">
                  <FormField label="Course code" htmlFor="class-code">
                    <Input
                      id="class-code"
                      type="text"
                      value={courseCode}
                      onChange={(e) => setCourseCode(e.target.value)}
                      placeholder="BMETE90AX22"
                      className={SHEET_INPUT_CLASS}
                    />
                  </FormField>
                  <FormField label="Instructor" htmlFor="class-instructor">
                    <Input
                      id="class-instructor"
                      type="text"
                      value={instructor}
                      onChange={(e) => setInstructor(e.target.value)}
                      placeholder="Dr. Kovács"
                      className={SHEET_INPUT_CLASS}
                    />
                  </FormField>
                </div>
                <FormField label="Room" htmlFor="class-location">
                  <Input
                    id="class-location"
                    type="text"
                    value={location}
                    onChange={(e) => setLocation(e.target.value)}
                    placeholder="K.F.81"
                    className={SHEET_INPUT_CLASS}
                  />
                </FormField>
              </FormGroup>
            </section>

            <section aria-labelledby={`${ids}-when`} className="space-y-3">
              <SheetSectionTitle id={`${ids}-when`}>When</SheetSectionTitle>
              <FormGroup>
                <FormField label="Day" labelId="class-day-label">
                  <PillRadio
                    value={String(dayOfWeek)}
                    onChange={(next) => setDayOfWeek(Number(next))}
                    options={DAY_OPTIONS}
                    labelledBy="class-day-label"
                    columns={7}
                  />
                </FormField>
                <div className="grid grid-cols-2 divide-x divide-subtle">
                  <FormField label="Starts" labelId="class-start-label">
                    <div role="group" aria-labelledby="class-start-label">
                      <TimePicker value={startTime} onChange={setStartTime} />
                    </div>
                  </FormField>
                  <FormField label="Ends" labelId="class-end-label">
                    <div role="group" aria-labelledby="class-end-label">
                      <TimePicker value={endTime} onChange={setEndTime} />
                    </div>
                  </FormField>
                </div>
                <FormField label="Repeats" labelId="class-frequency-label">
                  <PillRadio
                    value={frequency}
                    onChange={setFrequency}
                    options={FREQUENCY_OPTIONS}
                    labelledBy="class-frequency-label"
                    columns={3}
                  />
                </FormField>
                {availableSemesters.length > 0 && (
                  <FormField label="Semester" htmlFor="class-semester">
                    <Select
                      id="class-semester"
                      value={semester}
                      onChange={(e) => setSemester(e.target.value)}
                      containerClassName="w-full sm:w-full"
                      className="h-11 text-sm"
                    >
                      <option value="" className="bg-surface-tertiary">All semesters</option>
                      {availableSemesters.map((s) => (
                        <option key={s} value={s} className="bg-surface-tertiary">{s}</option>
                      ))}
                    </Select>
                  </FormField>
                )}
              </FormGroup>
            </section>

            <section aria-labelledby={`${ids}-colour`} className="space-y-3">
              <SheetSectionTitle id={`${ids}-colour`}>Colour</SheetSectionTitle>
              <FormGroup>
                <div className="px-4 py-4" role="radiogroup" aria-label="Class colour">
                  <div className="flex items-center justify-between">
                    {colorOptions.map((c) => {
                      const active = color === c;
                      return (
                        <button
                          key={c}
                          type="button"
                          role="radio"
                          aria-checked={active}
                          aria-label={`Colour ${c}`}
                          onClick={() => setColor(c)}
                          className={cn(
                            'flex h-7 w-7 shrink-0 items-center justify-center rounded-full transition-[transform,box-shadow] cursor-pointer touch-manipulation focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent-primary',
                            active ? 'scale-110 ring-2 ring-primary ring-offset-2 ring-offset-surface-secondary' : 'opacity-80 hover:scale-105 hover:opacity-100'
                          )}
                          style={{ backgroundColor: c }}
                        />
                      );
                    })}
                  </div>
                </div>
              </FormGroup>
            </section>

            {hasDelete && (
              <DeleteRow
                label="Delete class"
                onConfirm={handleDelete}
                disabled={isSubmitting}
                busy={isDeleting}
              />
            )}
          </SheetBody>

          <SheetFooter>
            <Button type="submit" size="lg" className="h-12 w-full rounded-2xl" disabled={!canSave}>
              {isSubmitting && <RiLoader4Line className="h-4 w-4 animate-spin" aria-hidden="true" />}
              {event ? 'Save changes' : 'Add class'}
            </Button>
          </SheetFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
