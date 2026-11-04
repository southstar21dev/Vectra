import React from 'react';
import { RiArrowLeftRightLine } from '@remixicon/react';
import { cn } from '@/lib/utils';
import { splitRouteTitle } from './transit-utils';

interface FormattedRouteTitleProps {
  title?: string | null;
  className?: string;
  iconClassName?: string;
}

/**
 * Formats route titles with two or more termini (separated by '|' in GTFS data)
 * by replacing the raw pipe with an elegant, sleek bidirectional arrow icon.
 */
export function FormattedRouteTitle({
  title,
  className,
  iconClassName = 'w-3.5 h-3.5 text-muted/60 shrink-0 mx-1.5 inline-block align-middle',
}: FormattedRouteTitleProps) {
  if (!title) return null;

  const parts = splitRouteTitle(title);
  if (parts.length <= 1) {
    return <span className={className}>{parts[0] || title}</span>;
  }

  return (
    <span className={cn('inline-flex items-center min-w-0 max-w-full truncate align-middle', className)}>
      {parts.map((part, index) => (
        <React.Fragment key={index}>
          {index > 0 && (
            <RiArrowLeftRightLine className={cn('shrink-0', iconClassName)} aria-hidden="true" />
          )}
          <span className="truncate">{part}</span>
        </React.Fragment>
      ))}
    </span>
  );
}
