import { useId, useState } from 'react';
import { Dialog, DialogContent } from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Toggle } from '@/components/ui/toggle';
import { SheetBar, SheetBody, SheetFooter, SheetSectionTitle, SHEET_GUTTER } from '@/features/trips/sheet-chrome';
import { DeleteRow, FormField, FormGroup, PillRadio, SwitchRow, SHEET_INPUT_CLASS } from '@/components/ui/sheet-form';
import { toast } from 'sonner';
import { estimateGrade } from '../points-calc';
import { clampNumber, normalizeDecimalInput, parseFiniteNumber } from '../numeric-input';
import { isOnline } from '@/lib/online';
import { cn } from '@/lib/utils';
import { type AssessmentItem, type GradingScaleTier } from '../tracker-types';
import { genId } from '@/lib/id';

interface AddAssessmentDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onAddAssessment: (item: AssessmentItem) => void;
  /** An existing assessment to edit; the sheet saves it in place under its own id. */
  item?: AssessmentItem | null;
  /** Shown only while editing: removes the assessment and closes the sheet. */
  onDeleteAssessment?: () => void;
  /** Called after the close animation finishes. */
  onExited?: () => void;
  useWeightedCategories?: boolean;
  /** Course context for the header (omitted when the tracker has no name). */
  courseName?: string;
  /** Course percentage the projected grade is read from (Points Earned basis). */
  projectedPercentage?: number;
  /** Projected grade context from the points summary. */
  estimatedGrade?: 1 | 2 | 3 | 4 | 5;
  /** The student's own grading scale, used for the live grade preview. */
  gradingScale?: GradingScaleTier[];
  /** Sum of weightPercent already assigned on other assessments (excluding the one being edited). */
  existingWeightTotal?: number;
}

function formatWeight(value: number): string {
  return Number.isInteger(value) ? String(value) : value.toFixed(1);
}

const NUMBER_FIELD_CLASS = cn(SHEET_INPUT_CLASS, 'text-center font-black tabular-nums');

type AddAssessmentFormProps = Omit<AddAssessmentDialogProps, 'open' | 'onExited'> & { titleId: string };

const draftNumber = (value: number | null | undefined, fallback: string) =>
  typeof value === 'number' && Number.isFinite(value) ? String(value) : fallback;

/**
 * Mounted inside DialogContent, so a closed sheet drops its draft and the
 * next one opens on clean defaults (or on the assessment being edited).
 */
