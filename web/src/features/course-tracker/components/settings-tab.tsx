import { useEffect, useState } from 'react';
import { Button } from '@/components/ui/button';
import { ConfirmDialog } from '@/components/ui/confirm-dialog';
import { Toggle } from '@/components/ui/toggle';
import { RiAddLine, RiCheckLine, RiSubtractLine } from '@remixicon/react';
import { SheetSectionTitle } from '@/features/trips/sheet-chrome';
import {
  DEFAULT_ATTENDANCE_LIMITS,
  DEFAULT_COURSE_TOTAL_POINTS,
  DEFAULT_GRADING_SCALE,
  type CourseTracker,
  type GradingScaleTier,
} from '../tracker-types';
import { isOnline } from '@/lib/online';
import { cn } from '@/lib/utils';
import { clampNumber, normalizeDecimalInput, parseFiniteNumber, validateScaleOrder } from '../numeric-input';
import { GRADE_NAMES, GRADE_TONES } from '../grades';
import { GradeScaleBar } from './grade-scale-bar';

interface SettingsTabProps {
  tracker: CourseTracker;
  onUpdateLimits: (limits: {
    maxAbsencesAllowed: number | null;
    minAttendancePercent: number | null;
    useWeightedCategories?: boolean;
    totalPoints?: number;
  }) => void;
  onUpdateGradingScale: (scale: GradingScaleTier[]) => void;
  /** Marks the seeded rules as checked against the syllabus. */
  onConfirmSettings?: () => void;
}

const MAX_ABSENCES = 50;

function tierInputsFromScale(scale: GradingScaleTier[]): Record<number, string> {
  const valueFor = (grade: number) => {
    const tier = scale.find((t) => t.grade === grade);
    return tier ? String(tier.minPercentage) : '0';
  };
  return { 5: valueFor(5), 4: valueFor(4), 3: valueFor(3), 2: valueFor(2) };
}

/** The percentages a grade covers, e.g. "75–86%", read from the committed scale. */
function gradeRange(scale: GradingScaleTier[], grade: 2 | 3 | 4 | 5): string {
  const min = scale.find((t) => t.grade === grade)?.minPercentage ?? 0;
  if (grade === 5) return `${min}–100%`;
  const next = scale.find((t) => t.grade === grade + 1)?.minPercentage ?? 100;
  return `${min}–${Number.isInteger(next) ? next - 1 : next}%`;
}

const FIELD_CLASS =
  'h-10 rounded-[14px] border border-subtle bg-surface-secondary text-center text-[15px] font-black tabular-nums text-primary focus:border-accent-primary focus:ring-1 focus:ring-accent-primary focus:outline-none';

const STEPPER_BUTTON_CLASS =
  'mark-glass flex h-10 w-10 items-center justify-center rounded-full text-primary transition-[transform,opacity] cursor-pointer active:scale-95 disabled:cursor-not-allowed disabled:opacity-40 focus-visible:outline focus-visible:outline-2 focus-visible:outline-accent-primary';

