import { type ReactNode } from 'react';
import { type RemixiconComponentType } from '@remixicon/react';
import { cn } from '@/lib/utils';
import { PostageStamp } from '@/components/ui/postage-stamp';

interface SettingsSectionProps {
  /** Short group name, printed on the postage stamp. */
  title: string;
  /** Stamp tilt; neighbouring groups alternate so the page doesn't look stamped by a machine. */
  tilt?: 'left' | 'right';
  id?: string;
  className?: string;
  children: ReactNode;
}

/** A settings group: one glass panel of rows separated by hairlines, titled
 *  with a postage stamp stuck over its top edge (the transit page's titles). */
export function SettingsSection({ title, tilt = 'left', id, className, children }: SettingsSectionProps) {
  return (
    <section id={id} className={cn('relative scroll-mt-24 pt-5', className)}>
      <PostageStamp
        className={cn('absolute left-4 top-0 z-10', tilt === 'left' ? '-rotate-3' : 'rotate-2')}
        innerClassName="px-2.5 py-1"
      >
        <h2 className="text-sm font-extrabold leading-none tracking-tight text-primary">{title}</h2>
      </PostageStamp>
      <div className="card-glass overflow-hidden rounded-3xl pt-4">
        <div className="divide-y divide-subtle">{children}</div>
      </div>
    </section>
  );
}

/** Padding for a non-row block inside a section (pickers, inline forms). */
export const SETTINGS_BLOCK_CLASS = 'px-4 py-3.5';

interface SettingsRowProps {
  icon: RemixiconComponentType;
  /** Tint for the small icon chip, e.g. 'bg-tint-blue text-tint-blue'. */
  iconClass: string;
  title: ReactNode;
  subtitle?: ReactNode;
  control?: ReactNode;
  /** Red title for destructive rows. */
  danger?: boolean;
  className?: string;
}

/** One settings row: small tinted icon, title/subtitle, right-side control. */
export function SettingsRow({ icon: Icon, iconClass, title, subtitle, control, danger = false, className }: SettingsRowProps) {
  return (
    <div className={cn('flex min-h-14 items-center justify-between gap-3 px-4 py-3', className)}>
      <div className="flex min-w-0 flex-1 items-center gap-3">
        <div className={cn('flex h-7 w-7 shrink-0 items-center justify-center rounded-lg', iconClass)}>
          <Icon className="h-3.5 w-3.5" />
        </div>
        <div className="min-w-0 flex-1">
          <p className={cn('text-sm font-semibold leading-tight', danger ? 'text-tint-red' : 'text-primary')}>{title}</p>
          {subtitle && <div className="mt-0.5 text-xs text-secondary">{subtitle}</div>}
        </div>
      </div>
      {control}
    </div>
  );
}