function AddAssessmentForm({
  titleId,
  onOpenChange,
  onAddAssessment,
  item,
  onDeleteAssessment,
  useWeightedCategories,
  courseName,
  projectedPercentage,
  estimatedGrade,
  gradingScale,
  existingWeightTotal = 0,
}: AddAssessmentFormProps) {
  const ids = useId();
  const [scrolled, setScrolled] = useState(false);
  const editing = !!item;
  const [name, setName] = useState(item?.name ?? '');
  const [date, setDate] = useState(item?.date ?? '');
  const [scoreMode, setScoreMode] = useState<'points' | 'percent'>('points');
  const [earnedPoints, setEarnedPoints] = useState(draftNumber(item?.earnedPoints, '0'));
  const [maxPoints, setMaxPoints] = useState(draftNumber(item?.maxPoints, '20'));
  const [percentage, setPercentage] = useState('85');
  const [weightPercent, setWeightPercent] = useState(draftNumber(item?.weightPercent, ''));
  const [isMustPass, setIsMustPass] = useState(item?.isMustPass ?? false);
  const [minPassPoints, setMinPassPoints] = useState(draftNumber(item?.minPassPoints, '10'));
  const [minPassPercent, setMinPassPercent] = useState('50');
  const [isCompleted, setIsCompleted] = useState(item ? item.isCompleted ?? item.earnedPoints > 0 : true);
  const [notes, setNotes] = useState(item?.notes ?? '');
  const [nameError, setNameError] = useState<string | null>(null);
  const [maxError, setMaxError] = useState<string | null>(null);

  const maxParsed = parseFiniteNumber(maxPoints);

  // Commit-on-blur: each field may hold an unclamped draft while typing; only
  // the committed value is clamped, normalized, and persisted.
  const commitMaxPoints = () => {
    const parsed = parseFiniteNumber(maxPoints);
    if (parsed === null || parsed <= 0) {
      setMaxError('Enter the maximum points for this assessment (e.g. 20).');
      return;
    }
    setMaxError(null);
    setMaxPoints(String(clampNumber(parsed, 0.1, 10000)));
  };

  const commitEarnedPoints = () => {
    const parsed = parseFiniteNumber(earnedPoints);
    setEarnedPoints(parsed === null ? '0' : String(clampNumber(parsed, 0, 10000)));
  };

  const commitPercentage = () => {
    const parsed = parseFiniteNumber(percentage);
    setPercentage(parsed === null ? '0' : String(clampNumber(parsed, 0, 200)));
  };

  const commitWeightPercent = () => {
    const parsed = parseFiniteNumber(weightPercent);
    setWeightPercent(parsed === null ? '' : String(clampNumber(parsed, 0, 100)));
  };

  const commitMinPass = () => {
    if (scoreMode === 'percent') {
      const parsed = parseFiniteNumber(minPassPercent);
      setMinPassPercent(parsed === null ? '50' : String(clampNumber(parsed, 0, 100)));
    } else {
      const parsed = parseFiniteNumber(minPassPoints);
      setMinPassPoints(parsed === null ? '' : String(clampNumber(parsed, 0, maxParsed ?? 10000)));
    }
  };

  const commitOnEnter = (commit: () => void) => (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key !== 'Enter') return;
    e.preventDefault();
    commit();
    e.currentTarget.blur();
  };

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    const trimmedName = name.trim();
    if (!trimmedName) {
      setNameError('Name this assessment, e.g. “Midterm Exam 1” or “ZH 2”.');
      return;
    }
    setNameError(null);

    const max = parseFiniteNumber(maxPoints);
    if (max === null || max <= 0) {
      setMaxError('Enter the maximum points for this assessment (e.g. 20).');
      return;
    }
    setMaxError(null);
    const maxClamped = clampNumber(max, 0.1, 10000);

    let earned: number;
    let minPass: number | null = null;

    if (scoreMode === 'percent') {
      const pct = clampNumber(parseFiniteNumber(percentage) ?? 0, 0, 200);
      earned = Math.round((pct / 100) * maxClamped * 100) / 100;
      if (isMustPass) {
        const passPct = clampNumber(parseFiniteNumber(minPassPercent) ?? 50, 0, 100);
        minPass = Math.round((passPct / 100) * maxClamped * 100) / 100;
      }
    } else {
      earned = clampNumber(parseFiniteNumber(earnedPoints) ?? 0, 0, 10000);
      if (isMustPass) {
        // Blank means "half the points", derived from this assessment's own max
        // — never the old fixed 10 that silently became a 100% threshold.
        minPass = clampNumber(parseFiniteNumber(minPassPoints) ?? maxClamped * 0.5, 0, maxClamped);
      }
    }

    const weight =
      weightPercent.trim() === '' ? null : clampNumber(parseFiniteNumber(weightPercent) ?? 0, 0, 100);

    onAddAssessment({
      ...item,
      id: item?.id ?? genId(),
      name: trimmedName,
      earnedPoints: earned,
      maxPoints: maxClamped,
      weightPercent: weight,
      isMustPass,
      minPassPoints: minPass,
      isCompleted,
      date: date || null,
      notes: notes.trim() || undefined,
    });

    toast.success(editing ? 'Assessment updated' : 'Assessment saved', {
      description: `${trimmedName} · ${earned} / ${maxClamped} pts${isOnline() ? '' : ' · saved on this device, syncs when online'}`,
    });

    onOpenChange(false);
  };

  // Live readouts -----------------------------------------------------------
  const calculatedPoints = Math.round(((parseFiniteNumber(percentage) ?? 0) / 100) * (maxParsed || 20) * 100) / 100;

  const scorePercent = (() => {
    if (scoreMode === 'percent') {
      const pct = parseFiniteNumber(percentage);
      return pct === null ? null : clampNumber(pct, 0, 200);
    }
    const earned = parseFiniteNumber(earnedPoints);
    if (earned === null || maxParsed === null || maxParsed <= 0) return null;
    return clampNumber((earned / maxParsed) * 100, 0, 200);
  })();

  const scaleHint = (() => {
    if (scorePercent === null || !gradingScale || gradingScale.length === 0) return null;
    const grade = estimateGrade(scorePercent, gradingScale);
    const cutoff = [...gradingScale].sort((a, b) => b.minPercentage - a.minPercentage).find((t) => t.grade === grade);
    return `Grade ${grade}${cutoff ? ` (≥${cutoff.minPercentage}%)` : ''}`;
  })();

  // Over 100% is allowed (bonus points exist) but usually a typo: say so gently.
  const overMaximum = scorePercent !== null && (scoreMode === 'percent'
    ? (parseFiniteNumber(percentage) ?? 0) > 100
    : (parseFiniteNumber(earnedPoints) ?? 0) > (maxParsed ?? Infinity));
  const overMaximumHint = (
    <span className="text-tint-amber">More than the maximum. Fine for bonus points, otherwise check for a typo.</span>
  );

  const minPassThreshold = scoreMode === 'percent' ? parseFiniteNumber(minPassPercent) : parseFiniteNumber(minPassPoints);
  const scoreForCompare = scoreMode === 'percent' ? parseFiniteNumber(percentage) : parseFiniteNumber(earnedPoints);
  const belowMinPass =
    isMustPass &&
    minPassThreshold !== null &&
    minPassThreshold > 0 &&
    scoreForCompare !== null &&
    scoreForCompare < minPassThreshold;

  const weightNow = parseFiniteNumber(weightPercent);
  const weightProjected = existingWeightTotal + (weightNow ?? 0);

  const subtitleParts: string[] = [];
  if (courseName) subtitleParts.push(courseName);
  if (estimatedGrade) {
    subtitleParts.push(
      `Projected Grade ${estimatedGrade}${projectedPercentage !== undefined ? ` (${projectedPercentage.toFixed(0)}%)` : ''}`
    );
  }
  const subtitle = subtitleParts.join(' · ');

  const scoreOptions = [
    { value: 'points' as const, label: 'Points' },
    { value: 'percent' as const, label: 'Percent' },
  ];

  return (
    <form onSubmit={handleSubmit} className="flex min-h-0 flex-1 flex-col">
      <SheetBar
        leading="close"
        onLeading={() => onOpenChange(false)}
        title={editing ? 'Edit assessment' : 'Add assessment'}
        titleId={titleId}
        subtitle={subtitle || undefined}
        scrolled={scrolled}
      />

      <SheetBody onScrolledChange={setScrolled} fadeBottom={false} className={cn('space-y-6 pb-6 pt-3', SHEET_GUTTER)}>
        <section aria-labelledby={`${ids}-what`} className="space-y-3">
          <SheetSectionTitle id={`${ids}-what`}>Assessment</SheetSectionTitle>
          <FormGroup>
            <FormField label="Name" htmlFor="assessment-name" error={nameError} errorId="assessment-name-error">
              <Input
                id="assessment-name"
                value={name}
                onChange={(e) => {
                  setName(e.target.value);
                  if (nameError) setNameError(null);
                }}
                placeholder="Midterm 1, ZH 2, homework…"
                className={SHEET_INPUT_CLASS}
                aria-invalid={nameError !== null}
                aria-describedby={nameError ? 'assessment-name-error' : undefined}
                required
              />
            </FormField>
            <FormField label="Date" htmlFor="assessment-date" hint="Optional. Shown next to the name in the list.">
              <Input
                id="assessment-date"
                type="date"
                value={date}
                onChange={(e) => setDate(e.target.value)}
                className={SHEET_INPUT_CLASS}
              />
            </FormField>
          </FormGroup>
        </section>

        <section aria-labelledby={`${ids}-score`} className="space-y-3">
          <SheetSectionTitle id={`${ids}-score`}>Score</SheetSectionTitle>
          <FormGroup>
            <FormField label="Enter it as" labelId="score-type-label">
              <PillRadio value={scoreMode} onChange={setScoreMode} options={scoreOptions} labelledBy="score-type-label" />
            </FormField>

            {scoreMode === 'percent' ? (
              <FormField
                label="Result"
                labelId="score-percent-label"
                error={maxError}
                hint={
                  overMaximum ? (
                    overMaximumHint
                  ) : <>
                    {calculatedPoints} / {maxParsed || 20} pts
                    {scaleHint && (
                      <>
                        {' · '}
                        <span className="font-semibold text-primary">{scaleHint}</span> on your scale
                      </>
                    )}
                  </>
                }
              >
                <div className="flex items-center gap-2" role="group" aria-labelledby="score-percent-label">
                  <Input
                    id="earned-percentage"
                    type="text"
                    inputMode="decimal"
                    aria-label="Score percentage"
                    value={percentage}
                    onChange={(e) => setPercentage(normalizeDecimalInput(e.target.value).slice(0, 6))}
                    onBlur={commitPercentage}
                    onKeyDown={commitOnEnter(commitPercentage)}
                    placeholder="85"
                    className={cn(NUMBER_FIELD_CLASS, 'text-tint-emerald')}
                    required
                  />
                  <span className="shrink-0 text-sm font-semibold text-secondary">% of</span>
                  <Input
                    id="max-points-percent"
                    type="text"
                    inputMode="decimal"
                    aria-label="Maximum points"
                    value={maxPoints}
                    onChange={(e) => {
                      setMaxPoints(normalizeDecimalInput(e.target.value).slice(0, 8));
                      if (maxError) setMaxError(null);
                    }}
                    onBlur={commitMaxPoints}
                    onKeyDown={commitOnEnter(commitMaxPoints)}
                    placeholder="20"
                    className={NUMBER_FIELD_CLASS}
                    aria-invalid={maxError !== null}
                    required
                  />
                  <span className="shrink-0 text-sm font-semibold text-secondary">pts</span>
                </div>
              </FormField>
            ) : (
              <FormField
                label="Result"
                labelId="score-points-label"
                error={maxError}
                hint={
                  overMaximum ? (
                    overMaximumHint
                  ) : scaleHint ? (
                    <>
                      <span className="font-semibold text-primary">{scaleHint}</span> on your scale
                    </>
                  ) : (
                    'Points earned out of the maximum.'
                  )
                }
              >
                <div className="flex items-center gap-2" role="group" aria-labelledby="score-points-label">
                  <Input
                    id="earned-points"
                    type="text"
                    inputMode="decimal"
                    aria-label="Points earned"
                    value={earnedPoints}
                    onChange={(e) => setEarnedPoints(normalizeDecimalInput(e.target.value).slice(0, 8))}
                    onBlur={commitEarnedPoints}
                    onKeyDown={commitOnEnter(commitEarnedPoints)}
                    className={cn(NUMBER_FIELD_CLASS, 'text-tint-emerald')}
                    required
                  />
                  <span className="shrink-0 text-sm font-semibold text-secondary">/</span>
                  <Input
                    id="max-points"
                    type="text"
                    inputMode="decimal"
                    aria-label="Maximum points"
                    value={maxPoints}
                    onChange={(e) => {
                      setMaxPoints(normalizeDecimalInput(e.target.value).slice(0, 8));
                      if (maxError) setMaxError(null);
                    }}
                    onBlur={commitMaxPoints}
                    onKeyDown={commitOnEnter(commitMaxPoints)}
                    className={NUMBER_FIELD_CLASS}
                    aria-invalid={maxError !== null}
                    required
                  />
                  <span className="shrink-0 text-sm font-semibold text-secondary">pts</span>
                </div>
              </FormField>
            )}

            <SwitchRow
              id="assessment-scored-label"
              label="Already scored"
              description="Off for an upcoming assessment you are planning for."
              control={<Toggle checked={isCompleted} onChange={setIsCompleted} aria-label="Already scored" />}
            />
          </FormGroup>
        </section>

        {useWeightedCategories && (
          <section aria-labelledby={`${ids}-weight`} className="space-y-3">
            <SheetSectionTitle id={`${ids}-weight`}>Weight</SheetSectionTitle>
            <FormGroup>
              <FormField
                label="Share of the final grade"
                htmlFor="weight-percent"
                hint={
                  weightProjected > 100 ? (
                    <span role="status" className="font-semibold text-tint-amber">
                      Over 100% in total. Double-check the weights on your syllabus.
                    </span>
                  ) : existingWeightTotal > 0 ? (
                    `Other assessments add up to ${formatWeight(existingWeightTotal)}%. With this one: ${formatWeight(weightProjected)}%.`
                  ) : (
                    `With this one the weights add up to ${formatWeight(weightProjected)}%.`
                  )
                }
              >
                <div className="flex items-center gap-2">
                  <Input
                    id="weight-percent"
                    type="text"
                    inputMode="decimal"
                    placeholder="25"
                    value={weightPercent}
                    onChange={(e) => setWeightPercent(normalizeDecimalInput(e.target.value).slice(0, 5))}
                    onBlur={commitWeightPercent}
                    onKeyDown={commitOnEnter(commitWeightPercent)}
                    className={cn(NUMBER_FIELD_CLASS, 'max-w-28')}
                  />
                  <span className="text-sm font-semibold text-secondary">%</span>
                </div>
              </FormField>
            </FormGroup>
          </section>
        )}

        <section aria-labelledby={`${ids}-rules`} className="space-y-3">
          <SheetSectionTitle id={`${ids}-rules`}>Rules</SheetSectionTitle>
          <FormGroup>
            <SwitchRow
              id="assessment-must-pass-label"
              label="Must pass"
              description="A minimum score here is needed to pass the course."
              control={<Toggle checked={isMustPass} onChange={setIsMustPass} aria-label="Must pass" />}
            />
            {isMustPass && (
              <FormField
                label={scoreMode === 'percent' ? 'Minimum to pass' : 'Minimum points to pass'}
                htmlFor="min-pass-points"
                error={
                  belowMinPass
                    ? 'This score is below the minimum, so the course counts as Grade 1 until it improves.'
                    : null
                }
                hint={scoreMode === 'percent' ? 'Of this assessment.' : `Out of ${maxParsed ?? maxPoints} pts.`}
              >
                <div className="flex items-center gap-2">
                  <Input
                    id="min-pass-points"
                    type="text"
                    inputMode="decimal"
                    value={scoreMode === 'percent' ? minPassPercent : minPassPoints}
                    onChange={(e) => {
                      const next = normalizeDecimalInput(e.target.value).slice(0, 8);
                      if (scoreMode === 'percent') {
                        setMinPassPercent(next);
                      } else {
                        setMinPassPoints(next);
                      }
                    }}
                    onBlur={commitMinPass}
                    onKeyDown={commitOnEnter(commitMinPass)}
                    placeholder={scoreMode === 'percent' ? '50' : '10'}
                    className={cn(NUMBER_FIELD_CLASS, 'max-w-28', belowMinPass && 'border-rose-500/50 text-tint-red')}
                  />
                  <span className="text-sm font-semibold text-secondary">{scoreMode === 'percent' ? '%' : 'pts'}</span>
                </div>
              </FormField>
            )}
          </FormGroup>
        </section>

        <FormGroup>
          <FormField label="Notes" htmlFor="assessment-notes">
            <Input
              id="assessment-notes"
              value={notes}
              onChange={(e) => setNotes(e.target.value)}
              placeholder="Open book, in-class paper quiz…"
              className={SHEET_INPUT_CLASS}
            />
          </FormField>
        </FormGroup>

        {editing && onDeleteAssessment && (
          <DeleteRow label="Delete assessment" onConfirm={onDeleteAssessment} />
        )}
      </SheetBody>

      <SheetFooter>
        <Button type="submit" size="lg" className="h-12 w-full rounded-2xl">
          {editing ? 'Save changes' : 'Add assessment'}
        </Button>
      </SheetFooter>
    </form>
  );
}

export function AddAssessmentDialog({ open, onOpenChange, onExited, ...formProps }: AddAssessmentDialogProps) {
  const titleId = useId();
  return (
    <Dialog open={open} onOpenChange={onOpenChange} onExited={onExited}>
      <DialogContent
        edgeToEdge
        aria-labelledby={titleId}
        className="sm:max-w-md max-h-[92vh] rounded-[28px] p-0 overflow-hidden flex flex-col gap-0"
      >
        <AddAssessmentForm key={formProps.item?.id ?? 'new'} titleId={titleId} onOpenChange={onOpenChange} {...formProps} />
      </DialogContent>
    </Dialog>
  );
}
