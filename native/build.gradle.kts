plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.baselineprofile) apply false
    alias(libs.plugins.metro) apply false
    alias(libs.plugins.detekt)
}

// One run over all modules' sources: `./gradlew detekt`; `detektBaseline` regenerates the baseline.
detekt {
    source.setFrom("shared/src", "androidApp/src", "baselineprofile/src")
    config.setFrom("config/detekt/detekt.yml")
    baseline = file("config/detekt/baseline.xml")
    buildUponDefaultConfig = true
    parallel = true
}

dependencies {
    detektPlugins(libs.compose.rules.detekt)
}
