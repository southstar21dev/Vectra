import {
  DEFAULT_COURSE_TOTAL_POINTS,
  DEFAULT_GRADING_SCALE,
  type AssessmentItem,
  type GradingScaleTier,
} from './tracker-types';

export interface PointsSummary {
  totalEarned: number;
  totalMax: number;
  scoredMax: number;
  /** Points earned on the scored (completed) assessments only. */
  scoredEarned: number;
  remainingMax: number;
  /** Success rate on the work graded so far: scored earned / scored max. */
  currentPercentage: number;
  /**
   * Share of the COURSE earned so far — earned / the course's total points
   * (or the weighted sum when weights are in play). This is the number the
   * grade is estimated from, so it always matches the Points Earned card.
   */
  overallPercentage: number;
  estimatedGrade: 1 | 2 | 3 | 4 | 5;
  hasFailedMustPass: boolean;
  failedMustPassItems: AssessmentItem[];
}

export function estimateGrade(percentage: number, scale: GradingScaleTier[] = DEFAULT_GRADING_SCALE): 1 | 2 | 3 | 4 | 5 {
  const sorted = [...scale].sort((a, b) => b.minPercentage - a.minPercentage);
  for (const tier of sorted) {
    if (percentage >= tier.minPercentage) {
      return tier.grade;
    }
  }
  return 1;
}

export function calculatePointsSummary(
  assessments: AssessmentItem[],
  options: {
    useWeightedCategories?: boolean;
    gradingScale?: GradingScaleTier[];
    /** The course's total points — the denominator of every percentage here. */
    totalPoints?: number;
  }
): PointsSummary {
  const scale = options.gradingScale || DEFAULT_GRADING_SCALE;
  const courseTotal = options.totalPoints && options.totalPoints > 0 ? options.totalPoints : DEFAULT_COURSE_TOTAL_POINTS;
  let totalEarned = 0;
  let totalMax = 0;
  let scoredMax = 0;
  let scoredEarned = 0;
  const failedMustPassItems: AssessmentItem[] = [];

  for (const item of assessments) {
    totalEarned += item.earnedPoints;
    totalMax += item.maxPoints;

    const isScored = item.isCompleted ?? (item.earnedPoints > 0);
    if (isScored) {
      scoredMax += item.maxPoints;
      scoredEarned += item.earnedPoints;
    }

    if (item.isMustPass && isScored) {
      const minRequired = item.minPassPoints ?? item.maxPoints * 0.5;
      if (item.earnedPoints < minRequired) {
        failedMustPassItems.push(item);
      }
    }
  }

  const remainingMax = Math.max(0, totalMax - scoredMax);
  // Numerator and denominator cover the same items: points typed on an
  // assessment not yet marked complete must not push the rate past 100%.
  const currentPercentage = scoredMax > 0 ? (scoredEarned / scoredMax) * 100 : 0;

  // Share of the whole course, not of the assessments logged so far: a single
  // 18/20 quiz on a 100-point course is 18% of the course, never 90%.
  let overallPercentage = 0;
  if (options.useWeightedCategories) {
    let weightedSum = 0;
    for (const item of assessments) {
      if (item.maxPoints > 0 && typeof item.weightPercent === 'number') {
        const itemPct = (item.earnedPoints / item.maxPoints) * item.weightPercent;
        weightedSum += itemPct;
      }
    }
    overallPercentage = weightedSum;
  } else {
    overallPercentage = courseTotal > 0 ? (totalEarned / courseTotal) * 100 : 0;
  }

  const hasFailedMustPass = failedMustPassItems.length > 0;
  const estimatedGrade = hasFailedMustPass ? 1 : estimateGrade(overallPercentage, scale);

  return {
    totalEarned,
    totalMax,
    scoredMax,
    scoredEarned,
    remainingMax,
    currentPercentage,
    overallPercentage,
    estimatedGrade,
    hasFailedMustPass,
    failedMustPassItems,
  };
}

export function calculatePointsToTarget(params: {
  totalEarned: number;
  totalMax: number;
  targetGrade: 1 | 2 | 3 | 4 | 5;
  gradingScale?: GradingScaleTier[];
}): number | null {
  const scale = params.gradingScale || DEFAULT_GRADING_SCALE;
  const tier = scale.find((t) => t.grade === params.targetGrade);
  if (!tier) return null;
  const targetPoints = (tier.minPercentage / 100) * params.totalMax;
  const needed = targetPoints - params.totalEarned;
  return Math.max(0, Math.ceil(needed));
}
