package dev.southstar21.vectra.features.update

import platform.Foundation.NSBundle

/**
 * AltStore and SideStore re-sign the app with a development profile, which leaves
 * embedded.mobileprovision in the bundle; TestFlight (iPhone, iPad and Mac) builds have none.
 * Chosen over the App Store receipt URL, whose API is deprecated.
 */
actual fun updateTarget(): UpdateTarget {
    val sideloaded = NSBundle.mainBundle.pathForResource("embedded", ofType = "mobileprovision") != null
    return if (sideloaded) {
        UpdateTarget("Get the update", AltStoreInstallPage)
    } else {
        UpdateTarget("Open TestFlight", TestFlightApp)
    }
}
