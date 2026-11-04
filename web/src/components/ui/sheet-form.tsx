import { useEffect, useState, type ReactNode } from 'react';
import { RiLoader4Line } from '@remixicon/react';
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { cn } from '@/lib/utils';

/**
 * Form furniture for the tracker's add sheets: grouped lists of fields in the
 * settings idiom, so a form reads like the rest of the sheet rather than a
 * stack of floating inputs.
 */

/** One grouped card of fields, separated by hairlines. */
export function FormGroup({ children, className }: { children: ReactNode; className?: string }) {
  return <div className={cn('rounded-3xl card-glass divide-y divide-subtle', className)}>{children}</div>;
}

interface FormFieldProps {
  label: ReactNode;
  htmlFor?: string;
  /** Use when the control is a group, not a single input. */
  labelId?: string;
  hint?: ReactNode;
  error?: string | null;
  errorId?: string;
  children: ReactNode;
}

/** A stacked label + control row inside a FormGroup. */
export function FormField({ label, htmlFor, labelId, hint, error, errorId, children }: FormFieldProps) {
  const LabelTag = htmlFor ? 'label' : 'p';
  return (
    <div className="space-y-2 px-4 pb-4 pt-3.5">
      <LabelTag id={labelId} htmlFor={htmlFor} className="block text-xs font-semibold text-secondary">
        {label}
      </LabelTag>
      {children}
      {error ? (
        <p id={errorId} role="alert" className="text-xs font-semibold text-tint-red">
          {error}
        </p>
      ) : hint ? (
        <p className="text-xs text-muted">{hint}</p>
      ) : null}
    </div>
  );
}

interface SwitchRowProps {
  id?: string;
  label: ReactNode;
  description?: ReactNode;
  control: ReactNode;
}

/** A single-line row: text on the left, a switch or value on the right. */
export function SwitchRow({ id, label, description, control }: SwitchRowProps) {
  return (
    <div className="flex items-center justify-between gap-4 px-4 py-3.5">
      <div className="min-w-0">
        <p id={id} className="text-sm font-semibold text-primary">
          {label}
        </p>
        {description && <p className="mt-0.5 text-xs text-muted">{description}</p>}
      </div>
      <div className="shrink-0">{control}</div>
    </div>
  );
}

export interface PillOption<T extends string> {
  value: T;
  label: ReactNode;
}

interface PillRadioProps<T extends string> {
  value: T;
  onChange: (value: T) => void;
  options: PillOption<T>[];
  labelledBy?: string;
  columns?: 2 | 3 | 4 | 7;
}

const COLUMN_CLASS: Record<2 | 3 | 4 | 7, string> = {
  2: 'grid-cols-2',
  3: 'grid-cols-3',
  4: 'grid-cols-4',
  7: 'grid-cols-7',
};

/**
 * Segmented choice built on the shared Tabs pill: the full-round well, the
 * spring-gliding accent pill and its glow, so a form segment is the same
 * control as the sheet's tab bar. Options wrap onto rows when there are more
 * than fit a row.
 */
export function PillRadio<T extends string>({ value, onChange, options, labelledBy, columns = 2 }: PillRadioProps<T>) {
  return (
    <Tabs value={value} onValueChange={(next) => onChange(next as T)}>
      <TabsList
        aria-labelledby={labelledBy}
        className={cn(
          'grid h-auto w-full gap-0.5',
          COLUMN_CLASS[columns],
          // A multi-row well rounds concentrically with its 36px pills (18px
          // + 4px padding) instead of a stadium that curves into the corner
          // options.
          options.length > columns && 'rounded-[22px]'
        )}
      >
        {options.map((option) => (
          <TabsTrigger key={option.value} value={option.value} className={cn('h-9 px-2', columns === 7 ? 'text-xs' : 'text-sm')}>
            {option.label}
          </TabsTrigger>
        ))}
      </TabsList>
    </Tabs>
  );
}

interface DeleteRowProps {
  label: string;
  confirmLabel?: string;
  onConfirm: () => void | Promise<void>;
  disabled?: boolean;
  busy?: boolean;
}

/**
 * A quiet destructive row at the end of a sheet: the first tap arms it, the
 * second deletes, and it disarms itself after a moment so a stray tap never
 * removes anything.
 */
export function DeleteRow({ label, confirmLabel = 'Tap again to delete', onConfirm, disabled, busy }: DeleteRowProps) {
  const [armed, setArmed] = useState(false);
  useEffect(() => {
    if (!armed) return;
    const timer = setTimeout(() => setArmed(false), 4000);
    return () => clearTimeout(timer);
  }, [armed]);
  return (
    <button
      type="button"
      disabled={disabled || busy}
      onClick={() => {
        if (!armed) {
          setArmed(true);
          return;
        }
        void onConfirm();
      }}
      className={cn(
        'flex h-12 w-full items-center justify-center gap-2 rounded-3xl border text-sm font-bold transition-colors cursor-pointer active:scale-[0.98] disabled:cursor-not-allowed disabled:opacity-50 focus-visible:outline focus-visible:outline-2 focus-visible:outline-accent-primary',
        armed
          ? 'border-rose-500/50 bg-rose-500/15 text-tint-red'
          : 'border-subtle bg-surface-overlay text-tint-red hover:bg-rose-500/10'
      )}
    >
      {busy && <RiLoader4Line className="h-4 w-4 animate-spin" aria-hidden="true" />}
      {armed ? confirmLabel : label}
    </button>
  );
}

/** The sheet-sized text field: taller on phones so the keyboard target is honest. */
export const SHEET_INPUT_CLASS = 'h-11 text-base sm:text-sm';
