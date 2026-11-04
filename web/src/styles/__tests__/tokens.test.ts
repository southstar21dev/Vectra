import { describe, it, expect } from 'vitest';
import fs from 'node:fs';
import path from 'node:path';

describe('VisionOS Luminous Glass CSS Tokens & Utilities', () => {
  const cssPath = path.resolve(__dirname, '../globals.css');
  const cssContent = fs.readFileSync(cssPath, 'utf-8');

  it('defines VisionOS obsidian canvas and glass tokens in dark theme', () => {
    expect(cssContent).toContain('--surface-primary: #07080a');
    expect(cssContent).toContain('--glass-bg:');
    expect(cssContent).toContain('--glass-bg-elevated:');
    expect(cssContent).toContain('--glass-border:');
    expect(cssContent).toContain('--glass-border-specular:');
    expect(cssContent).toContain('--glass-shadow:');
  });

  it('defines readable, elevated glass tokens in light theme', () => {
    expect(cssContent).toContain('[data-theme=\'light\']');
    // Pin the light-only values inside the token block (the dark block defines
    // some of the same token names, so a whole-file search would not prove
    // which theme they came from).
    const lightBlock = cssContent.match(/\[data-theme='light'\] \{([\s\S]*?)\n\}/)?.[1] ?? '';
    expect(lightBlock).toContain('--glass-bg: rgba(255, 255, 255, 0.95)');
    expect(lightBlock).toContain('--glass-bg-elevated: rgba(255, 255, 255, 0.98)');
    expect(lightBlock).toContain('--text-secondary: #334155');
    expect(lightBlock).toContain('--text-muted: #64748b');
  });

  it('defines essential glassmorphic utility classes', () => {
    expect(cssContent).toContain('.card-glass');
    expect(cssContent).toContain('.card-glass-interactive');
    expect(cssContent).toContain('.glass-pill');
    expect(cssContent).toContain('.glow-halo');
  });
});
