import { describe, expect, it } from 'vitest';
import { isExamEvent, isExamSummary, isExamTypeParenthetical } from './exam-detection';

describe('isExamSummary', () => {
  it('recognises the Hungarian suffix', () => {
    expect(isExamSummary('Microeconomics for Business - Feladat - Vizsga')).toBe(true);
    expect(isExamSummary('Business Economics - Vizsga')).toBe(true);
  });

  it('recognises exam-type parentheticals', () => {
    expect(isExamSummary('Elements of Accounting (Írásbeli) - Feladat')).toBe(true);
    expect(isExamSummary('Legal Studies (Szóbeli) - Dr. Kiss')).toBe(true);
    expect(isExamSummary('Economics (written) - Feladat')).toBe(true);
  });

  it('recognises an English-titled exam that carries no Vizsga suffix', () => {
    // The regression this module exists for: these were imported as classes.
    expect(isExamSummary('Elements of Accounting Midterm Exam - Feladat')).toBe(true);
    expect(isExamSummary('Elements of Accounting Midterm Exam - Feladat - Tanóra')).toBe(true);
  });

  it('recognises Hungarian midterm (zárthelyi / ZH) markers', () => {
    expect(isExamSummary('1.zh (Zárthelyi dolgozat)')).toBe(true);
    expect(isExamSummary('Mikroökonómia ZH2 - Dr. Kiss')).toBe(true);
    expect(isExamSummary('Statisztika 2. ZH - Tanóra')).toBe(true);
    expect(isExamSummary('Számvitel pótZH')).toBe(true);
    expect(isExamSummary('Számvitel pót-zh')).toBe(true);
    expect(isExamSummary('Zarthelyi dolgozat - Makro')).toBe(true);
  });

  it('does not read "zh" inside other words or évközi grading as an exam', () => {
    expect(isExamSummary('Zhang Wei Seminar (E01) - Tanóra')).toBe(false);
    expect(isExamSummary('Hazhi Studies - Tanóra')).toBe(false);
    expect(isExamSummary('Évközi jegyes kurzus (G01) - Tanóra')).toBe(false);
  });

  it('leaves ordinary classes alone', () => {
    expect(isExamSummary('Calculus I (MATH101-E01) - Prof. Euler - Tanóra')).toBe(false);
    expect(isExamSummary('Basics of Financial Markets (E01) - Teszt Elek')).toBe(false);
    expect(isExamSummary('')).toBe(false);
  });
});

describe('isExamTypeParenthetical', () => {
  it('separates an exam type from a course code', () => {
    expect(isExamTypeParenthetical('Írásbeli')).toBe(true);
    expect(isExamTypeParenthetical(' - Szóbeli')).toBe(true);
    expect(isExamTypeParenthetical('MATH101-E01')).toBe(false);
  });
});

describe('isExamEvent', () => {
  it('trusts an explicit exam type', () => {
    expect(isExamEvent({ event_type: 'exam', course_name: 'Anything At All' })).toBe(true);
    expect(isExamEvent({ event_type: 'EXAM', course_name: '' })).toBe(true);
  });

  it('falls back to the title for rows imported as classes', () => {
    expect(
      isExamEvent({ event_type: 'class', course_name: 'Elements of Accounting Midterm Exam' })
    ).toBe(true);
    expect(
      isExamEvent({ event_type: 'class', course_name: 'Microeconomics for Business (Írásbeli)' })
    ).toBe(true);
  });

  it('does not override an explicit non-class type', () => {
    expect(isExamEvent({ event_type: 'other', course_name: 'Final Exam Review' })).toBe(false);
    expect(isExamEvent({ event_type: 'lecture', course_name: 'Exam Prep Lecture' })).toBe(false);
  });

  it('leaves ordinary classes as classes', () => {
    expect(isExamEvent({ event_type: 'class', course_name: 'Marketing' })).toBe(false);
    expect(isExamEvent({ event_type: null, course_name: 'Marketing (E01) - Hubert József' })).toBe(
      false
    );
    expect(isExamEvent({})).toBe(false);
  });
});
