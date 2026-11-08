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
import dev.southstar21.vectra.core.platform.Launcher
import dev.southstar21.vectra.core.platform.LocationProvider
import dev.southstar21.vectra.core.platform.Notifier
import dev.southstar21.vectra.core.platform.PasskeyAuthenticator
import dev.southstar21.vectra.core.platform.PushRegistrar
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
import dev.southstar21.vectra.features.widgets.WidgetPublisher
import dev.southstar21.vectra.features.schedule.NeptunSyncListener
import dev.southstar21.vectra.features.schedule.ScheduleCourseTrackerHooks
import dev.southstar21.vectra.features.schedule.ScheduleRepository
import dev.southstar21.vectra.features.settings.AppearanceRepository
import dev.southstar21.vectra.features.transit.DeviceLocation
import dev.southstar21.vectra.features.transit.TrackerStopPicks
import dev.southstar21.vectra.features.transit.TransitRepository
import dev.southstar21.vectra.features.weather.WeatherRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * The app's object graph (Metro, compile-time). Platform pieces come in through the factory;
 * everything here is an app-lifetime singleton, built the first time it is asked for.
 */
@DependencyGraph(AppScope::class)
interface AppGraph {
    val config: VectraConfig
    val http: HttpClient
    val kv: KeyValueStore
    val pb: PocketBaseClient
    val crypto: CryptoClient
    val auth: AuthRepository
    val watchdog: SessionWatchdog
    val scope: CoroutineScope
    val connectivity: Connectivity
    val location: LocationProvider
    val launcher: Launcher
    val passkeyAuthenticator: PasskeyAuthenticator
    val notifier: Notifier
    val push: PushRegistrar
    val startup: ProcessStartup
    val mapEnvironment: MapEnvironment

    val appearanceRepository: AppearanceRepository
    val passkeyRepository: PasskeyRepository
    val notificationsRepository: NotificationsRepository
    val scheduleRepository: ScheduleRepository
    val courseTrackerRepository: CourseTrackerRepository
    val courseTrackerHooks: ScheduleCourseTrackerHooks
    val transitRepository: TransitRepository
    val trackerStopPicks: TrackerStopPicks
    val deviceLocation: DeviceLocation
    val weatherRepository: WeatherRepository
    val reminders: ReminderScheduler
    val widgets: WidgetPublisher

    @SingleIn(AppScope::class)
    @Provides
    fun provideStartup(): ProcessStartup = ProcessStartup()

    @Provides
    fun provideMapEnvironment(http: HttpClient, connectivity: Connectivity): MapEnvironment = MapEnvironment(http, connectivity)

    @SingleIn(AppScope::class)
    @Provides
    fun provideScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @SingleIn(AppScope::class)
    @Provides
    fun provideKv(store: DataStore<Preferences>, blobs: BlobStore): KeyValueStore = KeyValueStore(store, blobs)

    @SingleIn(AppScope::class)
    @Provides
    fun provideAuthStore(kv: KeyValueStore, cipher: SecretCipher): AuthStore = AuthStore(kv, cipher)

    @SingleIn(AppScope::class)
    @Provides
    fun providePb(http: HttpClient, config: VectraConfig, store: AuthStore): PocketBaseClient =
        PocketBaseClient(http, config.pocketbaseUrl, store)

    @SingleIn(AppScope::class)
    @Provides
    fun provideCrypto(pb: PocketBaseClient, scope: CoroutineScope): CryptoClient = CryptoClient(pb, scope)

    /**
     * The sign-out listeners are declared here rather than registered by each repository as
     * it is built, so one that no screen has opened yet still runs. Lazy because a listener
     * may depend on [AuthRepository], and because nothing needs them until a session ends.
     */
    @SingleIn(AppScope::class)
    @Provides
    fun provideAuth(
        pb: PocketBaseClient,
        store: AuthStore,
        kv: KeyValueStore,
        scope: CoroutineScope,
        listeners: Lazy<List<SessionListener>>,
    ): AuthRepository = AuthRepository(pb, store, kv, scope, listeners)

    @Provides
    fun provideSessionListeners(
        crypto: CryptoClient,
        transit: TransitRepository,
        notifications: NotificationsRepository,
        stopPicks: TrackerStopPicks,
        reminders: ReminderScheduler,
        widgets: WidgetPublisher,
    ): List<SessionListener> = listOf(
        object : SessionListener {
            override suspend fun onSessionCleared() = crypto.clearMemo()
        },
        transit,
        notifications,
        stopPicks,
        reminders,
        widgets,
    )

