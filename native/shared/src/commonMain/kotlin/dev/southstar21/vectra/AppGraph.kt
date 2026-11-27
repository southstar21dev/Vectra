package dev.southstar21.vectra

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import dev.southstar21.vectra.core.auth.AuthRepository
import dev.southstar21.vectra.core.auth.SessionListener
import dev.southstar21.vectra.core.auth.SessionWatchdog
import dev.southstar21.vectra.core.config.VectraConfig
import dev.southstar21.vectra.core.data.BlobStore
import dev.southstar21.vectra.core.data.KeyValueStore
import dev.southstar21.vectra.core.maps.MapEnvironment
import dev.southstar21.vectra.core.network.AuthStore
import dev.southstar21.vectra.core.network.CryptoClient
import dev.southstar21.vectra.core.network.PocketBaseClient
import dev.southstar21.vectra.core.platform.Connectivity
import dev.southstar21.vectra.core.platform.HttpCache
import dev.southstar21.vectra.core.platform.Launcher
import dev.southstar21.vectra.core.platform.LocationProvider
import dev.southstar21.vectra.core.platform.Notifier
import dev.southstar21.vectra.core.platform.PasskeyAuthenticator
import dev.southstar21.vectra.core.platform.PushRegistrar
import dev.southstar21.vectra.core.platform.platformName
import dev.southstar21.vectra.core.platform.SecretCipher
import dev.southstar21.vectra.core.platform.WidgetHost
import dev.southstar21.vectra.features.auth.PasskeyRepository
import dev.southstar21.vectra.features.coursetracker.CourseTrackerRepository
import dev.southstar21.vectra.features.coursetracker.CourseTrackerScheduleHooks
import dev.southstar21.vectra.features.coursetracker.CourseTrackerScheduleSync
import dev.southstar21.vectra.features.coursetracker.CryptoTrackerCipher
import dev.southstar21.vectra.features.coursetracker.PocketBaseTrackerCloud
import dev.southstar21.vectra.features.notifications.NotificationsRepository
import dev.southstar21.vectra.features.reminders.ReminderScheduler
import dev.southstar21.vectra.features.transit.data.CatalogDiskCache
import dev.southstar21.vectra.features.transit.data.DeviceLocation
import dev.southstar21.vectra.features.transit.data.FutarApi
import dev.southstar21.vectra.features.transit.data.FutarSource
import dev.southstar21.vectra.features.transit.data.RealFutarSource
import dev.southstar21.vectra.features.transit.demo.DemoFutarSource
import dev.southstar21.vectra.features.transit.demo.DemoSeeder
import dev.southstar21.vectra.features.transit.data.TransitRepository
import dev.southstar21.vectra.features.widgets.WidgetPublisher
import dev.southstar21.vectra.features.schedule.NeptunSyncListener
import dev.southstar21.vectra.features.schedule.ScheduleCourseTrackerHooks
import dev.southstar21.vectra.features.schedule.ScheduleRepository
import dev.southstar21.vectra.features.settings.AppearanceRepository
import dev.southstar21.vectra.features.update.UpdateGate
import dev.southstar21.vectra.features.weather.WeatherRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The app's object graph (Metro, compile-time). Platform pieces come in through the factory;
 * everything here is an app-lifetime singleton, built the first time it is asked for.
 */
@DependencyGraph(AppScope::class)
abstract class AppGraph {
    internal abstract val config: VectraConfig
    internal abstract val http: HttpClient
    abstract val kv: KeyValueStore
    internal abstract val pb: PocketBaseClient
    internal abstract val crypto: CryptoClient
    abstract val auth: AuthRepository
    internal abstract val watchdog: SessionWatchdog
    internal abstract val scope: CoroutineScope
    internal abstract val connectivity: Connectivity
    internal abstract val location: LocationProvider
    internal abstract val launcher: Launcher
    internal abstract val passkeyAuthenticator: PasskeyAuthenticator
    internal abstract val notifier: Notifier
    internal abstract val push: PushRegistrar
    abstract val startup: ProcessStartup
    internal abstract val mapEnvironment: MapEnvironment

    abstract val appearanceRepository: AppearanceRepository
    internal abstract val passkeyRepository: PasskeyRepository
    internal abstract val notificationsRepository: NotificationsRepository
    internal abstract val scheduleRepository: ScheduleRepository
    internal abstract val courseTrackerRepository: CourseTrackerRepository
    internal abstract val courseTrackerHooks: ScheduleCourseTrackerHooks
    internal abstract val transitRepository: TransitRepository
    internal abstract val demoSeeder: DemoSeeder
    internal abstract val deviceLocation: DeviceLocation
    internal abstract val weatherRepository: WeatherRepository
    abstract val reminders: ReminderScheduler
    abstract val widgets: WidgetPublisher
    internal abstract val updateGate: UpdateGate

