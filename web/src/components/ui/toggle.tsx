import { RiLoader4Line } from '@remixicon/react';
import { cn } from '@/lib/utils';

interface ToggleProps {
  checked: boolean;
  onChange: (checked: boolean) => void;
  disabled?: boolean;
  loading?: boolean;
  'aria-label'?: string;
}

/** Accessible switch — same visual language as the push-notifications toggle. */
export function Toggle({ checked, onChange, disabled, loading, 'aria-label': ariaLabel }: ToggleProps) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      aria-label={ariaLabel}
      onClick={() => onChange(!checked)}
      disabled={disabled || loading}
      className={cn(
        'relative w-12 h-6 rounded-full transition-[background-color,box-shadow] duration-200 shrink-0 cursor-pointer disabled:cursor-not-allowed select-none p-0.5 flex items-center',
        checked
          ? 'pill-depth-accent'
          : 'bg-black/12 dark:bg-white/10 border border-black/15 dark:border-white/15 shadow-inner'
      )}
    >
      {loading ? (
        <RiLoader4Line className="w-4 h-4 animate-spin absolute inset-0 m-auto text-white" />
      ) : (
        <div
          className={cn(
            'w-5 h-5 rounded-full bg-white shadow-[0_2px_5px_rgba(0,0,0,0.28)] ring-0 transition-transform duration-200 ease-out',
            checked ? 'translate-x-6' : 'translate-x-0'
          )}
        />
      )}
    </button>
  );
}
