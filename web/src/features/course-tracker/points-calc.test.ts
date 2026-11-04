import { describe, it, expect } from 'vitest';
import {
  calculatePointsSummary,
  calculatePointsToTarget,
} from './points-calc';
import { DEFAULT_GRADING_SCALE, type AssessmentItem } from './tracker-types';

describe('points-calc', () => {
  it('calculates unweighted direct point sums', () => {
    const assessments: AssessmentItem[] = [
      { id: '1', name: 'Quiz 1', earnedPoints: 10, maxPoints: 10 },
      { id: '2', name: 'Midterm', earnedPoints: 35, maxPoints: 40 },
      { id: '3', name: 'Homework', earnedPoints: 20, maxPoints: 25 },
      { id: '4', name: 'Final', earnedPoints: 0, maxPoints: 25 }, // not taken yet
    ];

    const summary = calculatePointsSummary(assessments, {
      useWeightedCategories: false,
      gradingScale: DEFAULT_GRADING_SCALE,
    });

    expect(summary.totalEarned).toBe(65);
    expect(summary.totalMax).toBe(100);
    expect(summary.scoredMax).toBe(75); // 10+40+25
    expect(summary.currentPercentage).toBeCloseTo(86.67, 1); // 65 / 75 = 86.67% (graded work only)
    expect(summary.overallPercentage).toBe(65); // 65 / 100 of the course
    expect(summary.estimatedGrade).toBe(3); // 65% of the course is in the 62-75 range (Grade 3)
  });

  it('estimates the grade against the course total, not the logged assessments', () => {
    const assessments: AssessmentItem[] = [{ id: '1', name: 'Midterm', earnedPoints: 18, maxPoints: 20 }];

    const onHundred = calculatePointsSummary(assessments, {
      useWeightedCategories: false,
      gradingScale: DEFAULT_GRADING_SCALE,
      totalPoints: 100,
    });

    expect(onHundred.totalEarned).toBe(18);
    expect(onHundred.overallPercentage).toBe(18); // 18 / 100, never 18 / 20 = 90%
    expect(onHundred.estimatedGrade).toBe(1); // 18% is below Grade 2

    const onTwenty = calculatePointsSummary(assessments, {
      useWeightedCategories: false,
      gradingScale: DEFAULT_GRADING_SCALE,
      totalPoints: 20,
    });

    expect(onTwenty.overallPercentage).toBe(90);
    expect(onTwenty.estimatedGrade).toBe(5); // 90% is in the 87+ range
  });

  it('calculates weighted assessments when enabled', () => {
    const assessments: AssessmentItem[] = [
      { id: '1', name: 'Quiz Avg', earnedPoints: 8, maxPoints: 10, weightPercent: 20 }, // 80% * 20% = 16%
      { id: '2', name: 'Midterm', earnedPoints: 40, maxPoints: 50, weightPercent: 40 }, // 80% * 40% = 32%
      { id: '3', name: 'Final', earnedPoints: 36, maxPoints: 40, weightPercent: 40 }, // 90% * 40% = 36%
    ];

    const summary = calculatePointsSummary(assessments, {
      useWeightedCategories: true,
      gradingScale: DEFAULT_GRADING_SCALE,
    });

    expect(summary.overallPercentage).toBeCloseTo(84.0, 1); // 16 + 32 + 36 = 84%
    expect(summary.estimatedGrade).toBe(4); // 84% is >= 75% but < 87%
  });

  it('detects failed must-pass requirement and caps grade at 1', () => {
    const assessments: AssessmentItem[] = [
      { id: '1', name: 'Homework', earnedPoints: 50, maxPoints: 50 },
      {
        id: '2',
        name: 'Final Exam',
        earnedPoints: 15,
        maxPoints: 50,
        isMustPass: true,
        minPassPoints: 25,
      },
    ];

    const summary = calculatePointsSummary(assessments, {
      useWeightedCategories: false,
      gradingScale: DEFAULT_GRADING_SCALE,
    });

    expect(summary.hasFailedMustPass).toBe(true);
    expect(summary.failedMustPassItems.length).toBe(1);
    expect(summary.estimatedGrade).toBe(1);
  });

  it('computes points needed to reach target grade', () => {
    const targetPoints = calculatePointsToTarget({
      totalEarned: 50,
      totalMax: 100,
      targetGrade: 5,
      gradingScale: DEFAULT_GRADING_SCALE,
    });
    // Grade 5 is 87% of 100 = 87 pts. 87 - 50 = 37 pts needed.
    expect(targetPoints).toBe(37);
  });
});

describe('points-calc consistency', () => {
  it('keeps the success rate within 100% when an incomplete item carries points', () => {
    const assessments: AssessmentItem[] = [
      { id: '1', name: 'Quiz', earnedPoints: 10, maxPoints: 10, isCompleted: true },
      // Points typed in ahead of marking it complete.
      { id: '2', name: 'Lab', earnedPoints: 15, maxPoints: 20, isCompleted: false },
    ];

    const summary = calculatePointsSummary(assessments, { totalPoints: 100 });

    expect(summary.totalEarned).toBe(25);
    expect(summary.scoredMax).toBe(10);
    expect(summary.scoredEarned).toBe(10);
    expect(summary.currentPercentage).toBe(100);
  });

  it('ignores unweighted assessments in weighted mode', () => {
    const assessments: AssessmentItem[] = [
      { id: '1', name: 'Midterm', earnedPoints: 40, maxPoints: 50, weightPercent: 50 }, // 40%
      { id: '2', name: 'Quiz', earnedPoints: 10, maxPoints: 10 }, // no weight
    ];

    const summary = calculatePointsSummary(assessments, { useWeightedCategories: true, totalPoints: 100 });

    expect(summary.overallPercentage).toBeCloseTo(40, 5);
    // Distance to Grade 3 (62%) in percentage points of the weighted grade.
    expect(
      calculatePointsToTarget({ totalEarned: summary.overallPercentage, totalMax: 100, targetGrade: 3 })
    ).toBe(22);
  });
});
