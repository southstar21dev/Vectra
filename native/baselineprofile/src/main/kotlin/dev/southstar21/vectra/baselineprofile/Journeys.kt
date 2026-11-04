package dev.southstar21.vectra.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until

const val TargetPackage = "dev.southstar21.vectra"

private const val ScreenTimeoutMs = 30_000L
private const val SettleMs = 1_500L

private val Dashboard = By.desc("Dashboard")

private val RuntimePermissions = listOf(
    "android.permission.ACCESS_COARSE_LOCATION",
    "android.permission.ACCESS_FINE_LOCATION",
    "android.permission.POST_NOTIFICATIONS",
)

/** Permission dialogs would cover the app, and Gradle reinstalls it for each run, so grants don't carry over. */
fun MacrobenchmarkScope.prepareLaunch() {
    pressHome()
    RuntimePermissions.forEach { device.executeShellCommand("pm grant $TargetPackage $it") }
}

/** After launch: the dashboard if there's a session, signing in first if there isn't. */
fun MacrobenchmarkScope.awaitSignedIn() {
    device.wait(Until.hasObject(By.pkg(TargetPackage).depth(0)), ScreenTimeoutMs)
    val onLogin = device.wait(Until.hasObject(By.clazz("android.widget.EditText")), SettleMs * 2)
    if (onLogin && !device.hasObject(Dashboard)) signIn()
    checkNotNull(device.wait(Until.findObject(Dashboard), ScreenTimeoutMs)) { "The dashboard never appeared" }
}

private fun MacrobenchmarkScope.signIn() {
    val args = InstrumentationRegistry.getArguments()
    val email = checkNotNull(args.getString("vectraEmail")?.takeIf { it.isNotEmpty() }) {
        "Signed out: pass -Pvectra.testEmail and -Pvectra.testPassword, or sign in on the device first"
    }
    val password = args.getString("vectraPassword").orEmpty()
    val fields = device.findObjects(By.clazz("android.widget.EditText"))
    fields[0].text = email
    fields[1].text = password
    // The keyboard moves the layout, so close it before looking for the button. Back with no keyboard up
    // would leave the app instead.
    if ("mInputShown=true" in device.executeShellCommand("dumpsys input_method")) device.pressBack()
    device.waitForIdle()
    val buttons = device.findObjects(By.text("Sign in"))
    buttons.maxBy { it.visibleBounds.top }.click()
}

fun MacrobenchmarkScope.runCriticalJourneys() {
    scrollDashboard()
    openTab("Schedule")
    scrollPage()
    openTab("Transit")
    scrollPage()
    openNearbyMap()
    openTab("Settings")
    scrollPage()
    openTab("Dashboard")
}

private fun MacrobenchmarkScope.openTab(label: String) {
    checkNotNull(device.wait(Until.findObject(By.desc(label)), ScreenTimeoutMs)) { "No $label tab" }.click()
    device.waitForIdle()
    Thread.sleep(SettleMs)
}

private fun MacrobenchmarkScope.scrollDashboard() {
    device.waitForIdle()
    Thread.sleep(SettleMs)
    scrollPage()
}

/** Tab pages draw continuously (the glass navbar), so scrolling goes by raw swipes rather than a scrollable node. */
private fun MacrobenchmarkScope.scrollPage() {
    repeat(2) { device.flick(Direction.UP) }
    repeat(2) { device.flick(Direction.DOWN) }
}

private fun UiDevice.flick(direction: Direction) {
    val x = displayWidth / 2
    val high = displayHeight / 4
    val low = displayHeight * 3 / 5
    if (direction == Direction.UP) swipe(x, low, x, high, FlickSteps) else swipe(x, high, x, low, FlickSteps)
    Thread.sleep(SettleMs / 2)
}

private const val FlickSteps = 12

private fun MacrobenchmarkScope.openNearbyMap() {
    val map = device.wait(Until.findObject(By.desc("Open the nearby transit map")), SettleMs * 2) ?: return
    map.click()
    Thread.sleep(SettleMs * 2)
    device.pressBack()
    device.waitForIdle()
    Thread.sleep(SettleMs)
}
