import { describe, it, expect, vi } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import { SettingsTab } from './settings-tab';
import { createDefaultTracker, type CourseTracker } from '../tracker-types';

function renderSettings(adjust?: (tracker: CourseTracker) => void, onConfirmSettings: (() => void) | undefined = vi.fn()) {
  const tracker = createDefaultTracker({ courseName: 'Algorithms', courseCode: 'CS201' });
  adjust?.(tracker);

  const onUpdateLimits = vi.fn();
  const html = renderToStaticMarkup(
    <SettingsTab
      tracker={tracker}
      onUpdateLimits={onUpdateLimits}
      onUpdateGradingScale={vi.fn()}
      onConfirmSettings={onConfirmSettings}
    />
  );
  return { html, tracker, onUpdateLimits };
}

describe('SettingsTab', () => {
  it('offers a course total points setting, defaulted to 100', () => {
    const { html } = renderSettings();
    expect(html).toContain('Course total');
    expect(html).toContain('id="total-points"');
    expect(html).toContain('value="100"');
  });

  it('shows the course total saved on the tracker', () => {
    const { html } = renderSettings((t) => {
      t.points.totalPoints = 60;
    });
    expect(html).toContain('value="60"');
  });

  it('asks to check the seeded rules against the syllabus until they are confirmed', () => {
    expect(renderSettings().html).toContain('Check your syllabus');
    expect(renderSettings().html).toContain('They match');
    const confirmed = renderSettings((t) => {
      t.settingsConfirmed = true;
    }).html;
    expect(confirmed).not.toContain('Check your syllabus');
  });

  it('steps the absence limit, and hides the stepper when there is no limit', () => {
    const limited = renderSettings().html;
    expect(limited).toContain('id="max-absences"');
    expect(limited).toContain('aria-label="One fewer absence"');
    expect(limited).toContain('aria-label="One more absence"');
    expect(limited).toContain('aria-checked="true"');

    const unlimited = renderSettings((t) => {
      t.attendance.maxAbsencesAllowed = null;
    }).html;
    expect(unlimited).not.toContain('id="max-absences"');
    expect(unlimited).toContain('aria-checked="false"');
  });

  it('names each grade with the range its cutoff covers', () => {
    const { html } = renderSettings();
    expect(html).toContain('Excellent');
    expect(html).toContain('87–100%');
    expect(html).toContain('75–86%');
    expect(html).toContain('62–74%');
    expect(html).toContain('50–61%');
  });
});
