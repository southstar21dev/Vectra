import { useState } from 'react';
import { Button } from '@/components/ui/button';
import { RiAddLine, RiAwardLine, RiShieldCrossLine } from '@remixicon/react';
import { SheetSectionTitle } from '@/features/trips/sheet-chrome';
import { toast } from 'sonner';
import { calculatePointsSummary, type PointsSummary } from '../points-calc';
import { AddAssessmentDialog } from './add-assessment-dialog';
import { AssessmentCard } from './assessment-card';
import { DEFAULT_GRADING_SCALE, DEFAULT_COURSE_TOTAL_POINTS, type AssessmentItem, type CourseTracker } from '../tracker-types';
import { GRADE_NAMES, GRADE_TONES, type Grade } from '../grades';
import { cn } from '@/lib/utils';

interface PointsTabProps {
  tracker: CourseTracker;
  onAddAssessment: (item: AssessmentItem) => void;
  onUpdateAssessment: (itemId: string, patch: Partial<AssessmentItem>) => void;
  onRemoveAssessment: (itemId: string) => void;
}

const GRADES: Grade[] = [1, 2, 3, 4, 5];

export function PointsTab({
  tracker,
  onAddAssessment,
  onUpdateAssessment,
  onRemoveAssessment,
}: PointsTabProps) {
  const [showAddModal, setShowAddModal] = useState(false);
  /** The assessment open in the sheet; kept through the close animation so the title does not flip. */
  const [editingItem, setEditingItem] = useState<AssessmentItem | null>(null);
  const assessments = tracker.points.assessments || [];
  const gradingScale = tracker.points.gradingScale || DEFAULT_GRADING_SCALE;
  /** The course's own total points — earned points are always read against it. */
  const totalPoints = tracker.points.totalPoints || DEFAULT_COURSE_TOTAL_POINTS;
  const weighted = !!tracker.points.useWeightedCategories;
  const summary: PointsSummary = calculatePointsSummary(assessments, {
    useWeightedCategories: weighted,
    gradingScale,
    totalPoints,
  });
  // Weighted courses grade from the weighted sum, so the bar must read from it
  // too — raw points would disagree with the grade shown above them.
  const coursePercentage = weighted ? summary.overallPercentage : (summary.totalEarned / totalPoints) * 100;
  const fillPercent = Math.max(0, Math.min(100, coursePercentage));
  /** Weighted mode only counts assessments that carry a weight. */
  const unweightedCount = weighted ? assessments.filter((item) => typeof item.weightPercent !== 'number').length : 0;
  const pointsRemaining = Math.max(0, totalPoints - summary.totalEarned);
  /** Weight already assigned elsewhere — the sheet warns when the total would exceed 100%. */
  const weightTotal = assessments.reduce(
    (total, item) => (item.id === editingItem?.id ? total : total + (item.weightPercent ?? 0)),
    0
  );

  const openAdd = () => {
    setEditingItem(null);
    setShowAddModal(true);
  };

  const openEdit = (item: AssessmentItem) => {
    setEditingItem(item);
    setShowAddModal(true);
  };

  const hasGrades = assessments.length > 0;
  const grade = summary.estimatedGrade as Grade;
  const tones = GRADE_TONES[grade];

  const removeAssessment = (item: AssessmentItem) => {
    onRemoveAssessment(item.id);
    toast('Assessment deleted', {
      description: item.name,
      action: { label: 'Undo', onClick: () => onAddAssessment(item) },
      duration: 6000,
    });
  };

  return (
    <div className="space-y-6">
      {/* The grade: where it sits on the five-point scale, then the points it is read from. */}
      <section aria-label="Grade" className="rounded-3xl card-glass card-depth p-5">
        {hasGrades ? (
          <p className="flex items-baseline gap-2.5">
            <span className={cn('text-[2.75rem] font-black leading-none tracking-tight', tones.text)}>{grade}</span>
            <span className="text-[15px] font-bold text-primary">{GRADE_NAMES[grade]}</span>
          </p>
        ) : (
          <p className="text-xl font-black tracking-tight text-primary">No grade yet</p>
        )}
        <div
          role="img"
          aria-label={hasGrades ? `Grade ${grade} of 5` : 'No grade yet'}
          className="mt-4 grid grid-cols-5 gap-1.5"
        >
          {GRADES.map((g) => (
            <span
              key={g}
              className={cn(
                'h-2.5 rounded-full transition-colors duration-500',
                !hasGrades || g > grade
                  ? 'bg-border-subtle'
                  : g === grade
                  ? cn(tones.fill, 'shadow-[inset_0_1px_0_rgba(255,255,255,0.35)]')
                  : tones.dim
              )}
            />
          ))}
        </div>
        <div className="mt-1.5 grid grid-cols-5 gap-1.5" aria-hidden="true">
          {GRADES.map((g) => (
            <span
              key={g}
              className={cn(
                'text-center text-xs tabular-nums',
                hasGrades && g === grade ? cn('font-extrabold', tones.text) : 'font-semibold text-muted'
              )}
            >
              {g}
            </span>
          ))}
        </div>

        <div className="mt-[18px] border-t border-subtle pt-4">
          <div className="flex items-baseline justify-between gap-3">
            <p className="tabular-nums">
              {weighted ? (
                <>
                  <span className="text-[15px] font-extrabold text-primary">{Math.round(summary.overallPercentage)}%</span>
                  <span className="text-[13px] text-secondary"> of the grade</span>
                </>
              ) : (
                <>
                  <span className="text-[15px] font-extrabold text-primary">{summary.totalEarned}</span>
                  <span className="text-[13px] text-secondary"> / {totalPoints} pts</span>
                </>
              )}
            </p>
            <span className="text-[13px] tabular-nums text-secondary">
              {!hasGrades
                ? 'Nothing logged'
                : weighted
                ? `${Math.max(0, 100 - Math.round(summary.overallPercentage))}% still open`
                : pointsRemaining > 0
                ? `${pointsRemaining} still open`
                : 'All scored'}
            </span>
          </div>
          <div
            role="img"
            aria-label={
              weighted
                ? `${fillPercent.toFixed(0)} percent of the weighted grade`
                : `${summary.totalEarned} of ${totalPoints} points, ${fillPercent.toFixed(0)} percent`
            }
            className="mt-2 h-1.5 overflow-hidden rounded-full bg-border-subtle"
          >
            <div
              className={cn('h-full rounded-full transition-[width] duration-700', hasGrades ? tones.fill : 'bg-accent-primary')}
              style={{ width: `${fillPercent}%` }}
            />
          </div>
        </div>
        {unweightedCount > 0 && (
          <p className="mt-3 text-xs leading-relaxed text-tint-amber">
            {unweightedCount === 1
              ? "1 assessment has no weight, so it doesn't count toward the grade. Add its weight from the syllabus."
              : `${unweightedCount} assessments have no weight, so they don't count toward the grade. Add their weights from the syllabus.`}
          </p>
        )}
      </section>

      {summary.hasFailedMustPass && (
        <div className="flex items-start gap-3 rounded-3xl border border-tint-red bg-tint-red p-4 text-tint-red">
          <RiShieldCrossLine className="mt-0.5 h-5 w-5 shrink-0" aria-hidden="true" />
          <div className="space-y-1 text-xs">
            <p className="font-bold">Must-pass requirement missed</p>
            <p className="leading-relaxed text-secondary">
              Below the minimum on{' '}
              {summary.failedMustPassItems.map((item, i) => (
                <span key={item.id} className="font-semibold text-tint-red">
                  {i > 0 ? ', ' : ''}
                  {item.name} ({item.earnedPoints} / {item.maxPoints} pts, needs {item.minPassPoints})
                </span>
              ))}
              . The course counts as Grade 1 until it is improved or retaken.
            </p>
          </div>
        </div>
      )}

      <section className="space-y-3" aria-labelledby="assessments-title">
        <SheetSectionTitle
          id="assessments-title"
          action={
            <Button
              type="button"
              variant="outline"
              onClick={openAdd}
              className="h-9 gap-1.5 rounded-full px-3.5 text-[13px] font-bold"
            >
              <RiAddLine className="h-3.5 w-3.5" aria-hidden="true" />
              Add
            </Button>
          }
        >
          Assessments
          {hasGrades && <span className="ml-2 text-sm font-semibold tabular-nums text-muted">{assessments.length}</span>}
        </SheetSectionTitle>

        {!hasGrades ? (
          <div className="rounded-3xl card-glass card-depth p-8 text-center">
            <RiAwardLine className="mx-auto h-8 w-8 text-muted" aria-hidden="true" />
            <p className="mt-3 text-base font-extrabold tracking-tight text-primary">Nothing logged yet</p>
            <p className="mx-auto mt-1 max-w-xs text-sm text-secondary">
              Add the midterm, homework or project once you know its points.
            </p>
          </div>
        ) : (
          <ul className="space-y-3">
            {assessments.map((item) => (
              <AssessmentCard
                key={item.id}
                item={item}
                gradingScale={gradingScale}
                weighted={weighted}
                onUpdate={(patch) => onUpdateAssessment(item.id, patch)}
                onEdit={() => openEdit(item)}
              />
            ))}
          </ul>
        )}
      </section>

      <AddAssessmentDialog
        open={showAddModal}
        onOpenChange={setShowAddModal}
        onExited={() => setEditingItem(null)}
        item={editingItem}
        onAddAssessment={(next) => (editingItem ? onUpdateAssessment(editingItem.id, next) : onAddAssessment(next))}
        onDeleteAssessment={
          editingItem
            ? () => {
                removeAssessment(editingItem);
                setShowAddModal(false);
              }
            : undefined
        }
        useWeightedCategories={tracker.points.useWeightedCategories}
        courseName={tracker.courseName}
        projectedPercentage={summary.overallPercentage}
        estimatedGrade={assessments.length > 0 ? summary.estimatedGrade : undefined}
        gradingScale={tracker.points.gradingScale}
        existingWeightTotal={weightTotal}
      />
    </div>
  );
}
