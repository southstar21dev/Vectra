package dev.southstar21.vectra.core.platform

/** "android" or "ios", as shown after the version in Settings › About. */
expect val platformName: String

/** What a passkey registered on this device is called in Settings, such as "Google Pixel 8" or "iPhone". */
expect val deviceDisplayName: String
