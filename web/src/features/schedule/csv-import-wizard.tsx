import { useState, useCallback, useId, type ReactNode } from 'react';
import { RiCheckLine, RiErrorWarningLine, RiLinkM, RiLoader4Line, RiLockLine } from '@remixicon/react';
import { fetchNeptunICS, parseICSContentAsync } from './ics-parser';
import { persistNeptunEvents, pickNeptunSyncSemester } from './use-neptun-sync';
import { syncCourseTrackersFromScheduleEvents } from '@/features/course-tracker/schedule-reconciler';
import { useAuth } from '@/features/auth/use-auth';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Dialog, DialogContent } from '@/components/ui/dialog';
import { FormGroup, SHEET_INPUT_CLASS } from '@/components/ui/sheet-form';
import { SheetBar, SheetBody, SheetFooter, SheetSectionTitle, SHEET_GUTTER } from '@/features/trips/sheet-chrome';
import { getErrorMessage } from '@/lib/error-utils';
import { cn } from '@/lib/utils';

/** Where the subscription link lives in Neptun, with a crop of each screen. */
const NEPTUN_STEPS: Array<{ title: string; body: ReactNode; image: string; alt: string }> = [
  {
    title: 'Open Calendar',
    body: <>In Neptun, open <strong>Menu</strong> at the top left and choose <strong>Calendar</strong>.</>,
    image: '/images/neptun-tutorial/step1.png',
    alt: 'Neptun menu with Calendar highlighted',
  },
  {
    title: 'Calendar management',
    body: <>On the calendar page, tap <strong>Calendar management</strong>.</>,
    image: '/images/neptun-tutorial/step2.png',
    alt: 'The Calendar management button',
  },
  {
    title: 'Copy the link',
    body: <>Choose <strong>Copy subscription link</strong>, then paste it above.</>,
    image: '/images/neptun-tutorial/step3.png',
    alt: 'Copy subscription link in the Calendar management menu',
  },
];

const plural = (n: number, word: string, many = `${word}s`) => `${n} ${n === 1 ? word : many}`;

const TEXT_BUTTON_CLASS =
  'shrink-0 rounded-full px-2.5 py-1.5 text-xs font-semibold text-accent-primary transition-colors hover:bg-accent-primary/10 cursor-pointer disabled:cursor-not-allowed disabled:opacity-40 focus-visible:outline focus-visible:outline-2 focus-visible:outline-accent-primary';

interface CSVImportWizardProps {
  open: boolean;
  onClose: () => void;
  initialUrl?: string;
  onSaveUrl?: (url: string) => void;
}

