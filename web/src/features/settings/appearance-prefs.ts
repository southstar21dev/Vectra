export const THEME_MODES = ['light', 'dark', 'system'] as const;
export type ThemeMode = (typeof THEME_MODES)[number];

export const ACCENT_KEYS = [
  'blue',
  'indigo',
  'purple',
  'pink',
  'red',
  'orange',
  'green',
  'teal',
  'cyan',
] as const;
export type AccentKey = (typeof ACCENT_KEYS)[number];

export const BACKGROUND_KEYS = ['mesh', 'flat', 'aurora'] as const;
export type BackgroundKey = (typeof BACKGROUND_KEYS)[number];

export const TIME_FORMATS = ['24h', '12h'] as const;
export type TimeFormat = (typeof TIME_FORMATS)[number];

export interface AppearancePrefs {
  theme: ThemeMode;
  accent: AccentKey;
  background: BackgroundKey;
  timeFormat: TimeFormat;
}

export const DEFAULT_APPEARANCE: AppearancePrefs = {
  theme: 'dark',
  accent: 'purple',
  background: 'aurora',
  timeFormat: '24h',
};

/** Background styles removed from the picker; they migrate to 'aurora'. */
const REMOVED_BACKGROUND_KEYS = ['gradient', 'noise', 'borealis', 'northern'];

/**
 * Parse unknown server/localStorage input; anything invalid → full defaults.
 *
 * Deliberately hand-rolled rather than a zod schema: this runs on the very
 * first paint (via AppearanceManager → useAppearance), so a schema library here
 * would pull its whole runtime into the entry chunk. Semantics match the former
 * `zod` schema — a missing field falls back to its default, while a field that
 * is present but invalid rejects the whole object (full defaults).
 *
 * Styles removed from the picker ('gradient', 'noise', 'borealis', 'northern')
 * migrate to the default background while preserving theme/accent.
 */
export function normalizeAppearance(raw: unknown): AppearancePrefs {
  const migrated =
    raw !== null &&
    typeof raw === 'object' &&
    'background' in raw &&
    REMOVED_BACKGROUND_KEYS.includes((raw as { background: unknown }).background as string)
      ? { ...raw, background: 'aurora' }
      : raw;

  if (migrated === null || typeof migrated !== 'object' || Array.isArray(migrated)) {
    return { ...DEFAULT_APPEARANCE };
  }
  const record = migrated as Record<string, unknown>;
  const theme = record.theme === undefined ? DEFAULT_APPEARANCE.theme : record.theme;
  const accent = record.accent === undefined ? DEFAULT_APPEARANCE.accent : record.accent;
  const background = record.background === undefined ? DEFAULT_APPEARANCE.background : record.background;
  const timeFormat = record.timeFormat === undefined ? DEFAULT_APPEARANCE.timeFormat : record.timeFormat;

  if (
    !(THEME_MODES as readonly unknown[]).includes(theme) ||
    !(ACCENT_KEYS as readonly unknown[]).includes(accent) ||
    !(BACKGROUND_KEYS as readonly unknown[]).includes(background) ||
    !(TIME_FORMATS as readonly unknown[]).includes(timeFormat)
  ) {
    return { ...DEFAULT_APPEARANCE };
  }

  return {
    theme: theme as ThemeMode,
    accent: accent as AccentKey,
    background: background as BackgroundKey,
    timeFormat: timeFormat as TimeFormat,
  };
}

export interface AccentPreset {
  key: AccentKey;
  name: string;
  /** Hex used on dark backgrounds (also the swatch color in dark mode). */
  dark: string;
  /** Deeper hex so accent-colored buttons keep readable contrast on white. */
  light: string;
}

export const ACCENT_PRESETS: AccentPreset[] = [
  { key: 'blue', name: 'Sky Blue', dark: '#38bdf8', light: '#0284c7' },
  { key: 'indigo', name: 'Indigo', dark: '#818cf8', light: '#4f46e5' },
  { key: 'purple', name: 'Violet', dark: '#a855f7', light: '#7c3aed' },
  { key: 'pink', name: 'Rose', dark: '#fb7185', light: '#db2777' },
  { key: 'red', name: 'Coral Red', dark: '#f43f5e', light: '#e11d48' },
  { key: 'orange', name: 'Amber', dark: '#f59e0b', light: '#d97706' },
  { key: 'green', name: 'Emerald', dark: '#10b981', light: '#059669' },
  { key: 'teal', name: 'Teal', dark: '#14b8a6', light: '#0d9488' },
  { key: 'cyan', name: 'Cyan', dark: '#06b6d4', light: '#0891b2' },
];

export const BACKGROUND_STYLES: ReadonlyArray<{ key: BackgroundKey; name: string }> = [
  { key: 'mesh', name: 'Grid' },
  { key: 'flat', name: 'Solid' },
  { key: 'aurora', name: 'Aurora' },
];

/** 'system' resolves against the OS preference; explicit modes pass through. */
export function effectiveTheme(mode: ThemeMode, systemPrefersDark: boolean): 'light' | 'dark' {
  if (mode === 'system') return systemPrefersDark ? 'dark' : 'light';
  return mode;
}
