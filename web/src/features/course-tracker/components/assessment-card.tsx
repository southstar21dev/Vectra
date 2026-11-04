import { useId, useState } from 'react';
import { RiPencilLine } from '@remixicon/react';
import { Button } from '@/components/ui/button';
import { StatusLabel } from '@/components/ui/status-label';
import { cn } from '@/lib/utils';
import { clampNumber, normalizeDecimalInput, parseFiniteNumber } from '../numeric-input';
import type { AssessmentItem, GradingScaleTier } from '../tracker-types';
import { DateTile } from './date-tile';
import { GRADE_TONES, gradeForPercent } from '../grades';

const formatScore = (value: number) => String(Math.round(value * 100) / 100);

interface ScoreFieldProps {
  id?: string;
  value: number;
  min: number;
  max: number;
  ariaLabel: string;
  onCommit: (value: number) => void;
}

/**
 * A score input that holds its own draft while focused, so "18." or a cleared
 * field can be typed through; every parseable draft is committed as it is
 * typed, and the stored value comes back on blur.
 */
function ScoreField({ id, value, min, max, ariaLabel, onCommit }: ScoreFieldProps) {
  const [draft, setDraft] = useState<string | null>(null);
  return (
    <input
      id={id}
      type="text"
      inputMode="decimal"
      aria-label={ariaLabel}
      value={draft ?? formatScore(value)}
      onFocus={(e) => {
        setDraft(formatScore(value));
        e.currentTarget.select();
      }}
      onChange={(e) => {
        const next = normalizeDecimalInput(e.target.value).slice(0, 7);
        setDraft(next);
        const parsed = parseFiniteNumber(next);
        if (parsed !== null) onCommit(clampNumber(parsed, min, max));
      }}
      onBlur={() => setDraft(null)}
      onKeyDown={(e) => {
        if (e.key === 'Enter') e.currentTarget.blur();
      }}
      className="h-10 w-16 rounded-[14px] border border-subtle bg-surface-secondary text-center text-[15px] font-black tabular-nums text-primary focus:border-accent-primary focus:outline-none focus:ring-1 focus:ring-accent-primary"
    />
  );
}

interface AssessmentCardProps {
  item: AssessmentItem;
  gradingScale: GradingScaleTier[];
  /** Weighted courses show each assessment's share of the grade. */
  weighted: boolean;
  onUpdate: (patch: Partial<AssessmentItem>) => void;
  /** Opens the full edit sheet (name, date, weight, rules, delete). */
  onEdit: () => void;
}

/**
 * One assessment: its date as a tile, what it is worth, and the score in the
 * grade hue it earns over a meter. Tapping the score (or "Add score" while it
 * is ungraded) opens a quick score editor underneath; the pencil opens the
 * full edit sheet.
 */
