import { describe, it, expect } from 'vitest';
import { nodeText, collectOptions, calculatePopupPosition } from './select-utils';
import React from 'react';

describe('Select option text extraction (nodeText)', () => {
  it('extracts plain string and numbers', () => {
    expect(nodeText('Spring 2026')).toBe('Spring 2026');
    expect(nodeText(2026)).toBe('2026');
    expect(nodeText(null)).toBe('');
    expect(nodeText(undefined)).toBe('');
  });

  it('extracts text from array of children with conditionals', () => {
    // e.g. {sem} {sem === current ? '(CURRENT)' : ''}
    const children = ['2025/2026/2', ' ', '(CURRENT)'];
    expect(nodeText(children)).toBe('2025/2026/2 (CURRENT)');
  });
});

describe('collectOptions', () => {
  it('collects option values and labels properly', () => {
    const element = React.createElement(
      React.Fragment,
      null,
      React.createElement('option', { value: 'all' }, 'All Semesters'),
      React.createElement('option', { value: '2026' }, '2026 Spring')
    );

    const options = collectOptions(element.props.children);
    expect(options).toEqual([
      { value: 'all', label: 'All Semesters' },
      { value: '2026', label: '2026 Spring' },
    ]);
  });

  it('handles empty value option properly', () => {
    const element = React.createElement(
      React.Fragment,
      null,
      React.createElement('option', { value: '' }, 'None / Personal')
    );

    const options = collectOptions(element.props.children);
    expect(options).toEqual([
      { value: '', label: 'None / Personal' },
    ]);
  });
});

describe('calculatePopupPosition (Mobile & Viewport clamping)', () => {
  it('positions below trigger when sufficient bottom space exists', () => {
    const pos = calculatePopupPosition({
      triggerRect: { top: 100, bottom: 136, left: 50, right: 190, width: 140, height: 36 },
      popupRect: { width: 180, height: 120 },
      viewportWidth: 375, // iPhone viewport
      viewportHeight: 667,
      padding: 8,
    });

    expect(pos.top).toBe(142); // 136 + 6
    expect(pos.left).toBe(50);
    expect(pos.minWidth).toBe(140);
    expect(pos.maxHeight).toBeGreaterThan(120);
  });

  it('clamps left position so it never overflows off the right edge on mobile', () => {
    // Trigger is near right edge: left=250, width=110, popupWidth=180 on 375px screen
    // Without clamping: left = 250 -> 250 + 180 = 430px (overflows 375px screen by 55px!)
    const pos = calculatePopupPosition({
      triggerRect: { top: 100, bottom: 136, left: 250, right: 360, width: 110, height: 36 },
      popupRect: { width: 180, height: 120 },
      viewportWidth: 375,
      viewportHeight: 667,
      padding: 8,
    });

    // Max left should be 375 - 180 - 8 = 187
    expect(pos.left).toBe(187);
    expect(pos.left + 180).toBeLessThanOrEqual(375 - 8);
  });

  it('clamps left position so it never overflows off the left edge on mobile', () => {
    const pos = calculatePopupPosition({
      triggerRect: { top: 100, bottom: 136, left: -20, right: 100, width: 120, height: 36 },
      popupRect: { width: 150, height: 100 },
      viewportWidth: 375,
      viewportHeight: 667,
      padding: 8,
    });

    expect(pos.left).toBe(8);
  });

  it('flips to open above when near the bottom of viewport', () => {
    // Trigger is at bottom=620 on a 667px viewport (only 47px below, but 584px above)
    const pos = calculatePopupPosition({
      triggerRect: { top: 584, bottom: 620, left: 50, right: 190, width: 140, height: 36 },
      popupRect: { width: 180, height: 200 },
      viewportWidth: 375,
      viewportHeight: 667,
      padding: 8,
    });

    // Should open above: 584 - 200 - 6 = 378
    expect(pos.top).toBe(378);
    expect(pos.openAbove).toBe(true);
  });
});
