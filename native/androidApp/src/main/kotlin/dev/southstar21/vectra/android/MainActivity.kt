package dev.southstar21.vectra.android

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.ReportDrawnWhen
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import dev.southstar21.vectra.VectraApp
import dev.southstar21.vectra.navigation.DeepLinks
import dev.southstar21.vectra.platform.AndroidNotifier

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        lockPortraitOnPhones()
        val app = application as VectraApplication
        app.passkeys.attach(this)
        val graph = app.graph
        val appearance = graph.appearanceRepository
        splash.setKeepOnScreenCondition { !appearance.loaded.value }
        // The app's own splash draws the same owl in the same place underneath: drop the system
        // splash at once (its default fade-out would blink the owl), and the owl just carries on.
        splash.setOnExitAnimationListener { it.remove() }
        // Only a fresh launch carries a new link: a recreate (dark mode, font size) re-delivers the same
        // intent, and replaying it would pop whatever screen is open now.
        if (savedInstanceState == null) handle(intent)
        setContent {
            VectraApp(graph)
            // Fully drawn: the session is restored and the real screen is composed, under the splash if it's still up.
            ReportDrawnWhen { graph.startup.restored }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        lockPortraitOnPhones()
    }

    /**
     * Portrait on phones only: tablets, unfolded foldables and Chromebooks (smallest width 600 dp or more)
     * rotate freely. Decided at runtime so a foldable that unfolds is unlocked; the manifest sets no lock.
     */
    private fun lockPortraitOnPhones() {
        val phone = resources.configuration.smallestScreenWidthDp < TABLET_MIN_WIDTH_DP
        requestedOrientation = if (phone) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        intent?.getStringExtra(AndroidNotifier.EXTRA_URL)?.let { DeepLinks.pending.value = it }
        // Consumed: the activity keeps this intent, so nothing may read the link from it again.
        intent?.removeExtra(AndroidNotifier.EXTRA_URL)
    }

    private companion object {
        const val TABLET_MIN_WIDTH_DP = 600
    }
}