    @SingleIn(AppScope::class)
    @Provides
    internal fun provideStartup(): ProcessStartup = ProcessStartup()

    @Provides
    internal fun provideMapEnvironment(http: HttpClient, connectivity: Connectivity): MapEnvironment = MapEnvironment(http, connectivity)

    @SingleIn(AppScope::class)
    @Provides
    internal fun provideScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @SingleIn(AppScope::class)
    @Provides
    internal fun provideKv(store: DataStore<Preferences>, blobs: BlobStore): KeyValueStore = KeyValueStore(store, blobs)

    @SingleIn(AppScope::class)
    @Provides
    internal fun provideAuthStore(kv: KeyValueStore, cipher: SecretCipher): AuthStore = AuthStore(kv, cipher)

    @SingleIn(AppScope::class)
    @Provides
    internal fun providePb(http: HttpClient, config: VectraConfig, store: AuthStore, updateGate: UpdateGate): PocketBaseClient =
        PocketBaseClient(http, config.pocketbaseUrl, store, clientVersion = config.appVersion, clientPlatform = platformName)
            .apply { onUpdateRequired = updateGate::report }

    @SingleIn(AppScope::class)
    @Provides
    internal fun provideUpdateGate(kv: KeyValueStore, scope: CoroutineScope, config: VectraConfig): UpdateGate =
        UpdateGate(kv, scope, config.appVersion)

    @SingleIn(AppScope::class)
    @Provides
    internal fun provideCrypto(pb: PocketBaseClient, scope: CoroutineScope): CryptoClient = CryptoClient(pb, scope)

    /**
     * The sign-out listeners are declared here rather than registered by each repository as
     * it is built, so one that no screen has opened yet still runs. Lazy because a listener
     * may depend on [AuthRepository], and because nothing needs them until a session ends.
     */
    @SingleIn(AppScope::class)
    @Provides
    internal fun provideAuth(
        pb: PocketBaseClient,
        store: AuthStore,
        kv: KeyValueStore,
        scope: CoroutineScope,
        listeners: Lazy<List<SessionListener>>,
    ): AuthRepository = AuthRepository(pb, store, kv, scope, listeners)

    @Provides
    internal fun provideSessionListeners(
        crypto: CryptoClient,
        httpCache: HttpCache,
        transit: TransitRepository,
        notifications: NotificationsRepository,
        reminders: ReminderScheduler,
        widgets: WidgetPublisher,
        passkeys: PasskeyRepository,
        schedule: ScheduleRepository,
        courseTracker: CourseTrackerRepository,
    ): List<SessionListener> = listOf(
        object : SessionListener {
            override suspend fun onSessionCleared() = crypto.clearMemo()
        },
        // Cached responses hold the previous account's proxy, weather and map requests.
        object : SessionListener {
            override suspend fun onSessionCleared() = httpCache.clear()
        },
        // Both react to the session themselves; here they only get to upload before an explicit sign-out.
        object : SessionListener {
            override suspend fun flushPendingWrites() = schedule.flushPendingWrites()
        },
        object : SessionListener {
            override suspend fun flushPendingWrites() = courseTracker.flushPendingWrites()
        },
        transit,
        notifications,
        reminders,
        widgets,
        passkeys,
    )

    @SingleIn(AppScope::class)
    @Provides
    internal fun provideWatchdog(auth: AuthRepository, store: AuthStore, scope: CoroutineScope): SessionWatchdog =
        SessionWatchdog(auth, store, scope)

    @SingleIn(AppScope::class)
    @Provides
    internal fun provideAppearance(kv: KeyValueStore, auth: AuthRepository, scope: CoroutineScope): AppearanceRepository =
        AppearanceRepository(kv, auth, scope)

    @SingleIn(AppScope::class)
    @Provides
    internal fun providePasskeys(pb: PocketBaseClient, auth: AuthRepository, authenticator: PasskeyAuthenticator, scope: CoroutineScope): PasskeyRepository =
        PasskeyRepository(pb, auth, authenticator, scope)

    @SingleIn(AppScope::class)
    @Provides
    internal fun provideNotifications(
        pb: PocketBaseClient,
        auth: AuthRepository,
        kv: KeyValueStore,
        notifier: Notifier,
        push: PushRegistrar,
    ): NotificationsRepository = NotificationsRepository(pb, auth, kv, notifier, push)

    @SingleIn(AppScope::class)
    @Provides
    internal fun provideReminders(
        kv: KeyValueStore,
        schedule: ScheduleRepository,
        courseTracker: CourseTrackerRepository,
        transit: TransitRepository,
        notifier: Notifier,
        auth: AuthRepository,
        scope: CoroutineScope,
        updateGate: UpdateGate,
        crypto: CryptoClient,
    ): ReminderScheduler = ReminderScheduler(kv, schedule, courseTracker, transit, notifier, auth, scope, updateGate, crypto)

