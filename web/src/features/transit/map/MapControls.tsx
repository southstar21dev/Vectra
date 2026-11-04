import { type RemixiconComponentType } from '@remixicon/react';
import { cn } from '@/lib/utils';

export interface MapControlButton {
  id: string;
  title: string;
  icon: RemixiconComponentType;
  onClick: () => void;
  active?: boolean;
}

export interface MapControlsProps {
  buttons: MapControlButton[];
  className?: string;
}

/** Shared map control column (top-right). */
export function MapControls({ buttons, className }: MapControlsProps) {
  return (
    <div className={cn('absolute top-2 right-2 z-[1000] flex flex-col gap-1.5', className)}>
      {buttons.map(({ id, title, icon: Icon, onClick, active }) => (
        <button
          key={id}
          type="button"
          title={title}
          onClick={onClick}
          className={cn(
            'w-8 h-8 rounded-full flex items-center justify-center border shadow-md transition-colors cursor-pointer',
            active
              ? 'bg-blue-100 dark:bg-blue-950 border-blue-500 text-blue-600 dark:text-blue-400 hover:bg-blue-200 dark:hover:bg-blue-900'
              : 'bg-white dark:bg-zinc-800 border-zinc-200 dark:border-zinc-700 text-zinc-700 dark:text-zinc-200 hover:bg-zinc-100 dark:hover:bg-zinc-700 hover:text-zinc-900 dark:hover:text-white'
          )}
        >
          <Icon className="w-4 h-4" />
        </button>
      ))}
    </div>
  );
}
