package dev.southstar21.vectra.platform

import androidx.compose.ui.window.ComposeUIViewController
import dev.southstar21.vectra.AppGraph
import dev.southstar21.vectra.VectraApp
import dev.southstar21.vectra.core.config.VectraConfig
import dev.southstar21.vectra.core.platform.NoPush
import dev.southstar21.vectra.core.util.runSuspendCatching
import dev.southstar21.vectra.navigation.DeepLinks
import dev.zacsweers.metro.createGraphFactory
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import platform.BackgroundTasks.BGAppRefreshTask
import platform.BackgroundTasks.BGAppRefreshTaskRequest
import platform.BackgroundTasks.BGTaskScheduler
import platform.Foundation.NSDate
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIViewController
import kotlin.experimental.ExperimentalNativeApi

/** The iOS counterpart of androidApp's VectraApplication: the platform pieces and the graph. */
object IosApp {
    internal val notifier by lazy { IosNotifier() }

    @OptIn(ExperimentalNativeApi::class)
    val graph: AppGraph by lazy {
        createGraphFactory<AppGraph.Factory>().create(
            config = VectraConfig(
                // Debug builds use the dev backend, like androidApp's debug build type.
                pocketbaseUrl = if (Platform.isDebugBinary) {
                    IosBuildConfig.DEV_POCKETBASE_URL
                } else {
                    IosBuildConfig.POCKETBASE_URL
                },
                catalogOrigin = IosBuildConfig.CATALOG_ORIGIN,
                appVersion = if (Platform.isDebugBinary) {
                    IosBuildConfig.DEBUG_VERSION_NAME
                } else {
                    IosBuildConfig.VERSION_NAME
                },
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
            widgetHost = IosWidgets,
        )
    }

    /**
     * A `vectra://open/<path>` link (the widgets use `vectra://open/schedule`): queues `/<path>` for
     * the shell like a notification tap. Anything else is ignored.
     */
    fun openUrl(url: String) {
        val path = url.removePrefix("vectra://open").takeIf { it != url && it.startsWith("/") } ?: return
        DeepLinks.pending.value = path
    }

    /** Called from the Swift app's init, before launch finishes, so cold-start notification taps arrive. */
    fun start() {
        installNotificationTaps()
        registerReminderRefresh()
        val center = NSNotificationCenter.defaultCenter
        // Each foregrounding replans, which also refreshes the timetable times baked into reminders.
        center.addObserverForName(UIApplicationDidBecomeActiveNotification, null, NSOperationQueue.mainQueue) { _ ->
            graph.reminders.start()
            graph.reminders.requestReplan()
            graph.widgets.start()
        }
        center.addObserverForName(UIApplicationDidEnterBackgroundNotification, null, NSOperationQueue.mainQueue) { _ ->
            scheduleReminderRefresh()
        }
    }

    /**
     * Background refresh tops up the week of reminders and their departure times when the app
     * isn't opened. iOS decides when it actually runs (typically a few times a day).
     */
    private fun registerReminderRefresh() {
        BGTaskScheduler.sharedScheduler.registerForTaskWithIdentifier(ReminderRefreshTask, null) { task ->
            val refresh = task as? BGAppRefreshTask ?: return@registerForTaskWithIdentifier
            scheduleReminderRefresh()
            // The task is completed exactly once: by the work finishing, or by iOS expiring it first.
            val completed = CompletableDeferred<Boolean>()
            fun complete(success: Boolean) {
                if (completed.complete(success)) refresh.setTaskCompletedWithSuccess(success)
            }
            val job = graph.scope.launch {
                runSuspendCatching { graph.reminders.rescheduleWhenReady() }
                // Background refresh also rolls the widgets over to a new day when the app isn't opened.
                runSuspendCatching { graph.widgets.publishWhenReady() }
                complete(true)
            }
            refresh.expirationHandler = {
                job.cancel()
                complete(false)
            }
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun scheduleReminderRefresh() {
        val request = BGAppRefreshTaskRequest(ReminderRefreshTask)
        request.earliestBeginDate = NSDate.dateWithTimeIntervalSinceNow(60.0 * 60)
        BGTaskScheduler.sharedScheduler.submitTaskRequest(request, null)
    }

    private const val ReminderRefreshTask = "dev.southstar21.vectra.reminders"
}

/** The whole app, hosted by iosApp's SwiftUI scene. */
@Suppress("FunctionName", "unused")
fun MainViewController(): UIViewController = ComposeUIViewController { VectraApp(IosApp.graph) }
