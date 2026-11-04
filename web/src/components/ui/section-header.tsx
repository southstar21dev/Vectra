import { cn } from '@/lib/utils';

export interface SectionHeaderProps {
  /** Optional icon container displayed to the left of the title */
  icon?: React.ReactNode;
  /** Main title */
  title: React.ReactNode;
  /** Subtitle / metadata description */
  description?: React.ReactNode;
  /** Action buttons / controls on the right */
  actions?: React.ReactNode;
  className?: string;
}

/**
 * Standardized section header with title, subtitle, optional icon, and optional right-aligned actions.
 */
export function SectionHeader({
  icon,
  title,
  description,
  actions,
  className,
}: SectionHeaderProps) {
  return (
    <div className={cn('flex items-center justify-between gap-3 sm:gap-4', className)}>
      <div className="flex items-center gap-2.5 sm:gap-3 min-w-0">
        {icon && <div className="shrink-0">{icon}</div>}
        <div className="min-w-0">
          <div className="flex items-center gap-2 flex-wrap sm:flex-nowrap">
            {typeof title === 'string' ? (
              <h2 className="text-lg font-black text-primary tracking-tight leading-none truncate">
                {title}
              </h2>
            ) : (
              title
            )}
          </div>
          {description && (
            <div className="text-[11px] sm:text-xs text-secondary pt-1 font-medium">
              {description}
            </div>
          )}

        </div>
      </div>
      {actions && (
        <div className="flex items-center gap-2 shrink-0">
          {actions}
        </div>
      )}
    </div>
  );
}
