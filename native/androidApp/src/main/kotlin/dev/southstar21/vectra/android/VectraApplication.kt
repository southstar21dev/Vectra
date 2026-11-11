package dev.southstar21.vectra.android

import android.app.Application
import dev.southstar21.vectra.AppGraph
import dev.southstar21.vectra.android.widget.AndroidWidgetHost
import dev.southstar21.vectra.android.widget.WidgetPreviews
import dev.southstar21.vectra.core.config.VectraConfig
import dev.southstar21.vectra.platform.AndroidConnectivity
import dev.southstar21.vectra.core.platform.NoPush
import dev.southstar21.vectra.platform.AndroidLauncher
import dev.southstar21.vectra.platform.AndroidNotifier
import dev.southstar21.vectra.platform.AndroidLocationProvider
import dev.southstar21.vectra.platform.AndroidPasskeyAuthenticator
import dev.southstar21.vectra.platform.KeystoreSecretCipher
import dev.southstar21.vectra.platform.ReminderRuntime
import dev.southstar21.vectra.platform.createAndroidHttpClient
import dev.southstar21.vectra.platform.createBlobStore
import dev.southstar21.vectra.platform.createCatalogCache
import dev.southstar21.vectra.platform.createDataStore
import dev.zacsweers.metro.createGraphFactory
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.thread

class VectraApplication : Application() {
    val passkeys by lazy { AndroidPasskeyAuthenticator(this) }

    /** Builds the graph and loads preferences ahead of the first frame, which would otherwise do both on Main. */
    override fun onCreate() {
        super.onCreate()
        // Reminder alarms can start the process with no activity; the receiver reaches the graph through this.
        ReminderRuntime.scheduler = { graph.reminders }
        thread(name = "vectra-warmup", priority = Thread.NORM_PRIORITY) {
            runCatching {
                graph.auth
                runBlocking { graph.kv.get("vectra.appearance") }
                graph.reminders.start()
                graph.widgets.start()
                runBlocking { WidgetPreviews.publishIfNeeded(this@VectraApplication, BuildConfig.VERSION_NAME) }
            }
        }
    }

    val graph: AppGraph by lazy {
        createGraphFactory<AppGraph.Factory>().create(
            config = VectraConfig(
                pocketbaseUrl = BuildConfig.POCKETBASE_URL,
                catalogOrigin = BuildConfig.CATALOG_ORIGIN,
                appVersion = BuildConfig.VERSION_NAME,
                isDebug = BuildConfig.DEBUG,
            ),
            dataStore = createDataStore(this),
            blobs = createBlobStore(this),
            catalogCache = createCatalogCache(this),
            http = createAndroidHttpClient(this),
            cipher = KeystoreSecretCipher(),
            connectivity = AndroidConnectivity(this),
            location = AndroidLocationProvider(this),
            launcher = AndroidLauncher(this),
            passkeyAuthenticator = passkeys,
            notifier = AndroidNotifier(this, R.drawable.ic_stat_vectra, MainActivity::class.java),
            push = NoPush,
            widgetHost = AndroidWidgetHost(this),
        )
    }
}
