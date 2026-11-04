package dev.southstar21.vectra.platform

import androidx.compose.ui.window.ComposeUIViewController
import dev.southstar21.vectra.AppGraph
import dev.southstar21.vectra.VectraApp
import dev.southstar21.vectra.core.config.VectraConfig
import dev.southstar21.vectra.core.platform.NoPush
import dev.zacsweers.metro.createGraphFactory
import platform.UIKit.UIViewController
import kotlin.experimental.ExperimentalNativeApi

/** The iOS counterpart of androidApp's VectraApplication: the platform pieces and the graph. */
object IosApp {
    internal val notifier by lazy { IosNotifier() }

    @OptIn(ExperimentalNativeApi::class)
    val graph: AppGraph by lazy {
        createGraphFactory<AppGraph.Factory>().create(
            config = VectraConfig(
                pocketbaseUrl = IosBuildConfig.POCKETBASE_URL,
                catalogOrigin = IosBuildConfig.CATALOG_ORIGIN,
                appVersion = IosBuildConfig.VERSION_NAME,
                isDebug = Platform.isDebugBinary,
            ),
            dataStore = createDataStore(),
            blobs = createBlobStore(),
            http = createIosHttpClient(),
            cipher = KeychainSecretCipher(),
            connectivity = IosConnectivity(),
            location = IosLocationProvider(),
            launcher = IosLauncher(),
            passkeyAuthenticator = IosPasskeyAuthenticator(),
            notifier = notifier,
            push = NoPush,
        )
    }

    /** Called from the Swift app's init, before launch finishes, so cold-start notification taps arrive. */
    fun start() {
        installNotificationTaps()
    }
}

/** The whole app, hosted by iosApp's SwiftUI scene. */
@Suppress("FunctionName", "unused")
fun MainViewController(): UIViewController = ComposeUIViewController { VectraApp(IosApp.graph) }
