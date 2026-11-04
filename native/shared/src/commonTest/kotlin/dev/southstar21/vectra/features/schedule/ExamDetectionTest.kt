package dev.southstar21.vectra.features.schedule

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExamDetectionTest {
    @Test fun recognisesHungarianSuffix() {
        assertTrue(isExamSummary("Microeconomics for Business - Feladat - Vizsga"))
        assertTrue(isExamSummary("Business Economics - Vizsga"))
    }

    @Test fun recognisesExamTypeParentheticals() {
        assertTrue(isExamSummary("Elements of Accounting (Írásbeli) - Feladat"))
        assertTrue(isExamSummary("Legal Studies (Szóbeli) - Dr. Kiss"))
        assertTrue(isExamSummary("Economics (written) - Feladat"))
    }

    @Test fun recognisesEnglishTitledExam() {
        assertTrue(isExamSummary("Elements of Accounting Midterm Exam - Feladat"))
        assertTrue(isExamSummary("Elements of Accounting Midterm Exam - Feladat - Tanóra"))
    }

    @Test fun recognisesHungarianMidtermMarkers() {
        assertTrue(isExamSummary("1.zh (Zárthelyi dolgozat)"))
        assertTrue(isExamSummary("Mikroökonómia ZH2 - Dr. Kiss"))
        assertTrue(isExamSummary("Statisztika 2. ZH - Tanóra"))
        assertTrue(isExamSummary("Számvitel pótZH"))
        assertTrue(isExamSummary("Számvitel pót-zh"))
        assertTrue(isExamSummary("Zarthelyi dolgozat - Makro"))
    }

    @Test fun doesNotReadZhInsideWords() {
        assertFalse(isExamSummary("Zhang Wei Seminar (E01) - Tanóra"))
        assertFalse(isExamSummary("Hazhi Studies - Tanóra"))
        assertFalse(isExamSummary("Évközi jegyes kurzus (G01) - Tanóra"))
    }

    @Test fun leavesOrdinaryClassesAlone() {
        assertFalse(isExamSummary("Calculus I (MATH101-E01) - Prof. Euler - Tanóra"))
        assertFalse(isExamSummary("Basics of Financial Markets (E01) - Teszt Elek"))
        assertFalse(isExamSummary(""))
    }

    @Test fun separatesExamTypeFromCourseCode() {
        assertTrue(isExamTypeParenthetical("Írásbeli"))
        assertTrue(isExamTypeParenthetical(" - Szóbeli"))
        assertFalse(isExamTypeParenthetical("MATH101-E01"))
    }

    @Test fun trustsExplicitExamType() {
        assertTrue(isExamEvent("exam", "Anything At All"))
        assertTrue(isExamEvent("EXAM", ""))
    }

    @Test fun fallsBackToTitle() {
        assertTrue(isExamEvent("class", "Elements of Accounting Midterm Exam"))
        assertTrue(isExamEvent("class", "Microeconomics for Business (Írásbeli)"))
    }

    @Test fun doesNotOverrideExplicitNonClassType() {
        assertFalse(isExamEvent("other", "Final Exam Review"))
        assertFalse(isExamEvent("lecture", "Exam Prep Lecture"))
    }

    @Test fun leavesOrdinaryClassesAsClasses() {
        assertFalse(isExamEvent("class", "Marketing"))
        assertFalse(isExamEvent(null, "Marketing (E01) - Hubert József"))
        assertFalse(isExamEvent(null, null))
    }
}
