import * as React from 'react';
import { cn } from '@/lib/utils';
import { type RemixiconComponentType } from '@remixicon/react';

export interface InputProps extends React.InputHTMLAttributes<HTMLInputElement> {
  icon?: RemixiconComponentType;
  rightElement?: React.ReactNode;
}

export const Input = React.forwardRef<HTMLInputElement, InputProps>(
  ({ className, type, icon: Icon, rightElement, ...props }, ref) => {
    return (
      <div className="relative w-full">
        <input
          type={type}
          className={cn(
            // No `flex` on the element itself: form controls are replaced
            // elements, and Firefox honors display:flex on them, turning the
            // value into a left-aligned flex item that ignores text-align.
            'peer h-10 w-full rounded-xl card-glass px-3 py-2 text-sm text-primary shadow-xs placeholder:text-muted focus:outline-none focus:border-accent-primary focus:ring-1 focus:ring-accent-primary focus:shadow-[0_0_16px_color-mix(in_srgb,var(--accent-primary)_20%,transparent)] disabled:cursor-not-allowed disabled:opacity-50 transition-[border-color,box-shadow]',
            Icon && 'pl-9',
            rightElement && 'pr-10',
            className
          )}
          ref={ref}
          {...props}
        />
        {Icon && (
          <Icon className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-muted pointer-events-none transition-colors peer-focus:text-accent-primary z-10" />
        )}
        {rightElement && (
          <div className="absolute right-2 top-1/2 -translate-y-1/2 flex items-center z-10">
            {rightElement}
          </div>
        )}
      </div>
    );
  }
);
Input.displayName = 'Input';