export function CSVImportWizard({ open, onClose, initialUrl, onSaveUrl }: CSVImportWizardProps) {
  const { updateProfile } = useAuth();
  const [subscriptionUrl, setSubscriptionUrl] = useState('');
  const [isSecureConnected, setIsSecureConnected] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [parsedEvents, setParsedEvents] = useState<Record<string, any>[]>([]);
  const [parseErrors, setParseErrors] = useState<Array<{ row: number; message: string }>>([]);
  const [isParsing, setIsParsing] = useState(false);
  const [selectedSemester, setSelectedSemester] = useState<string | null>(null);
  const [isSaving, setIsSaving] = useState(false);
  const [scrolled, setScrolled] = useState(false);
  const titleId = useId();

  // Sync the subscription URL and connection flag when the wizard opens (or the
  // initial URL changes) — adjusted during render so the previous session's URL
  // never paints for a frame.
  const [prevInitialUrl, setPrevInitialUrl] = useState<string | undefined>(undefined);
  if (initialUrl && initialUrl !== prevInitialUrl) {
    setPrevInitialUrl(initialUrl);
    setSubscriptionUrl(initialUrl);
  }
  const [prevOpen, setPrevOpen] = useState<boolean | undefined>(undefined);
  if (open !== prevOpen) {
    setPrevOpen(open);
    if (open) {
      if (initialUrl) {
        setSubscriptionUrl(initialUrl);
        setIsSecureConnected(true);
      } else {
        setSubscriptionUrl('');
        setIsSecureConnected(false);
      }
    }
  }

  // The feed carries every semester, but the wizard shows and imports exactly
  // one — the same pick the background sync makes (no semester picking).
  const latestSemester = selectedSemester;
  const latestSemesterEvents = latestSemester
    ? parsedEvents.filter((e) => e.semester === latestSemester)
    : [];

  const resetState = useCallback(() => {
    setError(null);
    setParsedEvents([]);
    setParseErrors([]);
    setSelectedSemester(null);
  }, []);

  const handleClose = useCallback(() => {
    resetState();
    if (!initialUrl) setSubscriptionUrl('');
    onClose();
  }, [onClose, initialUrl, resetState]);

  // Process Live Neptun URL
  const processUrl = async () => {
    if (!subscriptionUrl.trim()) {
      setError('Please enter a valid Neptun calendar subscription URL.');
      return;
    }

    setError(null);
    setIsParsing(true);

    try {
      const icsText = await fetchNeptunICS(subscriptionUrl.trim());
      const result = await parseICSContentAsync(icsText);

      if (result.valid.length === 0) {
        throw new Error('No valid class events found in the Neptun calendar feed.');
      }

      setParsedEvents(result.valid);
      setParseErrors(result.errors);
      const semester = pickNeptunSyncSemester(result.valid);
      if (!semester) {
        throw new Error('No class events found for the current or upcoming semester.');
      }
      setSelectedSemester(semester);

      if (onSaveUrl && isSecureConnected === false) {
        onSaveUrl(subscriptionUrl.trim());
      }
      if (!isSecureConnected) {
        setSubscriptionUrl(''); // Clear the unencrypted URL from the input only if not re-syncing
      }
    } catch (err) {
      setError(getErrorMessage(err, 'Failed to fetch or parse Neptun calendar from URL.'));
      setParsedEvents([]);
    } finally {
      setIsParsing(false);
    }
  };

  const handleConfirmImport = async () => {
    if (latestSemesterEvents.length === 0) return;

    setIsSaving(true);
    try {
      const cleanEvents = latestSemesterEvents.map((evt) => ({
        course_name: String(evt.course_name || ''),
        course_code: String(evt.course_code || ''),
        instructor: String(evt.instructor || ''),
        location: String(evt.location || ''),
        day_of_week: Number(evt.day_of_week),
        start_time: String(evt.start_time),
        end_time: String(evt.end_time),
        color: String(evt.color || '#3B82F6'),
        semester: String(evt.semester || ''),
        semester_start: evt.semester_start ? String(evt.semester_start) : null,
        semester_end: evt.semester_end ? String(evt.semester_end) : null,
        event_type: String(evt.event_type || 'class'),
      }));

      // Upload the synced calendar, encrypted at rest, so it syncs across devices.
      await persistNeptunEvents(cleanEvents);

      // Automatically autofill course tracker sessions for classes only; exams
      // stay on the schedule but never generate attendance sessions.
      try {
        await syncCourseTrackersFromScheduleEvents(cleanEvents);
      } catch (trackerErr) {
        console.warn('Auto-populating course trackers during import failed:', trackerErr);
      }

      const nowMs = Date.now();

      try {
        await updateProfile({ neptun_last_sync: new Date(nowMs).toISOString() });
      } catch (err) {
        console.warn('Failed to update profile sync timestamp', err);
      }

      window.dispatchEvent(new Event('vectra_neptun_sync_completed'));

      handleClose();
    } catch (err) {
      setError(getErrorMessage(err, 'Failed to save imported events.'));
    } finally {
      setIsSaving(false);
    }
  };

  const examCount = (events: Array<Record<string, any>>) => events.filter((event) => event.event_type === 'exam').length;
  const classCount = (events: Array<Record<string, any>>) => events.length - examCount(events);

  const hasPreview = parsedEvents.length > 0;
  // The preview names each course once; its sessions and exams show only as counts.
  const previewCourses = new Map<string, Record<string, any>>();
  for (const event of latestSemesterEvents) {
    const key = String(event.course_name || '').toLowerCase().trim();
    if (event.event_type !== 'exam' && !previewCourses.has(key)) previewCourses.set(key, event);
  }
  const courseCount = previewCourses.size;
  const previewClasses = classCount(latestSemesterEvents);
  const previewExams = examCount(latestSemesterEvents);

  return (
    <Dialog open={open} onOpenChange={(val) => !val && handleClose()}>
      <DialogContent
        edgeToEdge
        aria-labelledby={titleId}
        className="sm:max-w-lg max-h-[92vh] rounded-[28px] p-0 overflow-hidden flex flex-col gap-0"
      >
        <SheetBar
          leading="close"
          onLeading={handleClose}
          titleId={titleId}
          title="Sync Neptun"
          subtitle={hasPreview ? 'Check the preview before syncing' : 'Keep your timetable up to date'}
          scrolled={scrolled}
        />

        <SheetBody onScrolledChange={setScrolled} className="pb-6">
          <div className={cn('space-y-6 pt-2', SHEET_GUTTER)}>
            {error && (
              <div role="alert" className="flex items-start gap-3 rounded-2xl border border-rose-500/30 bg-rose-500/10 px-4 py-3">
                <RiErrorWarningLine className="mt-0.5 h-5 w-5 shrink-0 text-tint-red" aria-hidden="true" />
                <div className="min-w-0">
                  <p className="text-sm font-semibold text-primary">Couldn’t sync</p>
                  <p className="mt-0.5 text-xs leading-relaxed text-secondary">{error}</p>
                </div>
              </div>
            )}

            {hasPreview ? (
              <section className="space-y-3">
                <SheetSectionTitle
                  action={
                    <button type="button" onClick={resetState} disabled={isSaving} className={TEXT_BUTTON_CLASS}>
                      Change link
                    </button>
                  }
                >
                  {latestSemester ?? 'This semester'}
                </SheetSectionTitle>

                <dl className="grid grid-cols-3 gap-2">
                  {[
                    { label: courseCount === 1 ? 'Course' : 'Courses', value: courseCount },
                    { label: previewClasses === 1 ? 'Class' : 'Classes', value: previewClasses },
                    { label: previewExams === 1 ? 'Exam' : 'Exams', value: previewExams },
                  ].map((stat) => (
                    <div key={stat.label} className="rounded-2xl card-glass px-3.5 py-3">
                      <dd className="text-xl font-extrabold tabular-nums text-primary">{stat.value}</dd>
                      <dt className="text-xs text-muted">{stat.label}</dt>
                    </div>
                  ))}
                </dl>

                {parseErrors.length > 0 && (
                  <p className="flex items-start gap-2 rounded-2xl border border-amber-500/30 bg-amber-500/10 px-4 py-3 text-xs text-secondary">
                    <RiErrorWarningLine className="h-4 w-4 shrink-0 text-tint-amber" aria-hidden="true" />
                    {plural(parseErrors.length, 'item')} couldn’t be read and will be skipped.
                  </p>
                )}

                <FormGroup className="overflow-hidden">
                  {[...previewCourses].map(([key, evt]) => (
                    <div key={key}className="flex items-center gap-3 px-4 py-3">
                      <span
                        className="h-2.5 w-2.5 shrink-0 rounded-full"
                        style={{ backgroundColor: String(evt.color || '#3B82F6') }}
                        aria-hidden="true"
                      />
                      <p className="min-w-0 truncate text-sm font-semibold text-primary">{String(evt.course_name)}</p>
                    </div>
                  ))}
                </FormGroup>
              </section>
            ) : isSecureConnected ? (
              <section className="space-y-3">
                <FormGroup>
                  <div className="flex items-center gap-3 px-4 py-3.5">
                    <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-emerald-500/15 text-tint-emerald">
                      <RiLinkM className="h-5 w-5" aria-hidden="true" />
                    </span>
                    <div className="min-w-0 flex-1">
                      <p className="text-sm font-semibold text-primary">Neptun link saved</p>
                      <p className="mt-0.5 flex items-center gap-1 text-xs text-muted">
                        <RiLockLine className="h-3 w-3 shrink-0" aria-hidden="true" />
                        Encrypted · syncs across your devices
                      </p>
                    </div>
                    <button
                      type="button"
                      onClick={() => {
                        setIsSecureConnected(false);
                        setSubscriptionUrl('');
                      }}
                      disabled={isParsing}
                      className={TEXT_BUTTON_CLASS}
                    >
                      Change
                    </button>
                  </div>
                </FormGroup>
                <p className="px-1 text-xs text-muted">
                  Fetch to preview this semester’s classes and exams. Nothing changes until you confirm.
                </p>
              </section>
            ) : (
              <>
                <section className="space-y-3">
                  <SheetSectionTitle>Subscription link</SheetSectionTitle>
                  <Input
                    type="url"
                    inputMode="url"
                    autoComplete="off"
                    spellCheck={false}
                    aria-label="Neptun subscription link"
                    placeholder="Paste the link from Neptun"
                    value={subscriptionUrl}
                    onChange={(e) => setSubscriptionUrl(e.target.value)}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter' && subscriptionUrl.trim() && !isParsing) void processUrl();
                    }}
                    className={SHEET_INPUT_CLASS}
                    disabled={isParsing}
                  />
                  <p className="flex items-start gap-1.5 px-1 text-xs text-muted">
                    <RiLockLine className="mt-0.5 h-3 w-3 shrink-0" aria-hidden="true" />
                    The link holds a private token, so it and your classes are encrypted (AES-256-GCM) before they’re stored.
                  </p>
                </section>

                <section className="space-y-3">
                  <SheetSectionTitle>Where to find it</SheetSectionTitle>
                  {/* Bleeds to the sheet edge so the next card peeks in as a scroll hint. */}
                  <ol className={cn('-mx-5 flex snap-x snap-mandatory gap-3 overflow-x-auto no-scrollbar scroll-px-5 sm:-mx-6 sm:scroll-px-6', SHEET_GUTTER)}>
                    {NEPTUN_STEPS.map((step, i) => (
                      <li key={step.title} className="flex w-[72%] max-w-[240px] shrink-0 snap-start flex-col overflow-hidden rounded-3xl card-glass">
                        <div className="flex h-32 items-center justify-center bg-white p-3">
                          <img src={step.image} alt={step.alt} className="max-h-full object-contain" />
                        </div>
                        <div className="space-y-1 px-4 pb-4 pt-3">
                          <p className="flex items-center gap-2 text-sm font-bold text-primary">
                            <span className="flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-accent-primary/15 text-[11px] font-bold tabular-nums text-accent-primary">
                              {i + 1}
                            </span>
                            {step.title}
                          </p>
                          <p className="text-xs leading-relaxed text-secondary">{step.body}</p>
                        </div>
                      </li>
                    ))}
                  </ol>
                </section>
              </>
            )}
          </div>
        </SheetBody>

        <SheetFooter>
          {hasPreview ? (
            <Button
              type="button"
              size="lg"
              className="h-12 w-full rounded-2xl"
              onClick={handleConfirmImport}
              disabled={isSaving || latestSemesterEvents.length === 0}
            >
              {isSaving ? (
                <RiLoader4Line className="h-4 w-4 animate-spin" aria-hidden="true" />
              ) : (
                <>
                  <RiCheckLine className="h-4 w-4" aria-hidden="true" />
                  Sync {plural(previewClasses, 'class', 'classes')} · {plural(previewExams, 'exam')}
                </>
              )}
            </Button>
          ) : (
            <Button
              type="button"
              size="lg"
              className="h-12 w-full rounded-2xl"
              onClick={processUrl}
              disabled={isParsing || (!isSecureConnected && !subscriptionUrl.trim())}
            >
              {isParsing ? (
                <>
                  <RiLoader4Line className="h-4 w-4 animate-spin" aria-hidden="true" />
                  Fetching…
                </>
              ) : (
                'Fetch calendar'
              )}
            </Button>
          )}
        </SheetFooter>
      </DialogContent>
    </Dialog>
  );
}
