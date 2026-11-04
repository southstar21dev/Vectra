import { describe, expect, it } from 'vitest';
import {
  normalizeAppearance,
  effectiveTheme,
  ACCENT_PRESETS,
  BACKGROUND_STYLES,
  DEFAULT_APPEARANCE,
  type AppearancePrefs,
} from './appearance-prefs';

const full: AppearancePrefs = { theme: 'light', accent: 'green', background: 'aurora', timeFormat: '12h' };

describe('normalizeAppearance', () => {
  it('accepts a complete valid object unchanged', () => {
    expect(normalizeAppearance(full)).toEqual(full);
  });

  it('fills missing fields with defaults', () => {
    expect(normalizeAppearance({ accent: 'pink' })).toEqual({
      theme: 'dark',
      accent: 'pink',
      background: 'aurora',
      timeFormat: '24h',
    });
  });

  it('falls back to full defaults for invalid values', () => {
    expect(normalizeAppearance({ theme: 'neon' })).toEqual(DEFAULT_APPEARANCE);
    expect(normalizeAppearance({ accent: 'chartreuse' })).toEqual(DEFAULT_APPEARANCE);
    expect(normalizeAppearance({ background: 42 })).toEqual(DEFAULT_APPEARANCE);
    expect(normalizeAppearance({ timeFormat: '48h' })).toEqual(DEFAULT_APPEARANCE);
  });

  it('falls back to full defaults for garbage input', () => {
    expect(normalizeAppearance(null)).toEqual(DEFAULT_APPEARANCE);
    expect(normalizeAppearance('nope')).toEqual(DEFAULT_APPEARANCE);
    expect(normalizeAppearance([])).toEqual(DEFAULT_APPEARANCE);
    expect(normalizeAppearance(undefined)).toEqual(DEFAULT_APPEARANCE);
  });
});

describe('migration of removed background styles', () => {
  it('migrates removed styles to the default, preserving theme and accent', () => {
    expect(normalizeAppearance({ theme: 'light', accent: 'green', background: 'gradient' })).toEqual({
      theme: 'light',
      accent: 'green',
      background: 'aurora',
      timeFormat: '24h',
    });
    expect(normalizeAppearance({ theme: 'system', accent: 'pink', background: 'noise' })).toEqual({
      theme: 'system',
      accent: 'pink',
      background: 'aurora',
      timeFormat: '24h',
    });
    expect(normalizeAppearance({ theme: 'dark', accent: 'teal', background: 'borealis' })).toEqual({
      theme: 'dark',
      accent: 'teal',
      background: 'aurora',
      timeFormat: '24h',
    });
    expect(normalizeAppearance({ theme: 'light', accent: 'orange', background: 'northern' })).toEqual({
      theme: 'light',
      accent: 'orange',
      background: 'aurora',
      timeFormat: '24h',
    });
  });

  it('still accepts every surviving background style', () => {
    for (const key of ['mesh', 'flat', 'aurora'] as const) {
      expect(normalizeAppearance({ theme: 'dark', accent: 'blue', background: key }).background).toBe(key);
    }
  });
});

describe('effectiveTheme', () => {
  it('passes explicit modes through', () => {
    expect(effectiveTheme('dark', true)).toBe('dark');
    expect(effectiveTheme('dark', false)).toBe('dark');
    expect(effectiveTheme('light', true)).toBe('light');
    expect(effectiveTheme('light', false)).toBe('light');
  });

  it('resolves system from the OS preference', () => {
    expect(effectiveTheme('system', true)).toBe('dark');
    expect(effectiveTheme('system', false)).toBe('light');
  });
});

describe('registries', () => {
  it('covers every allowed key exactly once', () => {
    const accentKeys = ACCENT_PRESETS.map((p) => p.key);
    expect(accentKeys.sort()).toEqual([
      'blue',
      'cyan',
      'green',
      'indigo',
      'orange',
      'pink',
      'purple',
      'red',
      'teal',
    ]);
    expect(BACKGROUND_STYLES.map((b) => b.key).sort()).toEqual(['aurora', 'flat', 'mesh']);
    expect(BACKGROUND_STYLES.map((b) => b.name)).toEqual(['Grid', 'Solid', 'Aurora']);
  });

  it('gives every accent preset distinct dark and light hexes', () => {
    for (const p of ACCENT_PRESETS) {
      expect(p.dark).toMatch(/^#[0-9a-f]{6}$/i);
      expect(p.light).toMatch(/^#[0-9a-f]{6}$/i);
      expect(p.light).not.toBe(p.dark);
    }
  });
});
