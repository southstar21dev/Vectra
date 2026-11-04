package dev.southstar21.vectra.android

import android.content.Intent
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
        val app = application as VectraApplication
        app.passkeys.attach(this)
        val graph = app.graph
        val appearance = graph.appearanceRepository
        splash.setKeepOnScreenCondition { !appearance.loaded.value }
        handle(intent)
        setContent {
            VectraApp(graph)
            // Fully drawn: the session is restored and the real screen is composed, under the splash if it's still up.
            ReportDrawnWhen { graph.startup.restored }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        intent?.getStringExtra(AndroidNotifier.EXTRA_URL)?.let { DeepLinks.pending.value = it }
    }
}