export function SettingsTab({ tracker, onUpdateLimits, onUpdateGradingScale, onConfirmSettings }: SettingsTabProps) {
  const [maxAbsencesInput, setMaxAbsencesInput] = useState(
    tracker.attendance.maxAbsencesAllowed !== null ? String(tracker.attendance.maxAbsencesAllowed) : ''
  );
  const [totalPointsInput, setTotalPointsInput] = useState(
    String(tracker.points.totalPoints || DEFAULT_COURSE_TOTAL_POINTS)
  );
  const [scale, setScale] = useState<GradingScaleTier[]>(tracker.points.gradingScale || [...DEFAULT_GRADING_SCALE]);
  const [tierInputs, setTierInputs] = useState<Record<number, string>>(() =>
    tierInputsFromScale(tracker.points.gradingScale || [...DEFAULT_GRADING_SCALE])
  );
  const [scaleError, setScaleError] = useState<string | null>(null);
  const [saveMessage, setSaveMessage] = useState<string | null>(null);
  /** Which section earned the "Saved" flash, so the feedback lands where the edit happened. */
  const [saveWhere, setSaveWhere] = useState<'attendance' | 'grades' | 'points'>('attendance');
  const [saveTick, setSaveTick] = useState(0);
  const [confirmReset, setConfirmReset] = useState(false);

  // Each save restarts the dismiss timer; the message clears itself after a
  // moment. Kept in an effect (not a timer ref) so no ref is touched from
  // render-reachable code.
  useEffect(() => {
    if (!saveMessage) return;
    const timer = setTimeout(() => setSaveMessage(null), 2600);
    return () => clearTimeout(timer);
  }, [saveMessage, saveTick]);

  // Sync internal state when the external tracker changes. Adjusted during
  // render so edits from the previous tracker can never paint for a frame.
  const [prevTracker, setPrevTracker] = useState(tracker);
  if (tracker !== prevTracker) {
    setPrevTracker(tracker);
    setMaxAbsencesInput(
      tracker.attendance.maxAbsencesAllowed !== null ? String(tracker.attendance.maxAbsencesAllowed) : ''
    );
    setTotalPointsInput(String(tracker.points.totalPoints || DEFAULT_COURSE_TOTAL_POINTS));
    const nextScale = tracker.points.gradingScale || [...DEFAULT_GRADING_SCALE];
    setScale(nextScale);
    setTierInputs(tierInputsFromScale(nextScale));
    setScaleError(null);
  }

  const flashSaved = (where: 'attendance' | 'grades' | 'points' = 'attendance') => {
    setSaveMessage(isOnline() ? 'Saved' : 'Saved on this device — syncs when online');
    setSaveWhere(where);
    setSaveTick((t) => t + 1);
  };

  // Commit-on-blur: the field is free to hold an unclamped draft while typing,
  // and only the committed value is clamped, normalized, and persisted.
  const commitLimits = (overrides?: { maxInput?: string }) => {
    const maxRaw = overrides?.maxInput ?? maxAbsencesInput;
    const maxVal =
      maxRaw.trim() === '' ? null : clampNumber(Math.round(parseFiniteNumber(maxRaw) ?? 0), 0, MAX_ABSENCES);

    setMaxAbsencesInput(maxVal !== null ? String(maxVal) : '');

    const unchanged =
      maxVal === tracker.attendance.maxAbsencesAllowed && tracker.attendance.minAttendancePercent === null;
    if (unchanged) return;

    onUpdateLimits({ maxAbsencesAllowed: maxVal, minAttendancePercent: null });
    flashSaved();
  };

  const limited = maxAbsencesInput.trim() !== '';
  const committedMax = parseFiniteNumber(maxAbsencesInput) ?? 0;

  const setLimited = (on: boolean) => {
    commitLimits({ maxInput: on ? String(DEFAULT_ATTENDANCE_LIMITS.maxAbsencesAllowed) : '' });
  };

  const stepAbsences = (delta: number) => {
    commitLimits({ maxInput: String(clampNumber(Math.round(committedMax) + delta, 0, MAX_ABSENCES)) });
  };

  const commitTier = (grade: 2 | 3 | 4 | 5) => {
    const raw = tierInputs[grade] ?? '';
    const parsed = parseFiniteNumber(raw);
    if (parsed === null) {
      const committed = scale.find((t) => t.grade === grade)?.minPercentage ?? 0;
      setTierInputs((prev) => ({ ...prev, [grade]: String(committed) }));
      setScaleError(null);
      return;
    }

    const value = clampNumber(parsed, 0, 100);
    const nextScale = scale.map((t) => (t.grade === grade ? { ...t, minPercentage: value } : t));
    const error = validateScaleOrder(nextScale);

    setTierInputs((prev) => ({ ...prev, [grade]: String(value) }));
    if (error) {
      setScaleError(error);
      return;
    }

    setScale(nextScale);
    setScaleError(null);
    onUpdateGradingScale(nextScale);
    flashSaved('grades');
  };

  // Course total points: the denominator of the grade card. Blank or invalid
  // input falls back to the default instead of persisting a broken denominator
  // that every points readout divides by.
  const commitTotalPoints = () => {
    const parsed = parseFiniteNumber(totalPointsInput);
    const value = parsed === null ? DEFAULT_COURSE_TOTAL_POINTS : clampNumber(Math.round(parsed), 1, 10000);
    setTotalPointsInput(String(value));
    if (value === (tracker.points.totalPoints || DEFAULT_COURSE_TOTAL_POINTS)) return;

    onUpdateLimits({
      maxAbsencesAllowed: tracker.attendance.maxAbsencesAllowed,
      minAttendancePercent: tracker.attendance.minAttendancePercent,
      totalPoints: value,
    });
    flashSaved('points');
  };

  const handleReset = () => {
    const nextScale = [...DEFAULT_GRADING_SCALE];
    setScale(nextScale);
    setTierInputs(tierInputsFromScale(nextScale));
    setMaxAbsencesInput(String(DEFAULT_ATTENDANCE_LIMITS.maxAbsencesAllowed));
    setTotalPointsInput(String(DEFAULT_COURSE_TOTAL_POINTS));
    setScaleError(null);
    onUpdateGradingScale(nextScale);
    onUpdateLimits({
      maxAbsencesAllowed: DEFAULT_ATTENDANCE_LIMITS.maxAbsencesAllowed,
      minAttendancePercent: null,
      totalPoints: DEFAULT_COURSE_TOTAL_POINTS,
    });
    flashSaved();
  };

  const commitOnEnter = (commit: () => void) => (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key !== 'Enter') return;
    e.preventDefault();
    commit();
    e.currentTarget.blur();
  };

  const savedFlash = (where: 'attendance' | 'grades' | 'points') =>
    saveWhere === where && saveMessage ? (
      <span
        className="flex shrink-0 items-center gap-1 text-xs font-bold text-tint-emerald animate-fade-in"
        role="status"
        aria-live="polite"
      >
        <RiCheckLine className="h-3.5 w-3.5" aria-hidden="true" /> {saveMessage}
      </span>
    ) : undefined;

  return (
    <div className="space-y-6">
      {/* Seeded defaults must never pass for the course's actual rules. */}
      {!tracker.settingsConfirmed && onConfirmSettings && (
        <section aria-labelledby="settings-syllabus-title" className="rounded-3xl card-glass card-depth p-[18px]">
          <h3 id="settings-syllabus-title" className="text-[15px] font-extrabold text-primary">
            Check your syllabus
          </h3>
          <p className="mt-1 text-[13px] leading-snug text-secondary">
            These are Vectra&apos;s defaults. Match them to this course so your standing and grade are right.
          </p>
          <button
            type="button"
            onClick={onConfirmSettings}
            className="mark-depth mark-depth-present mt-3.5 flex h-11 w-full items-center justify-center gap-2 rounded-[20px] text-sm font-extrabold transition-transform cursor-pointer active:scale-[0.98] focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent-primary"
          >
            <RiCheckLine className="h-4 w-4" aria-hidden="true" />
            They match
          </button>
        </section>
      )}

      {/* Attendance */}
      <section className="space-y-3" aria-labelledby="settings-attendance-title">
        <SheetSectionTitle id="settings-attendance-title" action={savedFlash('attendance')}>
          Attendance
        </SheetSectionTitle>
        <div className="divide-y divide-subtle rounded-3xl card-glass card-depth">
          <div className="flex items-center justify-between gap-4 px-4 py-3.5">
            <div className="min-w-0">
              <p id="limit-absences-label" className="text-sm font-semibold text-primary">
                Limit absences
              </p>
              <p className="mt-0.5 text-xs text-muted">Off if the course doesn&apos;t count them</p>
            </div>
            <Toggle checked={limited} onChange={setLimited} aria-label="Limit absences" />
          </div>
          {limited && (
            <div className="flex items-center justify-between gap-4 px-4 py-3.5">
              <div className="min-w-0">
                <label htmlFor="max-absences" className="block text-sm font-semibold text-primary">
                  Absences allowed
                </label>
                <p className="mt-0.5 text-xs text-muted">Lectures and seminars together</p>
              </div>
              <div className="flex shrink-0 items-center gap-1">
                <button
                  type="button"
                  onClick={() => stepAbsences(-1)}
                  disabled={committedMax <= 0}
                  aria-label="One fewer absence"
                  className={STEPPER_BUTTON_CLASS}
                >
                  <RiSubtractLine className="h-4 w-4" aria-hidden="true" />
                </button>
                <input
                  id="max-absences"
                  type="text"
                  inputMode="numeric"
                  value={maxAbsencesInput}
                  onChange={(e) => setMaxAbsencesInput(e.target.value.replace(/[^0-9]/g, '').slice(0, 2))}
                  onBlur={() => commitLimits()}
                  onKeyDown={commitOnEnter(() => commitLimits())}
                  className="h-10 w-10 rounded-[14px] bg-transparent text-center text-xl font-black tabular-nums text-primary focus:outline-none focus:ring-1 focus:ring-accent-primary"
                />
                <button
                  type="button"
                  onClick={() => stepAbsences(1)}
                  disabled={committedMax >= MAX_ABSENCES}
                  aria-label="One more absence"
                  className={STEPPER_BUTTON_CLASS}
                >
                  <RiAddLine className="h-4 w-4" aria-hidden="true" />
                </button>
              </div>
            </div>
          )}
        </div>
      </section>

      {/* Course total points — the denominator of the grade card */}
      <section className="space-y-3" aria-labelledby="settings-points-title">
        <SheetSectionTitle id="settings-points-title" action={savedFlash('points')}>
          Points
        </SheetSectionTitle>
        <div className="flex items-center justify-between gap-4 rounded-3xl card-glass card-depth px-4 py-3.5">
          <div className="min-w-0">
            <label htmlFor="total-points" className="block text-sm font-semibold text-primary">
              Course total
            </label>
            <p className="mt-0.5 text-xs text-muted">Everything you can earn this term</p>
          </div>
          <div className="flex shrink-0 items-center gap-2">
            <input
              id="total-points"
              type="text"
              inputMode="numeric"
              value={totalPointsInput}
              onChange={(e) => setTotalPointsInput(e.target.value.replace(/[^0-9]/g, '').slice(0, 5))}
              onBlur={() => commitTotalPoints()}
              onKeyDown={commitOnEnter(() => commitTotalPoints())}
              className={cn(FIELD_CLASS, 'w-[72px]')}
            />
            <span className="text-[13px] font-semibold text-secondary">pts</span>
          </div>
        </div>
      </section>

      {/* Grade cutoffs */}
      <section className="space-y-3" aria-labelledby="settings-grades-title">
        <SheetSectionTitle id="settings-grades-title" action={savedFlash('grades')}>
          Grade cutoffs
        </SheetSectionTitle>
        <div className="divide-y divide-subtle overflow-hidden rounded-3xl card-glass card-depth">
          {/* The cutoffs as bands, redrawn as each one commits. */}
          <GradeScaleBar
            className="px-4 pb-3 pt-4"
            scale={scale}
            ariaLabel={`Grade cutoffs: ${[2, 3, 4, 5]
              .map((g) => `Grade ${g} from ${scale.find((t) => t.grade === g)?.minPercentage ?? 0}%`)
              .join(', ')}`}
          />
          {([5, 4, 3, 2] as const).map((grade) => (
            <div key={grade} className="flex items-center gap-3 px-4 py-3">
              <span
                className={cn('flex h-10 w-9 shrink-0 items-center justify-center text-[28px] font-black tracking-tight', GRADE_TONES[grade].text)}
                aria-hidden="true"
              >
                {grade}
              </span>
              <span className="min-w-0 flex-1">
                <label htmlFor={`grade-tier-${grade}`} className="block text-sm font-semibold text-primary">
                  {GRADE_NAMES[grade]}
                </label>
                <span className="block font-mono text-xs tabular-nums text-muted">{gradeRange(scale, grade)}</span>
              </span>
              <span className="text-xs text-muted" aria-hidden="true">
                from
              </span>
              <input
                id={`grade-tier-${grade}`}
                type="text"
                inputMode="decimal"
                aria-label={`Grade ${grade} minimum percentage`}
                value={tierInputs[grade] ?? ''}
                onChange={(e) =>
                  setTierInputs((prev) => ({ ...prev, [grade]: normalizeDecimalInput(e.target.value).slice(0, 5) }))
                }
                onBlur={() => commitTier(grade)}
                onKeyDown={commitOnEnter(() => commitTier(grade))}
                className={cn(FIELD_CLASS, 'w-[52px]')}
              />
              <span className="text-[13px] text-secondary">%</span>
            </div>
          ))}
          {scaleError && (
            <p role="alert" className="px-4 py-3 text-xs font-semibold leading-snug text-tint-red">
              {scaleError}
            </p>
          )}
        </div>
      </section>

      <Button
        type="button"
        variant="outline"
        onClick={() => setConfirmReset(true)}
        className="h-11 w-full rounded-[20px] text-[13px] font-bold text-secondary"
      >
        Reset to defaults
      </Button>

      <ConfirmDialog
        open={confirmReset}
        onClose={() => setConfirmReset(false)}
        onConfirm={handleReset}
        title="Reset to defaults?"
        description="Restores the default absence limit (3), grading scale (87 / 75 / 62 / 50) and course total (100 pts). Your attendance records and assessments are not affected."
        confirmText="Reset settings"
        cancelText="Keep my settings"
      />
    </div>
  );
}