    @SingleIn(AppScope::class)
    @Provides
    fun provideWatchdog(auth: AuthRepository, store: AuthStore, scope: CoroutineScope): SessionWatchdog =
        SessionWatchdog(auth, store, scope)

    @SingleIn(AppScope::class)
    @Provides
    fun provideAppearance(kv: KeyValueStore, auth: AuthRepository, scope: CoroutineScope): AppearanceRepository =
        AppearanceRepository(kv, auth, scope)

    @SingleIn(AppScope::class)
    @Provides
    fun providePasskeys(pb: PocketBaseClient, auth: AuthRepository, authenticator: PasskeyAuthenticator): PasskeyRepository =
        PasskeyRepository(pb, auth, authenticator)

    @SingleIn(AppScope::class)
    @Provides
    fun provideNotifications(
        pb: PocketBaseClient,
        auth: AuthRepository,
        kv: KeyValueStore,
        notifier: Notifier,
        push: PushRegistrar,
    ): NotificationsRepository = NotificationsRepository(pb, auth, kv, notifier, push)

    @SingleIn(AppScope::class)
    @Provides
    fun provideReminders(
        kv: KeyValueStore,
        schedule: ScheduleRepository,
        courseTracker: CourseTrackerRepository,
        transit: TransitRepository,
        notifier: Notifier,
        auth: AuthRepository,
        scope: CoroutineScope,
    ): ReminderScheduler = ReminderScheduler(kv, schedule, courseTracker, transit, notifier, auth, scope)

    @SingleIn(AppScope::class)
    @Provides
    fun provideWidgets(
        schedule: ScheduleRepository,
        courseTracker: CourseTrackerRepository,
        auth: AuthRepository,
        host: WidgetHost,
        scope: CoroutineScope,
    ): WidgetPublisher = WidgetPublisher(schedule, courseTracker, auth, host, scope)

    @SingleIn(AppScope::class)
    @Provides
    fun provideSchedule(
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
    fun provideCourseTracker(
        pb: PocketBaseClient,
        crypto: CryptoClient,
        kv: KeyValueStore,
        connectivity: Connectivity,
        auth: AuthRepository,
        scope: CoroutineScope,
    ): CourseTrackerRepository {
        val uid = MutableStateFlow(auth.session.value?.userId)
        scope.launch { auth.session.collect { uid.value = it?.userId } }
        return CourseTrackerRepository(
            cloud = PocketBaseTrackerCloud(pb),
            cipher = CryptoTrackerCipher(crypto),
            kv = kv,
            scope = scope,
            userIdFlow = uid,
            isOnline = { connectivity.online.value },
        )
    }

    @Provides
    fun provideCourseTrackerHooks(tracker: Lazy<CourseTrackerRepository>): ScheduleCourseTrackerHooks =
        CourseTrackerScheduleHooks(tracker)

    /** Lazy both ways: the schedule needs this at construction, and this reads the schedule back. */
    @Provides
    fun provideNeptunSyncListener(tracker: Lazy<CourseTrackerRepository>, schedule: Lazy<ScheduleRepository>): NeptunSyncListener =
        CourseTrackerScheduleSync(tracker, schedule)

    @SingleIn(AppScope::class)
    @Provides
    fun provideTransit(
        pb: PocketBaseClient,
        crypto: CryptoClient,
        kv: KeyValueStore,
        connectivity: Connectivity,
        location: LocationProvider,
        http: HttpClient,
        config: VectraConfig,
        scope: CoroutineScope,
    ): TransitRepository = TransitRepository(pb, crypto, kv, connectivity, location, http, config.catalogOrigin, scope)

    @SingleIn(AppScope::class)
    @Provides
    fun provideTrackerStopPicks(): TrackerStopPicks = TrackerStopPicks()

    @SingleIn(AppScope::class)
    @Provides
    fun provideDeviceLocation(
        transit: TransitRepository,
        kv: KeyValueStore,
        pb: PocketBaseClient,
        scope: CoroutineScope,
    ): DeviceLocation = DeviceLocation(transit.location, kv, scope, canPersist = { pb.auth.current != null })

    @SingleIn(AppScope::class)
    @Provides
    fun provideWeather(http: HttpClient, kv: KeyValueStore, location: LocationProvider, scope: CoroutineScope): WeatherRepository =
        WeatherRepository(http, kv, location, scope)

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(
            @Provides config: VectraConfig,
            @Provides dataStore: DataStore<Preferences>,
            @Provides blobs: BlobStore,
            @Provides http: HttpClient,
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