    @SingleIn(AppScope::class)
    @Provides
    internal fun provideWidgets(
        schedule: ScheduleRepository,
        courseTracker: CourseTrackerRepository,
        auth: AuthRepository,
        host: WidgetHost,
        scope: CoroutineScope,
        updateGate: UpdateGate,
        appearance: AppearanceRepository,
    ): WidgetPublisher = WidgetPublisher(schedule, courseTracker, auth, host, scope, updateGate, appearance)

    @SingleIn(AppScope::class)
    @Provides
    internal fun provideSchedule(
        pb: PocketBaseClient,
        crypto: CryptoClient,
        kv: KeyValueStore,
        connectivity: Connectivity,
        auth: AuthRepository,
        http: HttpClient,
        scope: CoroutineScope,
        neptunSync: NeptunSyncListener,
    ): ScheduleRepository = ScheduleRepository(pb, crypto, kv, connectivity, auth, http, scope, neptunSync)

    @SingleIn(AppScope::class)
    @Provides
    internal fun provideCourseTracker(
        pb: PocketBaseClient,
        crypto: CryptoClient,
        kv: KeyValueStore,
        connectivity: Connectivity,
        auth: AuthRepository,
        scope: CoroutineScope,
    ): CourseTrackerRepository {
        return CourseTrackerRepository(
            cloud = PocketBaseTrackerCloud(pb),
            cipher = CryptoTrackerCipher(crypto),
            kv = kv,
            scope = scope,
            userIdFlow = auth.currentUserId,
            isOnline = { connectivity.online.value },
            onlineChanges = connectivity.online,
        )
    }

    @Provides
    internal fun provideCourseTrackerHooks(tracker: Lazy<CourseTrackerRepository>): ScheduleCourseTrackerHooks =
        CourseTrackerScheduleHooks(tracker)

    /** Lazy: the schedule needs this at construction, before the tracker exists. */
    @Provides
    internal fun provideNeptunSyncListener(tracker: Lazy<CourseTrackerRepository>): NeptunSyncListener =
        CourseTrackerScheduleSync(tracker)

    @SingleIn(AppScope::class)
    @Provides
    internal fun provideTransit(
        pb: PocketBaseClient,
        crypto: CryptoClient,
        kv: KeyValueStore,
        auth: AuthRepository,
        connectivity: Connectivity,
        http: HttpClient,
        config: VectraConfig,
        catalogCache: CatalogDiskCache,
        scope: CoroutineScope,
    ): TransitRepository {
        // The one place that decides whether the Futar feed is real or the demo one.
        val live: FutarSource = RealFutarSource(FutarApi(pb), scope)
        val source = if (config.isDebug) DemoFutarSource(live) else live
        return TransitRepository(pb, crypto, kv, auth, connectivity, source, http, config.catalogOrigin, catalogCache, scope)
    }

    /** Debug builds seed the demo accounts' trackers; started from `AppStartup`, does nothing in release. */
    @SingleIn(AppScope::class)
    @Provides
    internal fun provideDemoSeeder(auth: AuthRepository, transit: TransitRepository, scope: CoroutineScope, config: VectraConfig): DemoSeeder =
        DemoSeeder(auth, transit, scope, enabled = config.isDebug)

    @SingleIn(AppScope::class)
    @Provides
    internal fun provideDeviceLocation(
        location: LocationProvider,
        kv: KeyValueStore,
        pb: PocketBaseClient,
        scope: CoroutineScope,
        weather: WeatherRepository,
    ): DeviceLocation = DeviceLocation(location, kv, scope, canPersist = { pb.auth.current != null }).apply {
        // A grant from the Nearby map or its "Allow" button turns the Settings location switch on too.
        onUserGrant = weather::adoptDeviceGrant
    }

    @SingleIn(AppScope::class)
    @Provides
    internal fun provideWeather(
        http: HttpClient,
        kv: KeyValueStore,
        location: LocationProvider,
        scope: CoroutineScope,
        connectivity: Connectivity,
    ): WeatherRepository = WeatherRepository(http, kv, location, scope, connectivity)

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(
            @Provides config: VectraConfig,
            @Provides dataStore: DataStore<Preferences>,
            @Provides blobs: BlobStore,
            @Provides catalogCache: CatalogDiskCache,
            @Provides http: HttpClient,
            @Provides httpCache: HttpCache,
            @Provides cipher: SecretCipher,
            @Provides connectivity: Connectivity,
            @Provides location: LocationProvider,
            @Provides launcher: Launcher,
            @Provides passkeyAuthenticator: PasskeyAuthenticator,
            @Provides notifier: Notifier,
            @Provides push: PushRegistrar,
            @Provides widgetHost: WidgetHost,
        ): AppGraph
    }
}
