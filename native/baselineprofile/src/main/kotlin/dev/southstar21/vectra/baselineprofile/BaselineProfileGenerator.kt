package dev.southstar21.vectra.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Run through `./gradlew :androidApp:generateBaselineProfile`; see the README. */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    /** Only this one feeds the startup profile, which lays out the dex: journey code there would dilute it. */
    @Test
    fun coldStart() = rule.collect(packageName = TargetPackage, maxIterations = MAX_ITERATIONS, includeInStartupProfile = true) {
        prepareLaunch()
        startActivityAndWait()
        awaitSignedIn()
    }

    @Test
    fun criticalJourneys() = rule.collect(packageName = TargetPackage, maxIterations = MAX_ITERATIONS) {
        prepareLaunch()
        startActivityAndWait()
        awaitSignedIn()
        runCriticalJourneys()
    }

    private companion object {
        // The journeys touch live data, so the rule's stability check rarely settles; five passes cover them.
        const val MAX_ITERATIONS = 5
    }
}
