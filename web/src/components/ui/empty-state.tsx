import { motion } from 'framer-motion';
import { cn } from '@/lib/utils';
import { REVEAL_TRANSITION } from './reveal-motion';

export interface EmptyStateProps {
  /** Emoji to display in the floating icon container */
  emoji?: string;
  /** Title text */
  title: string;
  /** Description text */
  description?: string;
  /** Action buttons to render below the text */
  actions?: React.ReactNode;
  /** Show a loading spinner instead of the CTA */
  loading?: boolean;
  /** Loading text */
  loadingText?: string;
  /** Optional className override */
  className?: string;
}

/**
 * Animated empty state card used when a view has no data.
 * Replaces custom empty states across features.
 */
export function EmptyState({
  emoji = '📋',
  title,
  description,
  actions,
  loading = false,
  loadingText = 'loading…',
  className,
}: EmptyStateProps) {
  return (
    <motion.div
      initial={{ opacity: 0, transform: 'scale(0.95)' }}
      animate={{ opacity: 1, transform: 'scale(1)' }}
      transition={REVEAL_TRANSITION}
      className={cn(
        'relative mt-4 sm:mt-6 rounded-[2rem] bg-gradient-to-b from-surface-tertiary to-surface-secondary border border-subtle text-center py-12 sm:py-16 px-6 flex flex-col items-center justify-center gap-5 shadow-[0_0_40px_rgba(0,0,0,0.2)] overflow-hidden',
        className
      )}
    >
      <div
        className="absolute inset-0 rounded-full bg-[radial-gradient(closest-side,rgba(59,130,246,0.16),transparent_72%)]"
        style={{ pointerEvents: 'none' }}
      />
      <motion.div
        animate={{ y: [0, -6, 0] }}
        transition={{ repeat: Infinity, duration: 4, ease: 'easeInOut' }}
        className="relative z-10 w-20 h-20 mx-auto rounded-3xl bg-gradient-to-br from-blue-500/20 to-purple-500/10 border border-subtle flex items-center justify-center text-4xl shadow-[0_0_30px_rgba(59,130,246,0.15)]"
      >
        {emoji}
      </motion.div>
      <div className="relative z-10 space-y-6">
        <h3 className="text-xl sm:text-2xl font-black bg-clip-text bg-gradient-to-br from-text-primary via-text-secondary to-tint-blue-text">
          {title}
        </h3>
        {description && (
          <p className="text-xs sm:text-sm text-secondary leading-relaxed font-medium max-w-md mx-auto">
            {description}
          </p>
        )}
        {loading ? (
          <p className="relative z-10 text-[10px] text-muted flex items-center justify-center gap-1.5">
            <span className="w-3 h-3 rounded-full border border-subtle border-t-transparent animate-spin" />
            {loadingText}
          </p>
        ) : (
          actions && (
            <div className="relative z-10 flex flex-col sm:flex-row items-center justify-center gap-3">
              {actions}
            </div>
          )
        )}
      </div>
    </motion.div>
  );
}
