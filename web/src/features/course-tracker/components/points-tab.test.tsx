import { describe, it, expect, vi } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import { PointsTab } from './points-tab';
import { createDefaultTracker, type AssessmentItem, type CourseTracker } from '../tracker-types';

function render(tracker: CourseTracker) {
  return renderToStaticMarkup(
    <PointsTab
      tracker={tracker}
      onAddAssessment={vi.fn()}
      onUpdateAssessment={vi.fn()}
      onRemoveAssessment={vi.fn()}
    />
  );
}

function renderTab(assessments: AssessmentItem[], totalPoints?: number) {
  const tracker = createDefaultTracker({ courseName: 'Algorithms', courseCode: 'CS201' });
  tracker.points.assessments = assessments;
  if (totalPoints !== undefined) tracker.points.totalPoints = totalPoints;
  return { html: render(tracker), tracker };
}

describe('PointsTab', () => {
  it('reads points earned against the course total, not the logged assessments', () => {
    const { html } = renderTab([
      { id: 'a1', name: 'Midterm', earnedPoints: 18, maxPoints: 20, isCompleted: true },
    ]);

    expect(html).toContain(' / 100 pts');
    expect(html).not.toContain(' / 20 pts');
    expect(html).toContain('82 still open');
    // The grade is read from the same 18/100 basis as the points line.
    expect(html).toContain('aria-label="Grade 1 of 5"');
    expect(html).toContain('>Fail<');
    expect(html).toContain('width:18%');
  });

  it('honours a course total set in Rules', () => {
    const { html } = renderTab(
      [{ id: 'a1', name: 'Midterm', earnedPoints: 18, maxPoints: 20, isCompleted: true }],
      60
    );

    expect(html).toContain(' / 60 pts');
    expect(html).toContain('42 still open');
  });

  it('never renders assessment categories', () => {
    const { html } = renderTab([
      { id: 'a1', name: 'Midterm', earnedPoints: 18, maxPoints: 20, isCompleted: true },
    ]);

    expect(html).not.toContain('midterm');
    expect(html).not.toContain('Category');
  });

  it('says there is no grade yet in words, without a placeholder dash', () => {
    const { html } = renderTab([]);
    expect(html).toContain('No grade yet');
    expect(html).not.toContain('—');
  });

  it('offers to add the score of an ungraded assessment instead of showing zero', () => {
    const { html } = renderTab([{ id: 'a1', name: 'Final exam', earnedPoints: 0, maxPoints: 40, isCompleted: false }]);
    expect(html).toContain('Add score');
    expect(html).toContain('not graded yet');
    expect(html).not.toContain('Edit score');
  });

  it('shows an edit control on every assessment', () => {
    const { html } = renderTab([
      { id: 'a1', name: 'Midterm', earnedPoints: 18, maxPoints: 20, isCompleted: true },
      { id: 'a2', name: 'Final exam', earnedPoints: 0, maxPoints: 40, isCompleted: false },
    ]);
    expect(html).toContain('aria-label="Edit Midterm"');
    expect(html).toContain('aria-label="Edit Final exam"');
  });

  it('reads the bar from the weighted grade in weighted mode', () => {
    const tracker = createDefaultTracker({ courseName: 'Algorithms', courseCode: 'CS201' });
    tracker.points.useWeightedCategories = true;
    tracker.points.assessments = [
      { id: 'a1', name: 'Midterm', earnedPoints: 40, maxPoints: 50, weightPercent: 50, isCompleted: true },
      { id: 'a2', name: 'Quiz', earnedPoints: 10, maxPoints: 10, isCompleted: true },
    ];
    const html = render(tracker);

    // Weighted grade is 40% (the unweighted quiz is ignored), not 50/100 raw.
    expect(html).toContain('>40%</span>');
    expect(html).toContain('60% still open');
    expect(html).toContain('width:40%');
    expect(html).toContain('50% of grade');
    expect(html).toContain('1 assessment has no weight');
  });
});