export function AssessmentCard({ item, gradingScale, weighted, onUpdate, onEdit }: AssessmentCardProps) {
  const ids = useId();
  const [editing, setEditing] = useState(false);
  const isCompleted = item.isCompleted ?? item.earnedPoints > 0;
  const percent = (item.earnedPoints / (item.maxPoints || 1)) * 100;
  const tones = GRADE_TONES[gradeForPercent(percent, gradingScale)];

  const details = [
    `${formatScore(item.maxPoints)} pts`,
    weighted && typeof item.weightPercent === 'number' ? `${item.weightPercent}% of grade` : null,
    isCompleted ? null : 'not graded yet',
  ]
    .filter(Boolean)
    .join(' · ');

  return (
    <li className="rounded-3xl card-glass card-depth p-4">
      <div className="flex items-center gap-3">
        <DateTile date={item.date} />
        <div className="min-w-0 flex-1">
          <p className="flex min-w-0 items-center gap-2">
            <span className="truncate text-sm font-bold text-primary">{item.name}</span>
            {item.isMustPass && <StatusLabel tone="red">Must-pass</StatusLabel>}
          </p>
          <p className="mt-0.5 truncate text-xs text-secondary">{details}</p>
          {item.notes && <p className="mt-0.5 truncate text-xs text-muted">{item.notes}</p>}
        </div>
        {isCompleted ? (
          <button
            type="button"
            onClick={() => setEditing((open) => !open)}
            aria-expanded={editing}
            aria-controls={`${ids}-editor`}
            aria-label={`${item.name}: ${formatScore(item.earnedPoints)} of ${formatScore(item.maxPoints)} points. Edit score`}
            className="-m-1.5 shrink-0 whitespace-nowrap rounded-xl p-1.5 text-right transition-colors cursor-pointer hover:bg-surface-overlay focus-visible:outline focus-visible:outline-2 focus-visible:outline-accent-primary"
          >
            <span className={cn('text-xl font-black tabular-nums', tones.text)}>{formatScore(item.earnedPoints)}</span>
            <span className="text-[13px] font-bold tabular-nums text-secondary"> / {formatScore(item.maxPoints)}</span>
          </button>
        ) : (
          !editing && (
            <Button
              type="button"
              size="sm"
              onClick={() => setEditing(true)}
              aria-controls={`${ids}-editor`}
              className="h-9 shrink-0 rounded-[20px] px-3.5 text-xs font-extrabold"
            >
              Add score
            </Button>
          )
        )}
        <button
          type="button"
          onClick={onEdit}
          aria-label={`Edit ${item.name}`}
          className="-mr-1.5 inline-flex h-9 w-9 shrink-0 items-center justify-center rounded-full text-secondary transition-colors cursor-pointer hover:bg-surface-overlay hover:text-primary active:scale-95 focus-visible:outline focus-visible:outline-2 focus-visible:outline-accent-primary"
        >
          <RiPencilLine className="h-4 w-4" aria-hidden="true" />
        </button>
      </div>

      {isCompleted && !editing && (
        <div className="mt-3 flex items-center gap-2.5" aria-hidden="true">
          <div className="h-1.5 min-w-0 flex-1 overflow-hidden rounded-full bg-border-primary">
            <div
              className={cn('h-full rounded-full transition-[width] duration-500', tones.fill)}
              style={{ width: `${Math.max(0, Math.min(100, percent))}%` }}
            />
          </div>
          <span className="text-xs font-bold tabular-nums text-secondary">{Math.round(percent)}%</span>
        </div>
      )}

      {editing && (
        <div id={`${ids}-editor`} className="mt-3 space-y-3 border-t border-subtle pt-3 animate-fade-in">
          <div className="flex items-center gap-2">
            <label htmlFor={`${ids}-earned`} className="flex-1 text-xs font-semibold text-secondary">
              Score
            </label>
            <ScoreField
              id={`${ids}-earned`}
              value={item.earnedPoints}
              min={0}
              max={10000}
              ariaLabel={`${item.name} points earned`}
              onCommit={(earnedPoints) => onUpdate({ earnedPoints, isCompleted: true })}
            />
            <span className="text-[13px] font-semibold text-secondary">/</span>
            <ScoreField
              value={item.maxPoints}
              min={0.1}
              max={10000}
              ariaLabel={`${item.name} maximum points`}
              onCommit={(maxPoints) => onUpdate({ maxPoints })}
            />
            <span className="text-[13px] font-semibold text-secondary">pts</span>
          </div>
          {item.earnedPoints > item.maxPoints && (
            // Allowed — bonus points exist — but usually a typo.
            <p className="text-xs text-tint-amber">
              More than the {formatScore(item.maxPoints)} pt maximum. Fine for bonus points, otherwise check for a typo.
            </p>
          )}
          <div className="flex items-center justify-between gap-2">
            <button
              type="button"
              onClick={() => {
                setEditing(false);
                onEdit();
              }}
              className="-ml-2 inline-flex h-9 items-center gap-1.5 rounded-full px-3 text-xs font-bold text-secondary transition-colors cursor-pointer hover:bg-surface-overlay hover:text-primary active:scale-95 focus-visible:outline focus-visible:outline-2 focus-visible:outline-accent-primary"
            >
              <RiPencilLine className="h-3.5 w-3.5" aria-hidden="true" />
              Edit details
            </button>
            <div className="flex items-center gap-2">
              {isCompleted && (
                <Button
                  type="button"
                  variant="ghost"
                  size="sm"
                  onClick={() => {
                    onUpdate({ isCompleted: false });
                    setEditing(false);
                  }}
                  className="h-9 rounded-full px-3"
                >
                  Not graded yet
                </Button>
              )}
              <Button type="button" size="sm" onClick={() => setEditing(false)} className="h-9 rounded-[20px] px-4">
                Done
              </Button>
            </div>
          </div>
        </div>
      )}
    </li>
  );
}
