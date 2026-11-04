import { RiCheckFill, RiTimeFill, RiComputerFill, RiMoonFill, RiSunFill } from '@remixicon/react';
import { motion } from 'framer-motion';
import { useAppearance } from '@/hooks/use-appearance';
import {
  ACCENT_PRESETS,
  BACKGROUND_STYLES,
  THEME_MODES,
  TIME_FORMATS,
  type ThemeMode,
} from '@/features/settings/appearance-prefs';
import { SETTINGS_BLOCK_CLASS, SettingsSection } from './settings-section';
import { cn } from '@/lib/utils';

const MODE_ICONS: Record<ThemeMode, typeof RiSunFill> = {
  light: RiSunFill,
  dark: RiMoonFill,
  system: RiComputerFill,
};

const SECTION_LABEL =
  'text-[11px] font-semibold text-muted mb-2';

/** Appearance card — theme mode, accent preset, background style. Every control
 *  applies instantly (optimistic local update + profile sync). */
export function AppearanceSettings() {
  const { prefs, setAppearance } = useAppearance();

  return (
    <SettingsSection title="Look" tilt="left">
      <div className={cn(SETTINGS_BLOCK_CLASS, 'space-y-5')}>
        {/* MODE */}
        <div>
          <p className={SECTION_LABEL}>Mode</p>
          <div className="card-glass flex gap-1 rounded-full p-1">
            {THEME_MODES.map((mode) => {
              const Icon = MODE_ICONS[mode];
              const active = prefs.theme === mode;
              return (
                <button
                  key={mode}
                  type="button"
                  onClick={() => setAppearance({ theme: mode })}
                  aria-pressed={active}
                  aria-label={`${mode} mode`}
                  className={cn(
                    'relative flex h-9 flex-1 cursor-pointer items-center justify-center rounded-full transition-colors duration-200 focus:outline-none focus-visible:outline-none active:scale-[0.98]',
                    active
                      ? 'text-white font-bold'
                      : 'text-secondary hover:text-primary hover:bg-surface-overlay/50'
                  )}
                >
                  {active && (
                    <motion.div
                      layoutId="settings-theme-mode-pill"
                      transition={{ type: 'spring', stiffness: 450, damping: 35 }}
                      className="absolute inset-0 rounded-full bg-accent-primary border border-accent-primary/50 nav-pill-glow pointer-events-none"
                    >
                      <div className="absolute top-0 inset-x-2 h-[1px] bg-gradient-to-r from-transparent via-white/50 to-transparent pointer-events-none" />
                    </motion.div>
                  )}
                  <Icon className="relative z-10 h-4 w-4" />
                </button>
              );
            })}
          </div>
        </div>

        {/* ACCENT */}
        <div>
          <p className={SECTION_LABEL}>Accent</p>
          <div className="flex items-center justify-between gap-1 sm:gap-2.5">
            {ACCENT_PRESETS.map((preset) => {
              const active = prefs.accent === preset.key;
              return (
                <button
                  key={preset.key}
                  type="button"
                  onClick={() => setAppearance({ accent: preset.key })}
                  aria-pressed={active}
                  aria-label={preset.name}
                  className="relative h-8 w-8 cursor-pointer rounded-full transition-transform active:scale-95 shadow-sm shrink-0"
                  style={{ backgroundColor: `var(--accent-${preset.key})` }}
                >
                  <span
                    className={cn(
                      'absolute -inset-[3px] rounded-full border-2 transition-colors',
                      active ? 'border-accent-primary shadow-[0_0_12px_color-mix(in_srgb,var(--accent-primary)_40%,transparent)]' : 'border-transparent'
                    )}
                  />
                  {active && (
                    <RiCheckFill className="absolute inset-0 m-auto h-4 w-4 text-white drop-shadow-xs" />
                  )}
                </button>
              );
            })}
          </div>
        </div>

        {/* BACKGROUND */}
        <div>
          <p className={SECTION_LABEL}>Background</p>
          <div className="flex gap-2.5">
            {BACKGROUND_STYLES.map((style) => {
              const active = prefs.background === style.key;
              return (
                <button
                  key={style.key}
                  type="button"
                  onClick={() => setAppearance({ background: style.key })}
                  aria-pressed={active}
                  aria-label={style.name}
                  className={cn(
                    'h-11 flex-1 cursor-pointer overflow-hidden rounded-2xl border border-subtle transition-[color,background-color,border-color,box-shadow,transform,opacity] active:scale-95 shadow-xs',
                    active ? 'border-accent-primary ring-2 ring-accent-primary/40 shadow-sm' : 'hover:border-primary'
                  )}
                >
                  <span className={cn('block h-full w-full', `bg-preview-${style.key}`)} />
                </button>
              );
            })}
          </div>
        </div>

        {/* TIME FORMAT */}
        <div>
          <p className={SECTION_LABEL}>Time Format</p>
          <div className="card-glass flex gap-1 rounded-full p-1">
            {TIME_FORMATS.map((fmt) => {
              const active = prefs.timeFormat === fmt;
              const label = fmt === '24h' ? '24-hour' : '12-hour';
              return (
                <button
                  key={fmt}
                  type="button"
                  onClick={() => setAppearance({ timeFormat: fmt })}
                  aria-pressed={active}
                  aria-label={label}
                  className={cn(
                    'relative flex h-9 flex-1 cursor-pointer items-center justify-center gap-1.5 rounded-full text-xs transition-colors duration-200 focus:outline-none active:scale-[0.98]',
                    active
                      ? 'text-white font-bold'
                      : 'text-secondary hover:text-primary hover:bg-surface-overlay/50'
                  )}
                >
                  {active && (
                    <motion.div
                      layoutId="settings-time-format-pill"
                      transition={{ type: 'spring', stiffness: 450, damping: 35 }}
                      className="absolute inset-0 rounded-full bg-accent-primary border border-accent-primary/50 nav-pill-glow pointer-events-none"
                    >
                      <div className="absolute top-0 inset-x-2 h-[1px] bg-gradient-to-r from-transparent via-white/50 to-transparent pointer-events-none" />
                    </motion.div>
                  )}
                  <RiTimeFill className="relative z-10 h-3.5 w-3.5" />
                  <span className="relative z-10 font-medium">{label}</span>
                </button>
              );
            })}
          </div>
        </div>
      </div>
    </SettingsSection>
  );
}
